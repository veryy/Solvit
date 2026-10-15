package dev.solvit.core

/** Host-only identity. No process handle, host path or chat selector is exposed to JS. */
data class StdioOwner(val pluginId: String, val scope: String, val server: String, val configuration: String)

/** Raw newline frames only; MCP negotiation and tool discovery belong to the JS plugin. */
interface StdioProcessService {
    suspend fun open(owner: StdioOwner, plan: HostProcessPlan, directory: String, authorize: suspend () -> Unit): String
    suspend fun send(owner: StdioOwner, id: String, frame: String, authorize: suspend () -> Unit)
    suspend fun receive(owner: StdioOwner, id: String, authorize: suspend () -> Unit): String
    suspend fun close(owner: StdioOwner, id: String)
    suspend fun cancelOwner(pluginId: String)
}
val StdioProcessServiceKey = ServiceKey("process.stdio", StdioProcessService::class)
