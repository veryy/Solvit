package dev.solvit.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host-only facts emitted after a durable intent or actual publication/cleanup.
 * No file path, replay command or script callback. Observers should return promptly. */
class WorkspaceCloneMonitor(val update: (stage: String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceCloneMonitor>
}
