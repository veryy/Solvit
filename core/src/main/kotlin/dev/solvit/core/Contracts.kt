package dev.solvit.core

import kotlinx.coroutines.flow.Flow
import java.net.URI
import java.io.IOException

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }
enum class MessageStatus { COMPLETE, INTERRUPTED, FAILED }
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoning: String = "",
    val toolStage: ToolStage? = null,
    val providerTranscript: ProviderTranscript? = null,
)
data class ChatSession(val id: String, val title: String, val updatedAt: Long,
    val originSessionId: String? = null, val originMessageId: String? = null)
data class ToolCall(val id: String, val name: String, val argumentsJson: String)
data class ModelMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoning: String = "",
    val toolStage: ToolStage? = null,
    val providerTranscript: ProviderTranscript? = null,
    val sourceMessageId: String? = null,
)

/** Not a data class: toString must never reveal API keys. */
class ModelConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val systemPrompt: String = "你是 Solvit，一个认真、可靠的 AI 助手。",
    val modelListUrl: String = "",
    val modelListUsesApiKey: Boolean = false,
    val providerId: String = "openai-compatible",
    val providerOptions: Map<String,String> = emptyMap(),
    val contextBudget: ContextBudgetPolicy = ContextBudgetPolicy(),
) {
    fun validate(): String? {
        validateConnection()?.let { return it }
        if (model.length > 256 || model.any(Char::isISOControl)) return "模型名称无效"
        if (model.isBlank()) return "请输入模型名称或获取模型列表后选择"
        return null
    }
    fun validateConnection(): String? {
        val explicitOutput = providerOptions["max_tokens"]
        if (explicitOutput != null && (explicitOutput.toIntOrNull() == null || explicitOutput.toInt() !in 1..1_000_000)) return "最大输出令牌无效"
        runCatching { contextBudget.copy(outputTokens = maxOf(contextBudget.outputTokens, explicitOutput?.toInt() ?: 0)).validate() }
            .exceptionOrNull()?.let { return it.message ?: "上下文预算无效" }
        if (!providerId.matches(Regex("[A-Za-z0-9_.-]{1,80}"))) return "模型协议 ID 无效"
        if (providerOptions.size > 32 || providerOptions.any { it.key.length !in 1..80 || it.value.length > 1024 }) return "模型协议参数超过限制"
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return "API 地址格式不正确"
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
            return "API 地址需为不含账号、查询参数的基础地址"
        val local = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1")
        if (uri.scheme != "https" && !(uri.scheme == "http" && local)) return "请使用 HTTPS；本地模型可使用 HTTP"
        if (apiKey.any { it == '\r' || it == '\n' }) return "API Key 格式不正确"
        if (modelListUrl.isNotBlank()) {
            val list = runCatching { URI(modelListUrl) }.getOrNull() ?: return "模型列表地址格式不正确"
            val listLocal = list.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1")
            if (list.host.isNullOrBlank() || list.userInfo != null || list.fragment != null || modelListUrl.length > 8192 ||
                (list.scheme != "https" && !(list.scheme == "http" && listLocal))) return "模型列表地址需为 HTTPS；本地服务可使用 HTTP"
        }
        return null
    }
    override fun toString() = "ModelConfig(model=$model, apiKey=<redacted>)"
}

data class AvailableModel(val id: String, val name: String = id, val owner: String? = null)
interface ModelCatalogService { suspend fun models(config: ModelConfig): List<AvailableModel> }
val ModelCatalogServiceKey = ServiceKey("model.catalog", ModelCatalogService::class)

