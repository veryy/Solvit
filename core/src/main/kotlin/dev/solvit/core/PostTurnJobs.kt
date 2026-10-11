package dev.solvit.core

/** Captured only after opt-in; exact package identity prevents running a later replacement. */
data class PostTurnTarget(val pluginId: String, val version: String, val digest: String) {
    fun validate() {
        require(pluginId.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")))
        require(version.isNotBlank() && version.length <= 80 && !version.any(Char::isISOControl))
        require(digest.matches(Regex("[a-f0-9]{64}")))
    }
}

enum class PostTurnJobStatus { WAITING, RUNNING, SUCCEEDED, FAILED, SKIPPED }
/** Persist only safe classifications, never arbitrary exception messages or model output. */
enum class PostTurnFailure { INTERRUPTED, MODEL, INVALID_OUTPUT, STORAGE, PERMISSION, PLUGIN_CHANGED, CONFIGURATION }
data class PostTurnJob(
    val id: String,
    val turn: CompletedChatTurn,
    val target: PostTurnTarget,
    val status: PostTurnJobStatus,
    val attempt: Int,
    val updatedAt: Long,
    val failure: PostTurnFailure?,
)
