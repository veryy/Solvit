package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class ToolPolicy { ASK, ALLOW, DENY }
fun toolPolicyKey(tool: ToolDescriptor): String {
    val identity = listOf(tool.pluginId, tool.pluginVersion, tool.name, tool.description, tool.inputSchema,
        tool.capabilities.sorted().joinToString(","), tool.executionMode?.name.orEmpty()).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
interface ToolPolicyService {
    val policies: StateFlow<Map<String, ToolPolicy>>
    suspend fun set(tool: ToolDescriptor, policy: ToolPolicy)
    fun policy(tool: ToolDescriptor) = policies.value[toolPolicyKey(tool)] ?: ToolPolicy.ASK
}
val ToolPolicyServiceKey = ServiceKey("tools.policies", ToolPolicyService::class)

/** Created only after the registry's role and authorization checks. */
class ToolInvocationContext(val descriptor: ToolDescriptor, val call: ToolCall) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolInvocationContext>
    private val operations = mutableSetOf<String>()
    @Synchronized fun claim(operation: String) { check(operations.add(operation)) { "此授权已经执行过该操作" } }
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
