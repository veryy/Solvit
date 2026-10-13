package dev.solvit.core

/** File primitives only; selection, deduplication and model-facing rendering belong to JS/TS. */
enum class WorkspaceInstructionStatus { TEXT, ABSENT, BINARY, OVERSIZED, UNAVAILABLE }
data class WorkspaceInstructionSource(val path: String, val status: WorkspaceInstructionStatus,
    val text: String? = null, val sha256: String? = null) {
    fun validate() {
        require(path in NAMES)
        require((status == WorkspaceInstructionStatus.TEXT) == (text != null))
        require(text == null || text.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES)
        require(sha256 == null || sha256.matches(Regex("[a-f0-9]{64}")))
        require(status == WorkspaceInstructionStatus.TEXT || sha256 == null)
    }
    companion object {
        val NAMES = listOf("AGENTS.md", "CLAUDE.md", "AGENTS.local.md", "CLAUDE.local.md")
        const val MAX_SOURCE_BYTES = 16 * 1024
    }
}
