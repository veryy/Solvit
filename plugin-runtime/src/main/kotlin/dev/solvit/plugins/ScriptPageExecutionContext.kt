package dev.solvit.plugins

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import dev.solvit.core.PostTurnTaskControl
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** Carries live page authority all the way to the actual native storage write lock. */
class ScriptPageExecutionContext(val pluginId: String, private val verify: suspend () -> Unit,
    private val approvedAction: JsonObject? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScriptPageExecutionContext>
    private val consumed = AtomicBoolean(false)
    suspend fun ensureAuthorized() = verify()
    suspend fun consumeTaskControl(): PostTurnTaskControl {
        ensureAuthorized()
        val approved = checkNotNull(approvedAction) { "No confirmed native task control" }
        check(consumed.compareAndSet(false, true)) { "Native task control already consumed" }
        return ScriptPageHostAction.parse(approved)
    }
}
