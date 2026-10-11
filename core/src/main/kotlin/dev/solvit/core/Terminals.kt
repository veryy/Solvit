package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

/** A live PTY process, separate from persisted workspace Shell names and finite jobs. */
data class TerminalSession(val id: String, val owner: String, val roleId: String?,
    val workspaceId: String, val shellId: String, val mode: ExecutionMode,
    val active: Boolean = true, val output: String = "", val revision: Long = 0,
    val truncated: Boolean = false, val exitCode: Int? = null, val failure: String? = null)
interface TerminalService {
    val sessions: StateFlow<List<TerminalSession>>
    suspend fun open(workspaceId: String, shellId: String): TerminalSession
    suspend fun status(workspaceId: String, id: String): TerminalSession
    /** Raw terminal input, including newlines; text is never evaluated by the host itself. */
    suspend fun input(workspaceId: String, id: String, text: String): TerminalSession
    suspend fun interrupt(workspaceId: String, id: String): TerminalSession
    suspend fun close(workspaceId: String, id: String)
    suspend fun cancelOwner(owner: String)
}
val TerminalServiceKey = ServiceKey("terminal.pty", TerminalService::class)
