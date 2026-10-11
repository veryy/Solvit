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
)
interface SolvitTool {
    val descriptor: ToolDescriptor
    suspend fun execute(argumentsJson: String): String
}
interface ToolAuthorization {
    /** Recheck OS permissions and user approval at execution time; deny by default. */
    suspend fun authorize(descriptor: ToolDescriptor): Boolean
    suspend fun authorizeCall(descriptor: ToolDescriptor, call: ToolCall): Boolean = authorize(descriptor)
}
object DenyTools : ToolAuthorization {
    override suspend fun authorize(descriptor: ToolDescriptor) = false
}

class ToolDeniedException : IllegalStateException("Tool authorization denied")

class ToolRegistry(private val events: EventBus, private val policies: ToolPolicyService? = null) {
    private val tools = linkedMapOf<String, SolvitTool>()
    @Synchronized fun descriptors(): List<ToolDescriptor> = tools.values.map { it.descriptor }
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
    suspend fun executeCall(call: ToolCall, authorization: ToolAuthorization, role: AgentRole? = null, onAuthorized: suspend () -> Unit = {}): String {
        val name = call.name
        val argumentsJson = call.argumentsJson
        val tool = synchronized(this) { tools[name] } ?: error("Unknown tool: $name")
        if (role != null && !role.allows(tool.descriptor.pluginId)) throw ToolDeniedException()
        val policy = policies?.policy(tool.descriptor) ?: ToolPolicy.ASK
        if (policy == ToolPolicy.DENY || (policy == ToolPolicy.ASK && !authorization.authorizeCall(tool.descriptor, call))) throw ToolDeniedException()
        currentCoroutineContext().ensureActive()
        if (policies?.policy(tool.descriptor) == ToolPolicy.DENY) throw ToolDeniedException()
        check(synchronized(this) { tools[name] === tool }) { "Tool unloaded" }
        onAuthorized()
        return withTimeout(60_000) { withContext(AgentRoleContext(role) + ToolInvocationContext(tool.descriptor, call)) { tool.execute(argumentsJson) } }
    }
}

class ToolsPlugin(private val enforcePolicies: Boolean = false) : SolvitPlugin {
    override val id = "builtin.tools"
    override val provides = setOf(Services.Tools)
    override val requires = if (enforcePolicies) setOf(ToolPolicyServiceKey) else emptySet()
    override suspend fun enable(context: PluginContext) {
        context.register(Services.Tools, ToolRegistry(context.events, if (enforcePolicies) context.require(ToolPolicyServiceKey) else null))
    }
}
