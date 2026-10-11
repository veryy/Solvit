package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

class ChatAgentPlugin : SolvitPlugin {
    override val id = "builtin.agent.chat"
    override val requires = setOf(Services.Model)
    override val provides = setOf(Services.Agent)
    override suspend fun enable(context: PluginContext) {
        val provider = context.require(Services.Model)
        context.register(Services.Agent, object : AgentRuntime {
            override fun run(request: ModelRequest) = provider.stream(request)
        })
    }
}

class DefaultChatService(
    private val store: ChatStore,
    private val agent: AgentRuntime,
    private val events: EventBus,
) : ChatService {
    // V1 permits one active turn. Deletion cannot race with a stream settlement.
    private val turn = Mutex()
    override fun sessions() = store.sessions()
    override fun messages(sessionId: String) = store.messages(sessionId)
    override suspend fun createSession() = store.createSession()
    override suspend fun deleteSession(sessionId: String) {
        check(turn.tryLock()) { "请先停止生成" }
        try { store.deleteSession(sessionId) } finally { turn.unlock() }
    }

    override fun send(sessionId: String, text: String, config: ModelConfig): Flow<ModelChunk> = flow {
        require(text.isNotBlank())
        config.validate()?.let { error(it) }
        check(turn.tryLock()) { "已有回复正在生成" }
        val answer = StringBuilder()
        var settled = false
        var admitted = false
        suspend fun persist(message: ChatMessage) {
            store.append(message)
            events.publish(CoreEvent.MessageStored(sessionId, message.id))
        }
        suspend fun settle(status: MessageStatus) {
            persist(ChatMessage(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT, answer.toString(), status))
            settled = true
        }
        try {
            persist(ChatMessage(UUID.randomUUID().toString(), sessionId, Role.USER, text.trim()))
            admitted = true
            val history = store.history(sessionId).filter { it.status == MessageStatus.COMPLETE }
            val messages = buildList {
                if (config.systemPrompt.isNotBlank()) add(ModelMessage(Role.SYSTEM, config.systemPrompt))
                addAll(history.map { ModelMessage(it.role, it.content) })
            }
            var done = false
            agent.run(ModelRequest(config, messages)).collect { chunk ->
                check(!done) { "Provider emitted after completion" }
                when (chunk) {
                    is ModelChunk.Text -> { answer.append(chunk.value); emit(chunk) }
                    is ModelChunk.ToolCalls -> error("工具调用运行器尚未接入")
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

class ChatServicePlugin : SolvitPlugin {
    override val id = "builtin.chat"
    override val requires = setOf(Services.Storage, Services.Agent)
    override val provides = setOf(Services.Chat)
    override suspend fun enable(context: PluginContext) {
        context.register(Services.Chat, DefaultChatService(
            context.require(Services.Storage), context.require(Services.Agent), context.events,
        ))
    }
}
