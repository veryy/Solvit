package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

class ChatAgentPlugin(private val requireDriver: Boolean = false) : SolvitPlugin {
    override val id = "builtin.agent.chat"
    override val requires = setOf(Services.Model, Services.Tools, ContextHookServiceKey, ModelProfilesServiceKey, RequestInspectionServiceKey, ContextPreparationServiceKey) + if (requireDriver) setOf(AgentDriverServiceKey) else emptySet()
    override val provides = setOf(Services.Agent)
    override suspend fun enable(context: PluginContext) {
        val provider = context.require(Services.Model)
        context.register(Services.Agent, ToolAgentRuntime(provider, context.require(Services.Tools), hooks = context.require(ContextHookServiceKey), modelProfiles = context.require(ModelProfilesServiceKey), inspection = context.require(RequestInspectionServiceKey), contextPreparation = context.require(ContextPreparationServiceKey), driver = if (requireDriver) context.require(AgentDriverServiceKey) else NativeAgentDriver))
    }
}

class DefaultChatService(
    private val store: ChatStore,
    private val agent: AgentRuntime,
    private val events: EventBus,
    private val roles: RoleService? = null,
    private val workspaces: WorkspaceService? = null,
    private val modelProfiles: ModelProfilesService? = null,
    private val postTurnHooks: PostTurnHookRegistry? = null,
    private val attachmentStore: ChatAttachmentStore? = null,
) : ChatService {
    // V1 permits one active turn. Deletion cannot race with a stream settlement.
    private val turn = Mutex()
    override fun sessions() = store.sessions()
    override fun messages(sessionId: String) = store.messages(sessionId)
    override fun executionRuns(sessionId: String) = store.executionRuns(sessionId)
    override suspend fun executionEvents(sessionId: String, runId: String) = store.executionEvents(sessionId,runId)
    override suspend fun executionFileContent(sessionId: String, runId: String, sequence: Int, path: String,
        authorize: suspend () -> Unit) = store.executionFileContent(sessionId,runId,sequence,path,authorize)
    override suspend fun createSession() = store.createSession()
    override suspend fun deleteSession(sessionId: String) {
        check(turn.tryLock()) { "请先停止生成" }
        try { store.deleteSession(sessionId); roles?.select(sessionId, null); workspaces?.unbind(sessionId) } finally { turn.unlock() }
    }

    override val supportsReplyRetry = true
    override fun retryLatestReply(sessionId: String, replyId: String, config: ModelConfig,
        authorization: ToolAuthorization): Flow<ModelChunk> =
        sendTurn(sessionId, "", emptyList(), config, authorization, retryReplyId = replyId)

    override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization): Flow<ModelChunk> =
        sendAttachments(sessionId, text, emptyList(), config, authorization)
    override fun sendAttachments(sessionId: String, text: String, attachments: List<ChatAttachment>, config: ModelConfig,
        authorization: ToolAuthorization): Flow<ModelChunk> {
        val snapshot = attachments.toList()
        return sendTurn(sessionId, text, snapshot, config, authorization)
    }
    private fun sendTurn(sessionId: String, text: String, attachments: List<ChatAttachment>, config: ModelConfig,
        authorization: ToolAuthorization, retryReplyId: String? = null): Flow<ModelChunk> = flow {
        require(retryReplyId != null || text.isNotBlank() || attachments.isNotEmpty())
        require(attachments.isEmpty() || attachmentStore != null) { "附件服务未启用" }
        ChatMessage("validation", sessionId, Role.USER, text, attachments = attachments)
        config.validate()?.let { error(it) }
        check(turn.tryLock()) { "已有回复正在生成" }
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var native: ProviderTranscript? = null
        val transcript = mutableListOf<ModelMessage>()
        var settled = false
        var admitted = false
        var sourceUserId: String? = null
        var sourceRole: AgentRole? = null
        var sourceProfileId: String? = null
        var sourceTargets = emptyList<PostTurnTarget>()
        var journal: StoredExecutionJournal? = null
        suspend fun persist(message: ChatMessage) {
            store.append(message)
            events.publish(CoreEvent.MessageStored(sessionId, message.id))
        }
        suspend fun settle(status: MessageStatus) {
            val batch = transcript.map { message -> ChatMessage(
                UUID.randomUUID().toString(), sessionId, message.role, message.content, status,
                toolCalls = message.toolCalls, toolCallId = message.toolCallId,
                reasoning = message.reasoning, toolStage = message.toolStage, providerTranscript = message.providerTranscript,
            ) } + ChatMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT, answer.toString(), status, reasoning = reasoning.toString(), providerTranscript = native)
            if (status == MessageStatus.COMPLETE) {
                val final = batch.last()
                store.appendCompletedTurn(batch, CompletedChatTurn(final.id, sessionId, requireNotNull(sourceUserId), final.id,
                    sourceRole?.id, sourceRole?.name, sourceRole?.pluginIds, sourceProfileId,
                    config.providerId, config.model, System.currentTimeMillis(), sourceTargets))
            } else store.appendBatch(batch)
            batch.forEach { events.publish(CoreEvent.MessageStored(sessionId, it.id)) }
            settled = true
            journal?.finish(when(status) {
                MessageStatus.COMPLETE -> ExecutionStatus.COMPLETE
                MessageStatus.INTERRUPTED -> ExecutionStatus.CANCELLED
                MessageStatus.FAILED -> ExecutionStatus.FAILED
            })
        }
        try {
            val role = roles?.selected(sessionId)
            sourceRole = role?.copy(pluginIds = role.pluginIds.toSet(), skillIds = role.skillIds.toSet())
            sourceProfileId = modelProfiles?.state?.value?.profiles?.firstOrNull { it.config === config }?.id
            sourceTargets = if (store.supportsCompletedTurns) postTurnHooks?.admit(sourceRole).orEmpty() else emptyList()
            val conversation = workspaces?.bound(sessionId)?.let { ConversationContext(sessionId, it.id) }
            val user = if (retryReplyId == null) {
                ChatMessage(UUID.randomUUID().toString(), sessionId, Role.USER, text.trim(), attachments = attachments)
            } else {
                val saved = store.history(sessionId)
                val latest = requireNotNull(saved.lastOrNull()) { "原用户消息已不存在" }
                require(latest.id == retryReplyId && latest.role == Role.ASSISTANT &&
                    latest.status != MessageStatus.COMPLETE && latest.toolCalls.isEmpty()) { "只能重试当前最后一条失败或停止的回复" }
                requireNotNull(saved.lastOrNull { it.role == Role.USER }) { "原用户消息已不存在" }.also {
                    require(it.status == MessageStatus.COMPLETE) { "原用户消息已不存在" }
                }
            }
            sourceUserId = user.id
            if (retryReplyId == null) persist(user)
            admitted = true
            if(store.supportsExecutionJournal) {
                val run=ExecutionRun(UUID.randomUUID().toString(),sessionId,user.id,sourceRole?.id,conversation?.workspaceId,System.currentTimeMillis())
                if(!store.beginExecutionRun(run)) throw ExecutionJournalException()
                journal=StoredExecutionJournal(store,run,listOf(config.apiKey)+modelProfiles?.state?.value?.profiles.orEmpty().map { it.config.apiKey },workspaces=workspaces)
            }
            val history = store.history(sessionId).filter { it.status == MessageStatus.COMPLETE }
            val sections = ChatContextComposer.sections(role, config.systemPrompt)
            val messages = buildList {
                val instructions = ChatContextComposer.render(sections)
                if (instructions.isNotBlank()) add(ModelMessage(Role.SYSTEM, instructions))
                val projected = completeContext(history, trackSources = true)
                // Bound the retained bytes before loading/encoding any history media.
                // Never allocate one full 20MiB buffer per historical message first.
                val attachmentBytes = projected.sumOf { message -> message.attachments.sumOf { it.sizeBytes } }
                if (attachmentBytes > ChatAttachment.MAX_ATTACHMENT_BYTES)
                    throw ModelException("本次会话附件资料超过20MiB请求限制，请新建对话；原消息和附件已保留")
                val resolver = attachmentStore?.let(::AttachmentContentResolver)
                projected.forEach { message ->
                    if (message.attachments.isEmpty()) add(message)
                    else add((resolver ?: error("附件服务未启用")).resolve(sessionId, message))
                }
            }
            var done = false
            agent.run(ModelRequest(config, messages, authorization = authorization, agentRole = role, conversation = conversation,
                sessionId = sessionId, executionJournal=journal ?: NoExecutionJournal,
                promptSources = sections.map { PromptSourceInfo(it.name, it.source, it.text.length) })).collect { chunk ->
                check(!done) { "Provider emitted after completion" }
                when (chunk) {
                    is ModelChunk.Text -> { answer.append(chunk.value); emit(chunk) }
                    is ModelChunk.NativeTranscript -> { native = chunk.value; emit(chunk) }
                    is ModelChunk.Reasoning -> { reasoning.append(chunk.value); emit(chunk) }
                    is ModelChunk.ToolCalls -> error("Agent 暴露了未处理的工具调用")
                    is ModelChunk.Transcript -> {
                        transcript += chunk.message
                        if (chunk.message.role == Role.ASSISTANT) { answer.setLength(0); reasoning.setLength(0); native = null }
                        emit(chunk)
                    }
                    is ModelChunk.ToolActivity -> emit(chunk)
                    ModelChunk.Done -> {
                        check(answer.isNotBlank()) { "模型没有返回文本回复" }
                        settle(MessageStatus.COMPLETE)
                        done = true
                        emit(chunk)
                    }
                }
            }
            check(done) { "模型响应提前结束" }
        } catch (e: CancellationException) {
            if (admitted && !settled) withContext(NonCancellable) { settle(MessageStatus.INTERRUPTED) }
            throw e
        } catch (e: Exception) {
            if (admitted && !settled) {
                try { withContext(NonCancellable) { settle(MessageStatus.FAILED) } }
                catch (storageError: Exception) { e.addSuppressed(storageError) }
            }
            throw e
        } finally { turn.unlock() }
    }
}

