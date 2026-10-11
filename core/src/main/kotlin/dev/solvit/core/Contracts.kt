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
)
data class ChatSession(val id: String, val title: String, val updatedAt: Long)
data class ToolCall(val id: String, val name: String, val argumentsJson: String)
data class ModelMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/** Not a data class: toString must never reveal API keys. */
class ModelConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val systemPrompt: String = "你是 Solvit，一个认真、可靠的 AI 助手。",
) {
    fun validate(): String? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return "API 地址格式不正确"
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null)
            return "API 地址需为不含账号、查询参数的基础地址"
        val local = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]", "::1")
        if (uri.scheme != "https" && !(uri.scheme == "http" && local)) return "请使用 HTTPS；本地模型可使用 HTTP"
        if (model.isBlank()) return "请输入模型名称"
        if (apiKey.any { it == '\r' || it == '\n' }) return "API Key 格式不正确"
        return null
    }
    override fun toString() = "ModelConfig(model=$model, apiKey=<redacted>)"
}

data class ModelRequest(
    val config: ModelConfig,
    val messages: List<ModelMessage>,
    val tools: List<ToolDescriptor> = emptyList(),
    val authorization: ToolAuthorization = DenyTools,
)
sealed interface ModelChunk {
    data class Text(val value: String) : ModelChunk
    data class ToolCalls(val calls: List<ToolCall>) : ModelChunk
    data class ToolActivity(val call: ToolCall, val stage: ToolStage, val result: String? = null) : ModelChunk
    data class Transcript(val message: ModelMessage) : ModelChunk
    data object Done : ModelChunk
}
interface ModelProvider {
    val id: String
    fun stream(request: ModelRequest): Flow<ModelChunk>
}
class ModelException(message: String) : IOException(message)
interface ChatStore {
    fun sessions(): Flow<List<ChatSession>>
    fun messages(sessionId: String): Flow<List<ChatMessage>>
    suspend fun createSession(): ChatSession
    suspend fun history(sessionId: String): List<ChatMessage>
    suspend fun append(message: ChatMessage)
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
