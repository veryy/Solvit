package dev.solvit.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host-only observation of bytes copied into staging, not picker/network download progress.
 * PUBLISHING is the last cancellable checkpoint. COMMITTED is a fact, never an abort request.
 */
data class WorkspaceImportProgress(val stage: String, val completed: Long, val total: Long)
class WorkspaceImportMonitor(val update: suspend (WorkspaceImportProgress) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceImportMonitor>
}
