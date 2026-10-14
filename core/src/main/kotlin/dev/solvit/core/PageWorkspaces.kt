package dev.solvit.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Human page capability attached by the host, never derived from a script-supplied chat id. */
interface PageWorkspaceAccess {
    /** Takes ownership only of this page's user-selected file; scripts receive metadata, never bytes. */
    suspend fun stageImport(document: PageBinaryDocument, authorize: suspend () -> Unit): String {
        document.close();error("此工作区页面不支持文件导入")
    }
    suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String
    suspend fun perform(control: PageWorkspaceControl, authorize: suspend () -> Unit): String
    /** Drop page-owned staging when the page closes; it must not outlive its capability. */
    suspend fun close() { }
}

data class PageWorkspaceControl(val operation: String, val argumentsJson: String)
data class WorkspaceTextFile(val text: String, val sha256: String)

/** Rechecked inside the native workspace lock, after dispatcher changes and queued work. */
class WorkspacePageAuthority(val verify: suspend (workspaceId: String?, sessionId: String?) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspacePageAuthority>
}
