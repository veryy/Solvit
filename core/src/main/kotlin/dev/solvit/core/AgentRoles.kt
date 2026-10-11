package dev.solvit.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout

/** A null role keeps legacy behavior; an explicit empty binding allows no plugins. */
data class AgentRole(
    val id: String,
    val name: String,
    val prompt: String = "",
    val pluginIds: Set<String> = emptySet(),
    val skillIds: Set<String> = emptySet(),
) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(name.isNotBlank() && name.length <= 80 && prompt.length <= 16_000)
        require(pluginIds.size <= 64 && pluginIds.all { it.matches(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")) })
        require(skillIds.size <= 64 && skillIds.all { it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) && it.length <= 64 })
    }
    fun allows(pluginId: String) = pluginId in pluginIds
}

interface RoleService {
    fun roles(): Flow<List<AgentRole>>
    suspend fun save(role: AgentRole)
    suspend fun delete(id: String)
    suspend fun selected(sessionId: String): AgentRole?
    suspend fun select(sessionId: String, roleId: String?)
}

val RoleServiceKey = ServiceKey("agent.roles", RoleService::class)

data class ContextInput(val role: AgentRole?, val userText: String)
fun interface ContextHook { suspend fun beforeModel(input: ContextInput): String }

/** Hooks contribute text only; they cannot change tools, credentials or approval. */
class ContextHookRegistry {
    private val hooks = linkedMapOf<String, ContextHook>()
    @Synchronized fun register(pluginId: String, hook: ContextHook): () -> Unit {
        check(pluginId !in hooks) { "Duplicate context hook" }
        hooks[pluginId] = hook
        return { synchronized(this) { if (hooks[pluginId] === hook) hooks.remove(pluginId) } }
    }
    suspend fun prepare(input: ContextInput): List<String> {
        val snapshot = synchronized(this) { hooks.toList() }
        return buildList {
            for ((id, hook) in snapshot) {
                if (input.role != null && !input.role.allows(id)) continue
                if (synchronized(this@ContextHookRegistry) { hooks[id] !== hook }) continue
                val text = withTimeout(10_000) { hook.beforeModel(input) }
                check(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024) { "插件上下文过大" }
                if (text.isNotBlank()) add("插件上下文（$id）：\n$text")
            }
            check(sumOf { it.toByteArray(Charsets.UTF_8).size } <= 64 * 1024) { "插件上下文总量过大" }
        }
    }
}

val ContextHookServiceKey = ServiceKey("agent.context-hooks", ContextHookRegistry::class)
class ContextHooksPlugin : SolvitPlugin {
    override val id = "builtin.context-hooks"
    override val provides = setOf(ContextHookServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(ContextHookServiceKey, ContextHookRegistry()) }
}
