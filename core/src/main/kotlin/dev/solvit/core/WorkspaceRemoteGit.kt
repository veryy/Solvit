package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

/** Network permission is additional to reading/writing the bound workspace. */
interface WorkspaceRemoteGitService : WorkspaceGitService {
    fun bindRemote(chatId: String, activeChat: () -> String?,
        authorizeRead: suspend () -> Unit, authorizeWrite: suspend () -> Unit,
        authorizeNetwork: suspend (GitRemoteEndpoint) -> Unit): WorkspaceRemoteGitSession
}

interface WorkspaceRemoteGitSession : WorkspaceGitSession {
    data class Progress(val operation: String, val task: String, val completed: Int, val total: Int)
    data class Fetch(val branch: String, val head: String, val updates: Map<String, String>)
    /** Host-retained review; plugin adapters expose an opaque handle, never these facts. */
    data class PushReview(val endpoint: GitRemoteEndpoint, val branch: String,
        val revision: WorkspaceGitSession.Revision, val remoteHead: String)
    data class Push(val branch: String, val head: String, val status: String)
    data class PullReview(val revision: WorkspaceGitSession.Revision, val remoteHead: String,
        val worktreeSha256: String)
    data class Merge(val status: String, val head: String?, val conflicts: List<String>)

    val progress: StateFlow<Progress?>
    suspend fun transferProgress(): Progress?
    /** Downloads objects and a tracking ref; does not change HEAD, index or workspace files. */
    suspend fun fetch(endpoint: GitRemoteEndpoint, branch: String): Fetch
    /** Stages privately before installing into an empty bound directory, without replacing it. */
    suspend fun clone(endpoint: GitRemoteEndpoint, branch: String): Fetch
    /** Fetches the actual remote head and captures the local revision for user review. */
    suspend fun reviewPush(endpoint: GitRemoteEndpoint, branch: String): PushReview
    /** Pushes only the reviewed commit, without force, using the reviewed remote head as a lease. */
    suspend fun push(review: PushReview): Push
    suspend fun reviewPull(endpoint: GitRemoteEndpoint, branch: String): PullReview
    /** Applies reviewed objects locally; a non-fast-forward merge waits for an explicit commit. */
    suspend fun merge(review: PullReview): Merge
    suspend fun cancelTransfer()
}
