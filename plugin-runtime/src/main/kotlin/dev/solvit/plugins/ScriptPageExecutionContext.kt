package dev.solvit.plugins

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import dev.solvit.core.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** Carries live page authority all the way to the actual native storage write lock. */
class ScriptPageExecutionContext(val pluginId: String, private val verify: suspend () -> Unit,
    private val approvedAction: JsonObject? = null, private val documents: PageDocumentAccess? = null,
    private val workspace: PageWorkspaceAccess? = null, private val git: PageGitAccess? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScriptPageExecutionContext>
    private val consumed = AtomicBoolean(false)
    val documentInvocation: Boolean get() = approvedAction?.get("kind")?.toString()?.trim('"')?.startsWith("document.") == true
    suspend fun ensureAuthorized() = verify()
    private fun verifyRemoteGit(operation: String, apiVersion: Int, networkDeclared: Boolean) {
        if (operation in setOf("fetch", "reviewPush", "push", "reviewPull", "merge", "cancelTransfer", "transferProgress", "startRemote", "remoteOperation"))
            check(apiVersion >= 20 && networkDeclared) { "远程Git页面需要API20及network权限" }
    }
    suspend fun queryGit(operation: String, args: JsonObject, apiVersion: Int = 19, networkDeclared: Boolean = false): String {
        ensureAuthorized()
        verifyRemoteGit(operation, apiVersion, networkDeclared)
        return checkNotNull(git) { "此页面没有Git授权" }.query(operation, args.toString(), ::ensureAuthorized)
            .also { ensureAuthorized() }
    }
    suspend fun performGit(apiVersion: Int = 19, networkDeclared: Boolean = false): String {
        ensureAuthorized()
        val control = ScriptPageHostAction.git(checkNotNull(approvedAction))
        verifyRemoteGit(control.operation, apiVersion, networkDeclared)
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return checkNotNull(git) { "此页面没有Git授权" }.perform(control, ::ensureAuthorized)
            .also { ensureAuthorized() }
    }
    suspend fun queryWorkspace(operation: String, args: JsonObject): String {
        ensureAuthorized()
        return checkNotNull(workspace) { "此页面没有聊天工作区授权" }.query(operation, args.toString(), ::ensureAuthorized)
            .also { ensureAuthorized() }
    }
    suspend fun performWorkspace(apiVersion: Int): String {
        ensureAuthorized()
        val control = ScriptPageHostAction.workspace(checkNotNull(approvedAction))
        check(control.operation !in setOf("writeDraft","makeDirectory","moveEntry","deleteEntry") || apiVersion >= 18) { "文件管理需要 API18" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return checkNotNull(workspace) { "此页面没有聊天工作区授权" }.perform(control, ::ensureAuthorized)
            .also { ensureAuthorized() }
    }
    suspend fun consumeTaskControl(): PostTurnTaskControl {
        ensureAuthorized()
        val approved = checkNotNull(approvedAction) { "No confirmed native task control" }
        check(consumed.compareAndSet(false, true)) { "Native task control already consumed" }
        return ScriptPageHostAction.parse(approved)
    }
    suspend fun openDocument(): PageTextDocument? {
        ensureAuthorized()
        val control = ScriptPageHostAction.document(checkNotNull(approvedAction)) as? PageDocumentControl.Open
            ?: error("Displayed control does not select a document")
        val access = checkNotNull(documents) { "No page document picker" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return access.open(control, ::ensureAuthorized).also { ensureAuthorized() }
    }
    suspend fun saveDocument(text: String): Boolean {
        ensureAuthorized()
        val control = ScriptPageHostAction.document(checkNotNull(approvedAction)) as? PageDocumentControl.Save
            ?: error("Displayed control does not save a document")
        require(text.toByteArray(Charsets.UTF_8).size <= control.maxBytes)
        val access = checkNotNull(documents) { "No page document picker" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return access.save(control, text, ::ensureAuthorized).also { ensureAuthorized() }
    }
}
