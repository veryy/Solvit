package dev.solvit.core

/** Visible, bounded text only. Never a provider transcript or a model connection configuration. */
data class AgentText(val text: String, val truncated: Boolean) {
    companion object {
        fun of(value: String, limit: Int): AgentText {
            require(limit > 0)
            var end = minOf(value.length, limit)
            if (end < value.length && end > 0 && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            return AgentText(value.substring(0, end), end < value.length)
        }
    }
}
data class AgentObservedTool(
    val index: Int, val id: AgentText, val name: String, val arguments: AgentText,
    val stage: ToolStage? = null, val result: AgentText? = null,
)
data class AgentObservation(
    val step: Int, val nextModelRound: Int, val phase: String,
    val userText: AgentText, val roleName: AgentText?,
    val assistantText: AgentText, val reasoning: AgentText, val tools: List<AgentObservedTool>,
    val toolsTruncated: Boolean = false,
)