/** Drop orphaned call groups from interrupted writes; never send unmatched tool IDs. */
internal fun completeContext(history: List<ChatMessage>, trackSources: Boolean = false): List<ModelMessage> {
    val result = mutableListOf<ModelMessage>()
    var index = 0
    while (index < history.size) {
        val message = history[index++]
        if (message.role == Role.TOOL) continue
        if (message.toolCalls.isEmpty()) { result += ModelMessage(message.role, message.content, providerTranscript=message.providerTranscript,
            sourceMessageId = message.id.takeIf { trackSources }, attachments = message.attachments); continue }
        val batch = history.drop(index).takeWhile { it.role == Role.TOOL }
        index += batch.size
        val ids = message.toolCalls.map { it.id }
        if (ids.toSet().size != ids.size || batch.size != ids.size || batch.map { it.toolCallId }.toSet() != ids.toSet()) continue
        result += ModelMessage(message.role, message.content, message.toolCalls, providerTranscript=message.providerTranscript,
            sourceMessageId = message.id.takeIf { trackSources }, attachments = message.attachments)
        result += batch.map { ModelMessage(Role.TOOL, it.content, toolCallId = it.toolCallId, toolStage = it.toolStage,
            sourceMessageId = it.id.takeIf { trackSources }) }
    }
    return result
}

class ChatServicePlugin(private val useWorkspaces: Boolean = false, private val useAttachments: Boolean = false) : SolvitPlugin {
    override val id = "builtin.chat"
    override val requires = buildSet { addAll(setOf(Services.Storage, Services.Agent, RoleServiceKey, ModelProfilesServiceKey, PostTurnHookServiceKey)); if (useWorkspaces) add(WorkspaceServiceKey); if (useAttachments) add(ChatAttachmentStoreKey) }
    override val provides = setOf(Services.Chat)
    override suspend fun enable(context: PluginContext) {
        context.register(Services.Chat, DefaultChatService(
            context.require(Services.Storage), context.require(Services.Agent), context.events, context.require(RoleServiceKey),
            if (useWorkspaces) context.require(WorkspaceServiceKey) else null,
            context.require(ModelProfilesServiceKey),
            context.require(PostTurnHookServiceKey),
            if (useAttachments) context.require(ChatAttachmentStoreKey) else null,
        ))
    }
}
