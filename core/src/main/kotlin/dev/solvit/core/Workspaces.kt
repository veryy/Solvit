package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class Workspace(val id: String, val name: String)
data class WorkspaceFile(val name: String, val path: String, val directory: Boolean, val bytes: Long)
/** A bounded UTF-16 slice of a strictly decoded UTF-8 file, tied to its content digest. */
data class WorkspaceTextChunk(val text: String, val offset: Int, val nextOffset: Int,
    val total: Int, val sha256: String)
enum class FileObservationKind { ABSENT, TEXT, BINARY, OVERSIZED, UNAVAILABLE }
data class FileObservation(val kind: FileObservationKind, val bytes: Long? = null,
    val sha256: String? = null, val excerpt: String? = null, val excerptTruncated: Boolean = false,
    /** Only present on fresh captures; durable journals store it separately from the event payload. */
    val captureText: String? = null, val captureSha256: String? = null) {
    fun validateCapture() {
        if (captureSha256 != null) {
            require(kind == FileObservationKind.TEXT && captureSha256 == sha256 && captureSha256.matches(Regex("[a-f0-9]{64}")))
            require(bytes != null && bytes in 0..MAX_FILE_CAPTURE_BYTES.toLong())
        }
        captureText?.let { text ->
            val encoded = text.toByteArray(Charsets.UTF_8)
            require(captureSha256 != null && '\u0000' !in text && encoded.size <= MAX_FILE_CAPTURE_BYTES && bytes == encoded.size.toLong())
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
            require(hash == captureSha256) { "File capture digest differs from its content" }
        }
    }
}
const val MAX_FILE_CAPTURE_BYTES = 256 * 1024
const val MAX_WORKSPACE_CAPTURE_BYTES = 2 * 1024 * 1024
/** Per-write observations, not a whole-project diff or a promise that Shell did not change other files. */
data class WorkspaceWriteEvidence(val workspaceId: String, val path: String,
    val before: FileObservation, val after: FileObservation, val changed: Boolean?, val verified: Boolean)
/** Exact imported bytes and optional retained original; never a host path or file body. */
data class WorkspaceImportedFile(val workspaceId: String, val path: String, val bytes: Long, val sha256: String,
    val backupPath: String? = null)
/** A complete content observation, separate from the selected source file's digest. */
data class WorkspaceImportTarget(val bytes: Long, val sha256: String)
/** Recovery reports contain workspace-relative paths only; dismissing never removes a file. */
data class WorkspaceImportRecovery(val id: String, val path: String, val backupPath: String?, val status: String)
/** PRESENT means the retained inode still matches, not that its bytes are unchanged. */
enum class WorkspaceBackupStatus { PRESENT, MISSING, CHANGED_IDENTITY, UNAVAILABLE }
data class WorkspaceImportBackup(val id: String, val path: String, val backupPath: String,
    val originalBytes: Long, val originalSha256: String, val createdAt: Long,
    val status: WorkspaceBackupStatus, val currentBytes: Long?, val pending: Boolean)
/** logicalBytes counts observed unique managed inodes, not physical blocks or reclaimable space.
 * Incomplete scans and unreadable records must be visible in the page; no deletion is implied. */
data class WorkspaceImportBackupPage(val backups: List<WorkspaceImportBackup>, val offset: Int,
    val total: Int, val logicalBytes: Long, val availableBytes: Long?,
    val incomplete: Boolean, val unreadableRecords: Int)
/** Trusted host-owned snapshot for review/copy; no absolute path or file identity leaves
 * the provider. Consumers erase bytes after creating their own bounded document. */
class WorkspaceImportBackupContent(val backup: WorkspaceImportBackup, val bytes: ByteArray,
    val sha256: String) : AutoCloseable {
    override fun close() { bytes.fill(0) }
    override fun toString() = "WorkspaceImportBackupContent(bytes=${bytes.size}, content=<redacted>)"
}
/** Interrupted cleanup facts. Paths stay workspace-relative; held bytes stay private. */
data class WorkspaceBackupCleanupRecord(val id:String,val path:String,val backupPath:String,
    val phase:String,val status:String,val heldBytes:Long?)
data class WorkspaceBackupCleanupPage(val cleanups:List<WorkspaceBackupCleanupRecord>,val offset:Int,
    val total:Int,val incomplete:Boolean,val unreadableEntries:Int)
const val MAX_WORKSPACE_IMPORT_BYTES = 20 * 1024 * 1024

data class ShellSession(val id: String, val workspaceId: String, val name: String,
    val runtime: ShellRuntime = ShellRuntime.ANDROID)
data class WorkspaceState(val workspaces: List<Workspace> = emptyList(),
    val bindings: Map<String, String> = emptyMap(), val shells: List<ShellSession> = emptyList())
