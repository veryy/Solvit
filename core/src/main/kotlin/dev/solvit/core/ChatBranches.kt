package dev.solvit.core

/** The complete reply includes intermediate assistant/tool rounds, never a partial tool pair. */
enum class BranchMode { BEFORE_USER, AFTER_REPLY }
data class BranchPoint(val messageId: String, val mode: BranchMode)
interface ChatBranchService {
    suspend fun fork(sessionId: String, point: BranchPoint): ChatSession
}
val ChatBranchServiceKey = ServiceKey("chat.branches", ChatBranchService::class)

/** Resolve a UI anchor to a whole conversation turn before the storage transaction copies it. */
fun branchPrefix(history: List<ChatMessage>, point: BranchPoint): List<ChatMessage> {
    val index = history.indexOfFirst { it.id == point.messageId }
    require(index >= 0) { "消息已不存在，请刷新对话" }
    val anchor = history[index]
    val end = when (point.mode) {
        BranchMode.BEFORE_USER -> {
            require(anchor.role == Role.USER) { "编辑分支必须从用户消息开始" }
            index
        }
        BranchMode.AFTER_REPLY -> {
            require(anchor.role == Role.ASSISTANT) { "回复分支必须从助手消息开始" }
            val next = (index + 1 until history.size).firstOrNull { history[it].role == Role.USER }
            next ?: history.size
        }
    }
    return history.take(end)
}
