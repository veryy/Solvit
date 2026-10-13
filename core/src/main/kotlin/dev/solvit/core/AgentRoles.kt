package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AgentRoleContext(val role: AgentRole?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentRoleContext>
}

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

enum class ContextHookFailure { FAILED, TIMED_OUT, RESOURCE_LIMIT }
/** Safe metadata only; exception messages and plugin data never enter diagnostics. */
data class ContextHookProblem(val pluginId: String, val failure: ContextHookFailure)
data class ContextHookReport(val context: List<String>, val problems: List<ContextHookProblem>)

/** Hooks contribute text only; they cannot change tools, credentials or approval. */
class ContextHookRegistry {
    private val hooks = linkedMapOf<String, ContextHook>()
    @Synchronized fun register(pluginId: String, hook: ContextHook): () -> Unit {
        check(pluginId !in hooks) { "Duplicate context hook" }
        require(pluginId.isNotBlank() && pluginId.length <= 128)
        check(hooks.size < 64) { "Too many context hooks" }
        hooks[pluginId] = hook
        return { synchronized(this) { if (hooks[pluginId] === hook) hooks.remove(pluginId) } }
    }
    suspend fun prepare(input: ContextInput): List<String> = prepareReport(input).context

    /** Optional read-only data has a shared deadline; system rules and gates are elsewhere. */
    suspend fun prepareReport(input: ContextInput): ContextHookReport {
        val snapshot = synchronized(this) { hooks.toList() }.filter { input.role?.allows(it.first) != false }
        val outputs = arrayOfNulls<ContextHookReport>(snapshot.size)
        val workers = Semaphore(4)
        supervisorScope {
            withTimeoutOrNull(10_000) {
                snapshot.mapIndexed { index, (id, hook) -> async {
                    workers.withPermit {
                        if (!current(id, hook)) return@withPermit
                        val result = try {
                            val text = withTimeoutOrNull(10_000) { hook.beforeModel(input) }
                            when {
                                text == null -> problem(id, ContextHookFailure.TIMED_OUT)
                                text.toByteArray(Charsets.UTF_8).size > 32 * 1024 -> problem(id, ContextHookFailure.RESOURCE_LIMIT)
                                text.isBlank() -> ContextHookReport(emptyList(), emptyList())
                                else -> ContextHookReport(listOf("插件上下文（$id）：\n$text"), emptyList())
                            }
                        } catch (e: CancellationException) {
                            // Parent/user cancellation always propagates. A plugin throwing its
                            // own cancellation while this task is active is an optional failure.
                            currentCoroutineContext().ensureActive()
                            problem(id, ContextHookFailure.FAILED)
                        } catch (e: Exception) {
                            problem(id, ContextHookFailure.FAILED)
                        }
                        if (current(id, hook)) outputs[index] = result
                    }
                } }.awaitAll()
            }
        }
        currentCoroutineContext().ensureActive()
        val context = mutableListOf<String>()
        val problems = mutableListOf<ContextHookProblem>()
        var bytes = 0
        snapshot.forEachIndexed { index, (id, hook) ->
            if (!current(id, hook)) return@forEachIndexed
            val result = outputs[index] ?: problem(id, ContextHookFailure.TIMED_OUT)
            problems += result.problems
            for (value in result.context) {
                val size = value.toByteArray(Charsets.UTF_8).size
                if (size > 64 * 1024 - bytes) problems += ContextHookProblem(id, ContextHookFailure.RESOURCE_LIMIT)
                else { context += value; bytes += size }
            }
        }
        return ContextHookReport(context, problems)
    }
    private fun current(id: String, hook: ContextHook) = synchronized(this) { hooks[id] === hook }
    private fun problem(id: String, failure: ContextHookFailure) = ContextHookReport(emptyList(), listOf(ContextHookProblem(id, failure)))
}

val ContextHookServiceKey = ServiceKey("agent.context-hooks", ContextHookRegistry::class)
class ContextHooksPlugin : SolvitPlugin {
    override val id = "builtin.context-hooks"
    override val provides = setOf(ContextHookServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(ContextHookServiceKey, ContextHookRegistry()) }
}
