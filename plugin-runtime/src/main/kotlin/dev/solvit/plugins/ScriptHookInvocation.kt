package dev.solvit.plugins

import dev.solvit.core.ContextInput
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Trusted native invocation scope. JavaScript cannot construct or replace it. */
class ScriptHookInvocation(val input: ContextInput) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScriptHookInvocation>
}
