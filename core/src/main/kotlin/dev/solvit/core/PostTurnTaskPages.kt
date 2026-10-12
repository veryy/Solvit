package dev.solvit.core

/** Human page controls, separate from model tools and background inference authority. */
sealed interface PostTurnTaskControl {
    data class Retry(val id: String, val attempt: Int) : PostTurnTaskControl
    data class Delete(val id: String, val attempt: Int, val status: PostTurnJobStatus) : PostTurnTaskControl
    data class Stop(val id: String, val attempt: Int) : PostTurnTaskControl
    data object Cleanup : PostTurnTaskControl
}

data class PostTurnTaskPage(val jobs: List<PostTurnJob>, val activeJobId: String?)
interface PostTurnTaskService {
    suspend fun list(pluginId: String, before: PostTurnJobCursor?, limit: Int,
        authorize: suspend () -> Unit): PostTurnTaskPage
    /** Authorize again inside the actual store write lock or before cancelling the exact Job. */
    suspend fun control(pluginId: String, action: PostTurnTaskControl,
        authorize: suspend () -> Unit): Int
    suspend fun retired(pluginId: String, turnId: String, authorize: suspend () -> Unit): Boolean
}
val PostTurnTaskServiceKey = ServiceKey("agent.post-turn-tasks", PostTurnTaskService::class)