/** Immutable for an Agent turn; scripts cannot choose another conversation's workspace. */
class ConversationContext(val sessionId: String, val workspaceId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationContext>
}
interface WorkspaceService {
    val state: StateFlow<WorkspaceState>
    val jobs: StateFlow<List<ShellJob>>
    suspend fun forChat(sessionId: String): Workspace
    /** Lookup only: a normal chat must not silently create a workspace. */
    suspend fun bound(sessionId: String): Workspace? = state.value.bindings[sessionId]?.let { id -> state.value.workspaces.firstOrNull { it.id == id } }
    suspend fun bind(sessionId: String, workspaceId: String)
    suspend fun unbind(sessionId: String)
    suspend fun create(name: String): Workspace
    suspend fun createShell(workspaceId: String, name: String): ShellSession
    suspend fun createShell(workspaceId: String, name: String, runtime: ShellRuntime): ShellSession {
        require(runtime == ShellRuntime.ANDROID) { "Linux Shell 服务不可用" }
        return createShell(workspaceId, name)
    }
    suspend fun location(workspaceId: String): String { error("工作区目录服务不可用") }
    /** Fixed-name root instruction sources only. Recheck host authority around every read. */
    suspend fun instructionSources(workspaceId: String, authorize: suspend () -> Unit): List<WorkspaceInstructionSource> {
        error("工作区项目规则读取服务不可用")
    }
    suspend fun snapshot(workspaceId: String): WorkspaceSnapshot = WorkspaceSnapshot(workspaceId,emptyMap(),false,System.currentTimeMillis())
    suspend fun list(workspaceId: String, path: String = ""): List<WorkspaceFile> { error("工作区目录服务不可用") }
    suspend fun makeDirectory(workspaceId: String, path: String) { error("工作区目录创建服务不可用") }
    suspend fun moveEntry(workspaceId: String, path: String, destination: String) { error("工作区重命名服务不可用") }
    suspend fun deleteEntry(workspaceId: String, path: String) { error("工作区删除服务不可用") }
    suspend fun read(workspaceId: String, path: String): String
    suspend fun readTextChunk(workspaceId: String, path: String, offset: Int = 0,
        expectedSha256: String? = null): WorkspaceTextChunk { error("工作区文本分段读取服务不可用") }
    suspend fun readForEdit(workspaceId: String, path: String): WorkspaceTextFile { error("安全文本编辑服务不可用") }
    suspend fun writeForEdit(workspaceId: String, path: String, text: String, expectedSha256: String?): WorkspaceWriteEvidence {
        error("安全文本编辑服务不可用")
    }
    /** Commit one complete UTF-8 document, up to the preview limit, against its opened version. */
    suspend fun writeTextForEdit(workspaceId: String, path: String, text: String,
        expectedSha256: String?): WorkspaceWriteEvidence { error("完整文本编辑服务不可用") }
    /** Create only: an existing file is a conflict, including one created by a concurrent Shell. */
    suspend fun importFile(workspaceId: String, path: String, bytes: ByteArray,
        expectedSha256: String): WorkspaceImportedFile { error("工作区文件导入服务不可用") }
    suspend fun reviewImportTarget(workspaceId: String, path: String): WorkspaceImportTarget? {
        error("工作区导入审阅服务不可用")
    }
    /** Replace only the reviewed content; retain the displaced file at backupPath. */
    suspend fun replaceImportedFile(workspaceId: String, path: String, bytes: ByteArray,
        sourceSha256: String, targetSha256: String): WorkspaceImportedFile {
        error("工作区文件替换服务不可用")
    }
    suspend fun importBackups(workspaceId: String, offset: Int = 0, limit: Int = 20): WorkspaceImportBackupPage {
        error("工作区备份登记服务不可用")
    }
    suspend fun readImportBackup(workspaceId: String, backupId: String,
        expectedSha256: String? = null): WorkspaceImportBackupContent { error("工作区备份读取服务不可用") }
    /** Explicit current-content approval only; startup must never replay this action. */
    suspend fun cleanupImportBackup(workspaceId:String,backupId:String,expectedSha256:String) {
        error("备份清理服务不可用")
    }
    suspend fun importBackupCleanups(workspaceId:String,offset:Int=0,limit:Int=20):WorkspaceBackupCleanupPage {
        error("备份清理记录服务不可用")
    }
    /** Acknowledge an absent claim; removes records only, never a workspace filename. */
    suspend fun dismissImportBackupCleanup(workspaceId:String,backupId:String) {
        error("备份清理记录确认服务不可用")
    }
    suspend fun restoreImportBackupCleanup(workspaceId:String,backupId:String) {
        error("备份清理恢复服务不可用")
    }
    suspend fun importRecoveries(workspaceId: String): List<WorkspaceImportRecovery> = emptyList()
    suspend fun dismissImportRecovery(workspaceId: String, recoveryId: String) {
        error("工作区导入恢复记录服务不可用")
    }
    suspend fun write(workspaceId: String, path: String, text: String)
    suspend fun writeObserved(workspaceId: String, path: String, text: String): WorkspaceWriteEvidence {
        write(workspaceId,path,text)
        return WorkspaceWriteEvidence(workspaceId,path,FileObservation(FileObservationKind.UNAVAILABLE),
            FileObservation(FileObservationKind.UNAVAILABLE),null,false)
    }
    suspend fun run(workspaceId: String, shellId: String, command: String, timeoutMs: Long = 60_000): ShellJob
    suspend fun cancel(workspaceId: String, jobId: String)
    suspend fun cancelOwner(owner: String)
}
val WorkspaceServiceKey = ServiceKey("workspace", WorkspaceService::class)

data class PluginDraft(val id: String, val workspaceId: String, val pluginId: String, val name: String,
    val version: String, val permissions: Set<String>, val path: String, val sha256: String)
interface PluginDraftService {
    val drafts: StateFlow<List<PluginDraft>>
    suspend fun prepare(workspaceId: String, manifestJson: String, source: String): PluginDraft
    suspend fun payload(id: String): String
    /** Installs exactly the reviewed digest and always leaves the plugin disabled. */
    suspend fun install(id: String, expectedSha256: String)
}
val PluginDraftServiceKey = ServiceKey("plugins.drafts", PluginDraftService::class)
