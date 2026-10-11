package dev.solvit.app

import dev.solvit.core.*

/** One assistant bubble per user turn, including every tool round and its final answer. */
data class ChatRow(val id: String, val user: ChatMessage? = null,
    val messages: List<ChatMessage> = emptyList(), val activity: List<ModelChunk.ToolActivity> = emptyList(),
    val generating: Boolean = false)

fun presentChat(history: List<ChatMessage>, busy: Boolean = false, baseline: String? = null,
    sendingText: String = "", transcript: List<ModelMessage> = emptyList(), draft: String = "",
    reasoning: String = "", activity: List<ModelChunk.ToolActivity> = emptyList()): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var anchor = "orphan"
    var pending = mutableListOf<ChatMessage>()
    fun flush() { if (pending.isNotEmpty()) { rows += ChatRow("reply-$anchor", messages = pending.toList()); pending = mutableListOf() } }
    for (message in history) when (message.role) {
        Role.USER -> { flush(); anchor = message.id; rows += ChatRow(message.id, user = message) }
        Role.ASSISTANT, Role.TOOL -> { if (anchor == "orphan" && pending.isEmpty()) anchor = message.id; pending += message }
        Role.SYSTEM -> Unit
    }
    flush()
    if (!busy) return rows
    val baselineIndex = baseline?.let { id -> history.indexOfFirst { it.id == id } } ?: -1
    val userIndex = history.indexOfLast { it.role == Role.USER }.takeIf { it > baselineIndex }
    // Atomic settlement can reach the history Flow before the Done event reaches the UI.
    if (userIndex != null && history.drop(userIndex + 1).any { it.role == Role.ASSISTANT }) return rows
    val userId = userIndex?.let { history[it].id } ?: "pending-user"
    if (userIndex == null) rows += ChatRow(userId, user = ChatMessage(userId, "", Role.USER, sendingText))
    val live = transcript.mapIndexed { index, message -> ChatMessage("live-$index", "", message.role, message.content,
        toolCalls = message.toolCalls, toolCallId = message.toolCallId, reasoning = message.reasoning, toolStage = message.toolStage) } +
        ChatMessage("live-answer", "", Role.ASSISTANT, draft, reasoning = reasoning)
    rows += ChatRow("reply-$userId", messages = live, activity = activity, generating = true)
    return rows
}

/** Some compatible models embed <think> text instead of a dedicated reasoning field. */
data class ThinkingText(val answer: String, val thinking: String)
fun splitThinking(text: String): ThinkingText {
    val first = text.indexOfFirst { !it.isWhitespace() }
    if (first < 0 || !text.startsWith("<think>", first, ignoreCase = true)) return ThinkingText(text, "")
    val close = text.indexOf("</think>", first + 7, ignoreCase = true)
    if (close < 0) return ThinkingText("", text.substring(first + 7).trim())
    return ThinkingText(text.substring(close + 8).trim(), text.substring(first + 7, close).trim())
}
