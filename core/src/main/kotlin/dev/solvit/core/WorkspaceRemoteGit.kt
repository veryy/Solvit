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
    /** Safe inventory only. No private directory or caller-selected workspace path. */
    data class CloneInstall(val id: String, val phase: String, val status: String,
        val url: String, val branch: String, val head: String?, val createdAt: Long)
    data class CloneInstalls(val entries: List<CloneInstall>, val nextOffset: Int?,
        val incomplete: Boolean, val unreadable: Int)
    /** Host-retained review. Script adapters must expose only a single-use opaque handle. */
    data class CloneInstallReview(val entry: CloneInstall, val fingerprint: String,
        val canResume: Boolean, val canRestart: Boolean, val files: Int, val bytes: Long,
        val installedFiles: Int, val problem: String?)

    val progress: StateFlow<Progress?>
    suspend fun transferProgress(): Progress?
    /** Downloads objects and a tracking ref; does not change HEAD, index or workspace files. */
    suspend fun fetch(endpoint: GitRemoteEndpoint, branch: String): Fetch
    /** Stages privately before installing into an empty bound directory, without replacing it. */
    suspend fun clone(endpoint: GitRemoteEndpoint, branch: String): Fetch
    suspend fun cloneInstalls(offset: Int = 0, limit: Int = 20): CloneInstalls { error("克隆恢复不可用") }
    suspend fun reviewCloneInstall(id: String): CloneInstallReview { error("克隆恢复不可用") }
    /** Offline continuation; never downloads again or overwrites concurrent workspace files. */
    suspend fun resumeCloneInstall(review: CloneInstallReview): Fetch { error("克隆恢复不可用") }
    /** New network approval is required. Only an unfinished download can be restarted. */
    suspend fun restartCloneDownload(review: CloneInstallReview): Fetch { error("克隆恢复不可用") }
    /** Deletes only registered private staging; all installed workspace files remain. */
    suspend fun discardCloneInstall(review: CloneInstallReview) { error("克隆恢复不可用") }
    /** Fetches the actual remote head and captures the local revision for user review. */
    suspend fun reviewPush(endpoint: GitRemoteEndpoint, branch: String): PushReview
    /** Pushes only the reviewed commit, without force, using the reviewed remote head as a lease. */
    suspend fun push(review: PushReview): Push
    suspend fun reviewPull(endpoint: GitRemoteEndpoint, branch: String): PullReview
    /** Applies reviewed objects locally; a non-fast-forward merge waits for an explicit commit. */
    suspend fun merge(review: PullReview): Merge
    suspend fun cancelTransfer()
    /** Host-only credential operations; never mapped to script/tool arguments. */
    suspend fun credentialsConfigured(endpoint: GitRemoteEndpoint): Boolean { error("Git凭据管理不可用") }
    suspend fun saveCredentials(endpoint: GitRemoteEndpoint, username: String, secret: CharArray) { error("Git凭据管理不可用") }
    suspend fun removeCredentials(endpoint: GitRemoteEndpoint) { error("Git凭据管理不可用") }
}
