package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class ToolPolicy { ASK, ALLOW, DENY }
fun toolPolicyKey(tool: ToolDescriptor): String {
    val identity = listOf(tool.pluginId, tool.pluginVersion, tool.name, tool.description, tool.inputSchema,
        tool.capabilities.sorted().joinToString(","), tool.androidPermissions.sorted().joinToString(","), tool.requiresConfirmation.toString(), tool.executionMode?.name.orEmpty(), tool.pluginDigest).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
fun scopedToolPolicyKey(tool: ToolDescriptor, role: AgentRole?): String = toolPolicyKey(tool) + ":" +
    MessageDigest.getInstance("SHA-256").digest((role?.let {
        listOf(it.id, it.prompt, it.pluginIds.sorted().joinToString(","), it.skillIds.sorted().joinToString(",")).joinToString("\u0000")
    } ?: "default").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

interface ToolPolicyService {
    val policies: StateFlow<Map<String, ToolPolicy>>
    suspend fun set(tool: ToolDescriptor, policy: ToolPolicy)
    suspend fun set(tool: ToolDescriptor, role: AgentRole?, policy: ToolPolicy) = set(tool, policy)
    fun policy(tool: ToolDescriptor, role: AgentRole?) = policy(tool)
    fun policy(tool: ToolDescriptor) = policies.value[toolPolicyKey(tool)] ?: ToolPolicy.ASK
}
val ToolPolicyServiceKey = ServiceKey("tools.policies", ToolPolicyService::class)

/** Created only after the registry's role and authorization checks. */
class ToolInvocationContext(val descriptor: ToolDescriptor, val call: ToolCall) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolInvocationContext>
    private val operations = mutableSetOf<String>()
    @Synchronized fun claim(operation: String) { check(operations.add(operation)) { "此授权已经执行过该操作" } }
    private val resources = AgentExecutionContext()
    fun own(id: String, cancel: suspend () -> Unit) = resources.own(id, cancel)
    suspend fun cancelResources() = resources.cancel()
    fun releaseResources() = resources.release()
}

enum class ExecutionMode { APP, ROOT }
enum class ShellStage { STARTING, RUNNING, COMPLETED, FAILED, CANCELLED, TIMED_OUT }
data class ShellRequest(val command: String, val cwd: String = "", val timeoutMs: Long = 60_000) {
    fun validate() {
        require(command.isNotBlank() && command.length <= 16_000 && '\u0000' !in command)
        require(cwd.length <= 1024 && '\u0000' !in cwd)
        require(timeoutMs in 1_000..1_800_000)
    }
}
data class ShellOutput(val sequence: Long, val stream: String, val text: String)
data class ShellJob(
    val id: String, val owner: String, val roleId: String?, val command: String, val cwd: String,
    val mode: ExecutionMode, val stage: ShellStage, val output: List<ShellOutput> = emptyList(),
    val nextSequence: Long = 0, val truncated: Boolean = false, val exitCode: Int? = null,
    val failure: String? = null,
    val workspaceId: String? = null, val shellSessionId: String? = null,
)
data class DeviceExecutionState(
    val appUid: Int, val shellAvailable: Boolean, val rootDetected: Boolean,
    val rootGranted: Boolean = false, val rootStatus: String = "尚未请求 Root",
)
interface SystemExecutionService {
    val device: StateFlow<DeviceExecutionState>
    val modes: StateFlow<Map<String, ExecutionMode>>
    val jobs: StateFlow<List<ShellJob>>
    suspend fun refresh()
    suspend fun requestRoot()
    suspend fun setMode(owner: String, mode: ExecutionMode)
    suspend fun start(owner: String, request: ShellRequest, userInitiated: Boolean = false): ShellJob
    suspend fun read(owner: String, id: String, after: Long = -1): ShellJob
    suspend fun cancel(owner: String, id: String, userInitiated: Boolean = false)
    suspend fun cancelOwner(owner: String)
}
const val TERMINAL_OWNER = "builtin.terminal"
val SystemExecutionServiceKey = ServiceKey("system.execution", SystemExecutionService::class)
