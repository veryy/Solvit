package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class Workspace(val id: String, val name: String)
data class WorkspaceFile(val name: String, val path: String, val directory: Boolean, val bytes: Long)
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
    suspend fun location(workspaceId: String): String { error("工作区目录服务不可用") }
    suspend fun list(workspaceId: String, path: String = ""): List<WorkspaceFile> { error("工作区目录服务不可用") }
    suspend fun read(workspaceId: String, path: String): String
    suspend fun write(workspaceId: String, path: String, text: String)
    suspend fun run(workspaceId: String, shellId: String, command: String, timeoutMs: Long = 60_000): ShellJob
    suspend fun cancel(workspaceId: String, jobId: String)
    suspend fun cancelOwner(owner: String)
}
val WorkspaceServiceKey = ServiceKey("workspace", WorkspaceService::class)

data class PluginDraft(val id: String, val workspaceId: String, val pluginId: String, val name: String,
    val version: String, val permissions: Set<String>, val path: String, val sha256: String)
interface PluginDraftService {
    val drafts: StateFlow<List<PluginDraft>>
    suspend fun prepare(workspaceId: String, manifestJson: String, source: String): PluginDraft
    suspend fun payload(id: String): String
    /** Installs exactly the reviewed digest and always leaves the plugin disabled. */
    suspend fun install(id: String, expectedSha256: String)
}
val PluginDraftServiceKey = ServiceKey("plugins.drafts", PluginDraftService::class)