data class ModelRequest(
    val config: ModelConfig,
    val messages: List<ModelMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val authorization: ToolAuthorization = DenyTools,
    val agentRole: AgentRole? = null,
    val conversation: ConversationContext? = null,
    val sessionId: String? = null,
    val promptSources: List<PromptSourceInfo> = emptyList(),
    val contextMeasurement: ContextMeasurement? = null,
    /** Dynamic plugin data, projected by adapters independently of immutable system rules. */
    val runtimeContext: List<String> = emptyList(),
)
sealed interface ModelChunk {
    data class Text(val value: String) : ModelChunk
    /** Provider-supplied reasoning, kept separate from the visible answer. */
    data class Reasoning(val value: String) : ModelChunk
    data class ToolCalls(val calls: List<ToolCall>) : ModelChunk
    data class NativeTranscript(val value: ProviderTranscript) : ModelChunk
    data class ToolActivity(val call: ToolCall, val stage: ToolStage, val result: String? = null) : ModelChunk
    data class Transcript(val message: ModelMessage) : ModelChunk
    data object Done : ModelChunk
}
interface ModelProvider {
    val id: String
    fun separatesRuntimeContext(config: ModelConfig): Boolean = false
    fun validate(config: ModelConfig): String? = config.validate()
    /** Adapter-owned output reservation, including parameters retained by a pending native tool turn. */
    fun reservedOutputTokens(request: ModelRequest): Int = maxOf(request.config.contextBudget.outputTokens,
        request.config.providerOptions["max_tokens"]?.toIntOrNull() ?: 0)
    fun stream(request: ModelRequest): Flow<ModelChunk>
}
/** Diagnostics are already redacted by the provider; never include request prompts. */
data class ModelErrorDetails(val endpoint: String, val httpStatus: Int? = null,
    val response: String = "", val requestId: String? = null) {
    fun display(): String = buildString {
        append("地址：").append(endpoint)
        httpStatus?.let { append("\nHTTP：").append(it) }
        requestId?.let { append("\n请求 ID：").append(it) }
        append("\n\n").append(response.ifBlank { "服务未返回响应正文" })
    }
}
class ModelException(message: String, val details: ModelErrorDetails? = null) : IOException(message)
interface ChatStore {
    /** Only supporting stores promise atomic completed-turn anchors for background work. */
    val supportsCompletedTurns: Boolean get() = false
    fun sessions(): Flow<List<ChatSession>>
    fun messages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun createSession(): ChatSession
    /** Implementations must copy a stable prefix atomically, with fresh message IDs. */
    suspend fun forkSession(sessionId: String, point: BranchPoint): ChatSession = error("当前存储不支持聊天分支")
    suspend fun history(sessionId: String): List<ChatMessage>
    suspend fun contextSummary(sessionId: String): ContextSummary? = null
    /** Compare-and-swap plus prefix validation in one transaction. Unsupported stores return false. */
    suspend fun saveContextSummary(sessionId: String, expected: ContextSummary?, replacement: ContextSummary): Boolean = false
    suspend fun append(message: ChatMessage)
    suspend fun appendBatch(messages: List<ChatMessage>) { messages.forEach { append(it) } }
    suspend fun appendCompletedTurn(messages: List<ChatMessage>, turn: CompletedChatTurn) {
        turn.validate()
        appendBatch(messages)
    }
    suspend fun recordedTurns(afterSequence: Long, limit: Int = 20): List<RecordedChatTurn> = emptyList()
    suspend fun postTurnJobs(statuses: Set<PostTurnJobStatus>, limit: Int = 20): List<PostTurnJob> = emptyList()
    /** Most recently updated first; listing order is independent of consumer admission order. */
    suspend fun recentPostTurnJobs(before: PostTurnJobCursor? = null, limit: Int = 20): List<PostTurnJob> = emptyList()
    /** Page queries filter ownership in the database before applying the page limit. */
    suspend fun ownPostTurnJobs(pluginId: String, before: PostTurnJobCursor?, limit: Int,
        authorize: suspend () -> Unit): List<PostTurnJob> = emptyList()
    /** Unsupported stores refuse mutation; implementations must authorize inside the write lock. */
    suspend fun controlPostTurnJobs(pluginId: String, action: PostTurnTaskControl, now: Long,
        authorize: suspend () -> Unit): Int = 0
    suspend fun postTurnJob(id: String): PostTurnJob? = null
    suspend fun postTurnMessages(turn: CompletedChatTurn): List<ChatMessage> = history(turn.sessionId)
        .filter { it.id == turn.userMessageId || it.id == turn.assistantMessageId }
    suspend fun claimPostTurnJob(id: String, expectedAttempt: Int, now: Long): Boolean = false
    suspend fun finishPostTurnJob(id: String, attempt: Int, status: PostTurnJobStatus, failure: PostTurnFailure?, now: Long): Boolean = false
    /** Failed jobs retry only through an explicit action, with at most three claims. */
    suspend fun retryPostTurnJob(id: String, expectedAttempt: Int, now: Long): Boolean = false
    suspend fun recoverInterruptedPostTurnJobs(now: Long): Int = 0
    /** Explicit retirement only; stale controls cannot remove retried or running work. */
    suspend fun deletePostTurnJob(id: String, expectedAttempt: Int, expectedStatus: PostTurnJobStatus): Boolean = false
    /** Bounded removal of completed/skipped jobs, preserving retryable work and source history. */
    suspend fun deleteFinishedPostTurnJobs(pluginId: String, limit: Int = 100): Int = 0
    suspend fun deleteSession(sessionId: String)
}
enum class ToolStage { CONFIRMING, RUNNING, SUCCEEDED, FAILED, DENIED }

interface AgentRuntime {
    fun run(request: ModelRequest): Flow<ModelChunk>
}
interface ChatService {
    fun sessions(): Flow<List<ChatSession>>
    fun messages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun createSession(): ChatSession
    suspend fun deleteSession(sessionId: String)
    fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization = DenyTools): Flow<ModelChunk>
}

object Services {
    val Model = ServiceKey("model", ModelProvider::class)
    val Storage = ServiceKey("storage", ChatStore::class)
    val Agent = ServiceKey("agent", AgentRuntime::class)
    val Chat = ServiceKey("chat", ChatService::class)
    val Tools = ServiceKey("tools", ToolRegistry::class)
}
