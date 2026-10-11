package dev.solvit.app

/** Times are elapsed milliseconds from send, not wall-clock timestamps. */
data class ReplyTiming(val sessionId: String, val startedAtMs: Long, val firstTextMs: Long? = null,
    val firstReasoningMs: Long? = null, val firstVisibleMs: Long? = null,
    val receivedEndMs: Long? = null, val displayEndMs: Long? = null,
    val receivedCharacters: Int = 0, val outcome: String = "running")
