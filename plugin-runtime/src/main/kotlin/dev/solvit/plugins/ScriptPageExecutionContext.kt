package dev.solvit.plugins

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries live page authority all the way to the actual native storage write lock. */
class ScriptPageExecutionContext(val pluginId: String, private val verify: suspend () -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScriptPageExecutionContext>
    suspend fun ensureAuthorized() = verify()
}
