package dev.solvit.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Human page capability attached by the host, never derived from a script-supplied chat id. */
interface PageWorkspaceAccess {
    suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String
    suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String
}

data class PageWorkspaceControl(val operation: String, val argumentsJson: String)
data class WorkspaceTextFile(val text: String, val sha256: String)

/** Rechecked inside the native workspace lock, after dispatcher changes and queued work. */
class WorkspacePageAuthority(val verify: suspend (workspaceId: String?, sessionId: String?) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspacePageAuthority>
}
