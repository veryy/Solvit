package dev.solvit.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

data class ToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: String,
    val androidPermissions: Set<String> = emptySet(),
    val requiresConfirmation: Boolean = false,
    val pluginId: String = "builtin.tools",
    val chatVisible: Boolean = true,
    val pluginVersion: String = "",
    val capabilities: Set<String> = emptySet(),
    val executionMode: ExecutionMode? = null,
    val pluginDigest: String = "",
)
interface SolvitTool {
    val descriptor: ToolDescriptor
    suspend fun execute(argumentsJson: String): String
}
interface ToolAuthorization {
    /** Recheck OS permissions and user approval at execution time; deny by default. */
    suspend fun authorize(descriptor: ToolDescriptor): Boolean
    suspend fun authorizeCall(descriptor: ToolDescriptor, call: ToolCall): Boolean = authorize(descriptor)
    suspend fun authorizeCallForRole(descriptor: ToolDescriptor, call: ToolCall, role: AgentRole?): Boolean = authorizeCall(descriptor, call)
}
object DenyTools : ToolAuthorization {
    override suspend fun authorize(descriptor: ToolDescriptor) = false
}

class ToolDeniedException : IllegalStateException("Tool authorization denied")

class ToolRegistry(private val events: EventBus, private val policies: ToolPolicyService? = null,
    private val system: SystemExecutionService? = null) {
    private val tools = linkedMapOf<String, SolvitTool>()
    private fun snapshot(tool: SolvitTool): ToolDescriptor = if (system != null && "shell" in tool.descriptor.capabilities)
        tool.descriptor.copy(executionMode = system.modes.value[tool.descriptor.pluginId] ?: ExecutionMode.APP) else tool.descriptor
    @Synchronized fun descriptors(): List<ToolDescriptor> = tools.values.map(::snapshot)
    @Synchronized fun register(tool: SolvitTool): () -> Unit {
        val name = tool.descriptor.name
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        check(name !in tools) { "Duplicate tool: $name" }
        tools[name] = tool
        events.publish(CoreEvent.ToolsChanged(tools.keys.toList()))
        return { synchronized(this) {
            if (tools[name] === tool) tools.remove(name)
            events.publish(CoreEvent.ToolsChanged(tools.keys.toList()))
        } }
    }
    suspend fun execute(name: String, argumentsJson: String, authorization: ToolAuthorization): String {
        return executeCall(ToolCall("manual", name, argumentsJson), authorization)
    }
    suspend fun executeCall(call: ToolCall, authorization: ToolAuthorization, role: AgentRole? = null,
        execution: AgentExecutionContext? = null, conversation: ConversationContext? = null,
        onAuthorized: suspend () -> Unit = {}): String {
        val name = call.name
        val argumentsJson = call.argumentsJson
        val tool = synchronized(this) { tools[name] } ?: error("Unknown tool: $name")
        val descriptor = snapshot(tool)
        if (role != null && !role.allows(descriptor.pluginId)) throw ToolDeniedException()
        val policy = policies?.policy(descriptor, role) ?: ToolPolicy.ASK
        if (policy == ToolPolicy.DENY || (policy == ToolPolicy.ASK && !authorization.authorizeCallForRole(descriptor, call, role))) throw ToolDeniedException()
        currentCoroutineContext().ensureActive()
        if (policies?.policy(descriptor, role) == ToolPolicy.DENY) throw ToolDeniedException()
        check(snapshot(tool).executionMode == descriptor.executionMode) { "执行模式已变化，请重新确认" }
        check(synchronized(this) { tools[name] === tool }) { "Tool unloaded" }
        onAuthorized()
        // A durable dispatch checkpoint may suspend: authority can change while it is saved.
        currentCoroutineContext().ensureActive()
        if (policies?.policy(descriptor, role) == ToolPolicy.DENY) throw ToolDeniedException()
        check(snapshot(tool).executionMode == descriptor.executionMode) { "执行模式已变化，请重新确认" }
        check(synchronized(this) { tools[name] === tool }) { "Tool unloaded" }
        val invocation = ToolInvocationContext(descriptor, call)
        val context = AgentRoleContext(role) + invocation + (conversation ?: kotlin.coroutines.EmptyCoroutineContext)
        try {
            return withTimeout(60_000) { withContext(if (execution == null) context else context + execution) { tool.execute(argumentsJson) } }
        } catch (e: Throwable) { invocation.cancelResources(); throw e }
        finally { invocation.releaseResources() }
    }
}

class ToolsPlugin(private val enforcePolicies: Boolean = false, private val enforceSystem: Boolean = false) : SolvitPlugin {
    override val id = "builtin.tools"
    override val provides = setOf(Services.Tools)
    override val requires = buildSet<ServiceKey<*>> {
        if (enforcePolicies) add(ToolPolicyServiceKey)
        if (enforceSystem) add(SystemExecutionServiceKey)
    }
    override suspend fun enable(context: PluginContext) {
        context.register(Services.Tools, ToolRegistry(context.events, if (enforcePolicies) context.require(ToolPolicyServiceKey) else null,
            if (enforceSystem) context.require(SystemExecutionServiceKey) else null))
    }
}
