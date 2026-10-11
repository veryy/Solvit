package dev.solvit.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Commands may outlive a successful turn; stopping/failing that turn cancels them. */
class AgentExecutionContext : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentExecutionContext>
    private val resources = linkedMapOf<String, suspend () -> Unit>()
    private var closed = false
    @Synchronized fun own(id: String, cancel: suspend () -> Unit) {
        check(!closed) { "Agent 执行已经结束" }
        check(id !in resources); resources[id] = cancel
    }
    @Synchronized fun release() { closed = true; resources.clear() }
    suspend fun cancel() = withContext(NonCancellable) {
        val actions = synchronized(this@AgentExecutionContext) {
            closed = true; resources.values.toList().asReversed().also { resources.clear() }
        }
        // One missing/completed job must not prevent stopping all other jobs.
        actions.forEach { action -> runCatching { action() } }
    }
}
