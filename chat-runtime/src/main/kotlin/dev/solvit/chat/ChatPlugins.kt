package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

class ChatAgentPlugin : SolvitPlugin {
    override val id = "builtin.agent.chat"
    override val requires = setOf(Services.Model, Services.Tools, ContextHookServiceKey)
    override val provides = setOf(Services.Agent)
    override suspend fun enable(context: PluginContext) {
        val provider = context.require(Services.Model)
        context.register(Services.Agent, ToolAgentRuntime(provider, context.require(Services.Tools), hooks = context.require(ContextHookServiceKey)))
    }
}

class DefaultChatService(
    private val store: ChatStore,
    private val agent: AgentRuntime,
    private val events: EventBus,
    private val roles: RoleService? = null,
    private val workspaces: WorkspaceService? = null,
) : ChatService {
    // V1 permits one active turn. Deletion cannot race with a stream settlement.
    private val turn = Mutex()
    override fun sessions() = store.sessions()
    override fun messages(sessionId: String) = store.messages(sessionId)
    override suspend fun createSession() = store.createSession()
    override suspend fun deleteSession(sessionId: String) {
        check(turn.tryLock()) { "请先停止生成" }
        try { store.deleteSession(sessionId); roles?.select(sessionId, null); workspaces?.unbind(sessionId) } finally { turn.unlock() }
    }

    override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization): Flow<ModelChunk> = flow {
        require(text.isNotBlank())
        config.validate()?.let { error(it) }
        check(turn.tryLock()) { "已有回复正在生成" }
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        val transcript = mutableListOf<ModelMessage>()
        var settled = false
        var admitted = false
        suspend fun persist(message: ChatMessage) {
            store.append(message)
            events.publish(CoreEvent.MessageStored(sessionId, message.id))
        }
        suspend fun settle(status: MessageStatus) {
            val batch = transcript.map { message -> ChatMessage(
                UUID.randomUUID().toString(), sessionId, message.role, message.content, status,
                toolCalls = message.toolCalls, toolCallId = message.toolCallId,
                reasoning = message.reasoning, toolStage = message.toolStage,
            ) } + ChatMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT, answer.toString(), status, reasoning = reasoning.toString())
            store.appendBatch(batch)
            batch.forEach { events.publish(CoreEvent.MessageStored(sessionId, it.id)) }
            settled = true
        }
        try {
            val role = roles?.selected(sessionId)
            val conversation = workspaces?.bound(sessionId)?.let { ConversationContext(sessionId, it.id) }
            persist(ChatMessage(UUID.randomUUID().toString(), sessionId, Role.USER, text.trim()))
            admitted = true
            val history = store.history(sessionId).filter { it.status == MessageStatus.COMPLETE }
            val messages = buildList {
                if (config.systemPrompt.isNotBlank()) add(ModelMessage(Role.SYSTEM, config.systemPrompt))
                if (!role?.prompt.isNullOrBlank()) add(ModelMessage(Role.SYSTEM, role!!.prompt))
                addAll(completeContext(history))
            }
            var done = false
            agent.run(ModelRequest(config, messages, authorization = authorization, agentRole = role, conversation = conversation)).collect { chunk ->
                check(!done) { "Provider emitted after completion" }
                when (chunk) {
                    is ModelChunk.Text -> { answer.append(chunk.value); emit(chunk) }
                    is ModelChunk.Reasoning -> { reasoning.append(chunk.value); emit(chunk) }
                    is ModelChunk.ToolCalls -> error("Agent 暴露了未处理的工具调用")
                    is ModelChunk.Transcript -> {
                        transcript += chunk.message
                        if (chunk.message.role == Role.ASSISTANT) { answer.setLength(0); reasoning.setLength(0) }
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
internal fun completeContext(history: List<ChatMessage>): List<ModelMessage> {
    val result = mutableListOf<ModelMessage>()
    var index = 0
    while (index < history.size) {
        val message = history[index++]
        if (message.role == Role.TOOL) continue
        if (message.toolCalls.isEmpty()) { result += ModelMessage(message.role, message.content); continue }
        val batch = history.drop(index).takeWhile { it.role == Role.TOOL }
        index += batch.size
        val ids = message.toolCalls.map { it.id }
        if (ids.toSet().size != ids.size || batch.size != ids.size || batch.map { it.toolCallId }.toSet() != ids.toSet()) continue
        result += ModelMessage(message.role, message.content, message.toolCalls)
        result += batch.map { ModelMessage(Role.TOOL, it.content, toolCallId = it.toolCallId) }
    }
    return result
}

class ChatServicePlugin(private val useWorkspaces: Boolean = false) : SolvitPlugin {
    override val id = "builtin.chat"
    override val requires = buildSet { addAll(setOf(Services.Storage, Services.Agent, RoleServiceKey)); if (useWorkspaces) add(WorkspaceServiceKey) }
    override val provides = setOf(Services.Chat)
    override suspend fun enable(context: PluginContext) {
        context.register(Services.Chat, DefaultChatService(
            context.require(Services.Storage), context.require(Services.Agent), context.events, context.require(RoleServiceKey),
            if (useWorkspaces) context.require(WorkspaceServiceKey) else null,
        ))
    }
}
