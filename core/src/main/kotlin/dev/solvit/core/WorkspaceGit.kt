package dev.solvit.core

/** Native Git capability shared by plugin pages and tool invocation adapters.
 * Callers provide host authority checks, never a filesystem root or shell command.
 */
interface WorkspaceGitService {
    fun bind(
        chatId: String,
        activeChat: () -> String?,
        authorizeRead: suspend () -> Unit,
        authorizeWrite: suspend () -> Unit,
    ): WorkspaceGitSession
    suspend fun close()
}

/** Captures one existing chat/workspace binding. Every operation rechecks authority.
 * Review tokens below are native values; JS adapters must retain them in the host
 * and expose handles rather than accepting a caller-supplied revision or patch.
 */
interface WorkspaceGitSession {
    data class Entry(val path: String, val staged: String?, val working: String?, val conflict: Boolean)
    data class Status(val branch: String?, val head: String?, val entries: List<Entry>, val total: Int)
    data class Revision(val head: String?, val indexSha256: String?, val branch: String?)
    data class Diff(val path: String, val staged: Boolean, val patch: String, val revision: Revision,
        val workingSha256: String?, val workingExecutable: Boolean?)
    data class Commit(val id: String, val message: String, val author: String, val timeSeconds: Int)

    suspend fun initialize(): Revision
    suspend fun status(offset: Int = 0, limit: Int = 100): Status
    suspend fun diff(path: String, staged: Boolean): Diff
    suspend fun history(limit: Int = 30): List<Commit>
    /** Capture the exact staged tree version before showing a commit confirmation. */
    suspend fun reviewIndex(): Revision
    suspend fun stage(review: Diff): Revision
    suspend fun unstage(path: String, expected: Revision): Revision
    suspend fun commit(expected: Revision, message: String, author: String, email: String): Commit
    suspend fun close()
}

val WorkspaceGitServiceKey = ServiceKey("workspace.git", WorkspaceGitService::class)

/** Attached only to a host-owned page; perform is called after native confirmation. */
interface PageGitAccess : PageWorkspaceAccess
