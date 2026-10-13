package dev.solvit.app

import androidx.compose.runtime.compositionLocalOf

/** Speed 0 preserves automatic catch-up; -1 reveals received text immediately. */
data class ChatAppearance(val fontSize: Float=16f,val replySpeed: Int=0,val style: String="bubble") {
    fun validate() {
        require(fontSize.isFinite() && fontSize in 12f..24f)
        require(replySpeed == -1 || replySpeed == 0 || replySpeed in 10..400)
        require(style in setOf("bubble","compact","plain"))
    }
}
val LocalChatAppearance=compositionLocalOf { ChatAppearance() }
