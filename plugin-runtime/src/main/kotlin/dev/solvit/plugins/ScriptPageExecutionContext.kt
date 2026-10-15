package dev.solvit.plugins

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import dev.solvit.core.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

/** Carries live page authority all the way to the actual native storage write lock. */
class ScriptPageExecutionContext(val pluginId: String, private val verify: suspend () -> Unit,
    private val approvedAction: JsonObject? = null, private val documents: PageDocumentAccess? = null,
    private val workspace: PageWorkspaceAccess? = null, private val git: PageGitAccess? = null,
    private val gitCredentialPrompt: PageGitCredentialPrompt? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ScriptPageExecutionContext>
    private val consumed = AtomicBoolean(false)
    val documentInvocation: Boolean get() = approvedAction?.get("kind")?.toString()?.trim('"')?.startsWith("document.") == true
    val credentialInvocation: Boolean get() = approvedAction?.get("kind")?.toString()?.trim('"') == "git.credentials"
    suspend fun ensureAuthorized() = verify()
    suspend fun editGitCredentials(apiVersion: Int, networkDeclared: Boolean): GitCredentialOutcome {
        ensureAuthorized()
        check(apiVersion >= 20 && networkDeclared) { "Git凭据管理需要API20及network权限" }
        val endpoint = ScriptPageHostAction.gitCredentials(checkNotNull(approvedAction))
        val access = checkNotNull(git) { "此页面没有Git授权" }
        val prompt = checkNotNull(gitCredentialPrompt) { "此页面没有宿主凭据表单" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        return access.editCredentials(endpoint, prompt, ::ensureAuthorized).also { ensureAuthorized() }
    }
    private fun verifyRemoteGit(operation: String, apiVersion: Int, networkDeclared: Boolean) {
        if (operation in setOf("clone", "fetch", "reviewPush", "push", "reviewPull", "merge", "cancelTransfer", "transferProgress", "startRemote", "remoteOperation"))
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
    suspend fun queryWorkspace(operation: String, args: JsonObject, apiVersion: Int = 12, filesDeclared: Boolean = false): String {
        ensureAuthorized()
        if(operation in setOf("importBackups","reviewBackup","importBackupCleanups")) check(apiVersion>=28 && filesDeclared) { "备份审阅需要API28及workspace.files声明" }
        if(operation=="importRecoveries") check(apiVersion>=27 && filesDeclared) { "导入恢复报告需要API27及workspace.files服务声明" }
        if(operation=="importOperation") check(apiVersion>=26 && filesDeclared) { "导入任务进度需要API26及workspace.files服务声明" }
        if (operation == "reviewImport") check(apiVersion >= 25 && filesDeclared) {
            "文件替换审阅需要 API25 及 workspace.files 服务声明"
        }
        if (operation == "chatAttachments") check(apiVersion >= 24 && filesDeclared) {
            "聊天附件来源需要 API24 及 workspace.files 服务声明"
        }
        if (operation == "importPdf") check(apiVersion >= 23 && filesDeclared) {
            "PDF文件预览需要 API23 及 workspace.files 服务声明"
        }
        if (operation in setOf("importSelection", "importText", "importPicture")) requireImportAccess(apiVersion, filesDeclared)
        return checkNotNull(workspace) { "此页面没有聊天工作区授权" }.query(operation, args.toString(), ::ensureAuthorized)
            .also { ensureAuthorized() }
    }
    private fun requireImportAccess(apiVersion: Int, filesDeclared: Boolean) {
        check(apiVersion >= 22 && filesDeclared) { "文件导入需要 API22 及 workspace.files 服务声明" }
    }
    suspend fun performWorkspace(apiVersion: Int, linuxDeclared: Boolean = false, filesDeclared: Boolean = false): String {
        ensureAuthorized()
        val control = ScriptPageHostAction.workspace(checkNotNull(approvedAction))
        if(control.operation in setOf("selectImportBackup","cleanupImportBackup","restoreImportBackupCleanup","dismissImportBackupCleanup")) check(apiVersion>=28 && filesDeclared) { "备份副本选择需要API28及workspace.files声明" }
        if(control.operation=="dismissImportRecovery") check(apiVersion>=27 && filesDeclared) { "关闭恢复提示需要API27及workspace.files服务声明" }
        if(control.operation in setOf("startImport","cancelImport")) check(apiVersion>=26 && filesDeclared) { "导入任务需要API26及workspace.files服务声明" }
        if (control.operation == "importReviewed") check(apiVersion >= 25 && filesDeclared) {
            "审阅后导入需要 API25 及 workspace.files 服务声明"
        }
        if (control.operation == "selectChatAttachment") check(apiVersion >= 24 && filesDeclared) {
            "聊天附件选择需要 API24 及 workspace.files 服务声明"
        }
        check(control.operation !in setOf("installEnvironment", "cancelEnvironment", "installSoftware", "createLinuxShell") ||
            apiVersion >= 21 && linuxDeclared) { "Linux 环境操作需要 API21 及 environment.linux 服务声明" }
        if (control.operation in setOf("selectImport", "importFile", "dropImport")) requireImportAccess(apiVersion, filesDeclared)
        check(control.operation !in setOf("writeDraft","makeDirectory","moveEntry","deleteEntry") || apiVersion >= 18) { "文件管理需要 API18" }
        check(consumed.compareAndSet(false, true)) { "Native page control already consumed" }
        if (control.operation == "selectImport") {
            val access = checkNotNull(workspace) { "此页面没有聊天工作区授权" }
            // Reject an unbound/invalid page before opening the system picker.
            access.query("list", "{\"path\":\"\",\"offset\":0}", ::ensureAuthorized)
            ensureAuthorized()
            val selected = checkNotNull(documents) { "此页面没有文件导入选择器" }.selectFile(::ensureAuthorized)
                ?: return "null".also { ensureAuthorized() }
            var adopted = false
            try {
                ensureAuthorized()
                val metadata = access.stageImport(selected, ::ensureAuthorized)
                adopted = true
                ensureAuthorized()
                return metadata
            } finally {
                if (!adopted) selected.close()
            }
        }
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
