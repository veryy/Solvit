package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

/** A live PTY process, separate from persisted workspace Shell names and finite jobs. */
data class TerminalSession(val id: String, val owner: String, val roleId: String?,
    val workspaceId: String, val shellId: String, val mode: ExecutionMode,
    val active: Boolean = true, val output: String = "", val revision: Long = 0,
    val truncated: Boolean = false, val exitCode: Int? = null, val failure: String? = null,
    val chatId: String? = null, val human: Boolean = false,
    /** UTF-16 position of the first retained output character; monotonic across truncation. */
    val outputOffset: Long = 0, val rows: Int = 24, val columns: Int = 80)
interface TerminalService {
    val sessions: StateFlow<List<TerminalSession>>
    suspend fun open(workspaceId: String, shellId: String): TerminalSession
    suspend fun status(workspaceId: String, id: String): TerminalSession
    /** Raw terminal input, including newlines; text is never evaluated by the host itself. */
    suspend fun input(workspaceId: String, id: String, text: String): TerminalSession
    suspend fun resize(workspaceId: String, id: String, rows: Int, columns: Int): TerminalSession =
        error("This terminal backend does not support resizing")
    suspend fun interrupt(workspaceId: String, id: String): TerminalSession
    suspend fun close(workspaceId: String, id: String)
    suspend fun cancelOwner(owner: String)
}
val TerminalServiceKey = ServiceKey("terminal.pty", TerminalService::class)

/** Host-created human page authority. Scripts cannot supply or change conversation identity. */
class TerminalPageAuthority(val owner: String, val chatId: String,
    val controlledIds: Set<String> = emptySet(), val verify: suspend (workspaceId: String) -> Unit) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<TerminalPageAuthority>
}
