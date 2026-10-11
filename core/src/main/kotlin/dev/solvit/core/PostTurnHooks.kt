package dev.solvit.core

import kotlinx.coroutines.withTimeout
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class PostTurnInput(val turn: CompletedChatTurn, val userText: String, val assistantText: String)
/** Host-owned bounded inference. No provider configuration or credentials cross into a plugin. */
fun interface PostTurnModel { suspend fun complete(instructions: String, input: String): String }
class PostTurnWorkException(val failure: PostTurnFailure) : IllegalStateException("Post-turn work failed: ${failure.name}")
class PostTurnRevokedException : IllegalStateException("Post-turn authorization no longer applies")
/** Native bindings must recheck this before inference or storage writes. */
class PostTurnExecutionContext(private val verify: suspend () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PostTurnExecutionContext>
    suspend fun ensureAuthorized() = verify()
}
interface PostTurnHook {
    val target: PostTurnTarget
    /** Reads the current explicit opt-in, both before admission and before execution. */
    suspend fun enabled(): Boolean
    suspend fun process(input: PostTurnInput, model: PostTurnModel)
}

class PostTurnHookRegistry {
    private val hooks = linkedMapOf<String, PostTurnHook>()
    @Synchronized fun register(hook: PostTurnHook): () -> Unit {
        hook.target.validate()
        check(hook.target.pluginId !in hooks) { "Duplicate post-turn hook" }
        hooks[hook.target.pluginId] = hook
        return { synchronized(this) { if (hooks[hook.target.pluginId] === hook) hooks.remove(hook.target.pluginId) } }
    }
    suspend fun admit(role: AgentRole?): List<PostTurnTarget> = withTimeout(2_000) {
        val snapshot = synchronized(this@PostTurnHookRegistry) { hooks.values.toList() }
        buildList {
            for (hook in snapshot) {
                if (role != null && !role.allows(hook.target.pluginId)) continue
                if (!hook.enabled()) continue
                if (synchronized(this@PostTurnHookRegistry) { hooks[hook.target.pluginId] === hook }) add(hook.target)
            }
            check(size <= 32) { "Too many post-turn hooks" }
        }
    }
    @Synchronized fun resolve(target: PostTurnTarget): PostTurnHook? = hooks[target.pluginId]?.takeIf { it.target == target }
}

val PostTurnHookServiceKey = ServiceKey("agent.post-turn-hooks", PostTurnHookRegistry::class)
class PostTurnHooksPlugin : SolvitPlugin {
    override val id = "builtin.post-turn-hooks"
    override val provides = setOf(PostTurnHookServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(PostTurnHookServiceKey, PostTurnHookRegistry()) }
}
