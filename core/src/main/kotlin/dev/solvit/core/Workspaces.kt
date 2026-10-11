package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class Workspace(val id: String, val name: String)
data class ShellSession(val id: String, val workspaceId: String, val name: String)
data class WorkspaceState(val workspaces: List<Workspace> = emptyList(),
    val bindings: Map<String, String> = emptyMap(), val shells: List<ShellSession> = emptyList())
/** Immutable for an Agent turn; scripts cannot choose another conversation's workspace. */
class ConversationContext(val sessionId: String, val workspaceId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationContext>
}
interface WorkspaceService {
    val state: StateFlow<WorkspaceState>
    val jobs: StateFlow<List<ShellJob>>
    suspend fun forChat(sessionId: String): Workspace
    suspend fun bind(sessionId: String, workspaceId: String)
    suspend fun unbind(sessionId: String)
    suspend fun create(name: String): Workspace
    suspend fun createShell(workspaceId: String, name: String): ShellSession
    suspend fun read(workspaceId: String, path: String): String
    suspend fun write(workspaceId: String, path: String, text: String)
    suspend fun run(workspaceId: String, shellId: String, command: String, timeoutMs: Long = 60_000): ShellJob
    suspend fun cancel(workspaceId: String, jobId: String)
}
val WorkspaceServiceKey = ServiceKey("workspace", WorkspaceService::class)
