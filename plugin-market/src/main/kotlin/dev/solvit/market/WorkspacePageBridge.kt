package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.ScriptPageHostAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One captured conversation. A background rebind invalidates it; it never follows another chat. */
class WorkspacePageBridge(private val service: WorkspaceService, private val drafts: PluginDraftService?,
    private val chatId: String, private val canInstallDrafts: Boolean = false,
    private val linux: LinuxEnvironmentService? = null,
    private val pictureRenderer: PageFilePictureRenderer? = null,
    private val pdfRenderer: PagePdfPreviewRenderer? = null,
    private val chatAttachments: SavedChatAttachmentSource? = null,
    private val activeChat: () -> String? = { chatId }) : PageWorkspaceAccess {
    init { require(chatId.isNotBlank() && chatId.length <= 128) }
    private val mutex = Mutex()
    private var binding = service.state.value.bindings[chatId]
    @Volatile private var closed = false
    private val importScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val operationLock=Any()
    private data class ImportOperation(val id:String,val status:String="RUNNING",
        val progress:WorkspaceImportProgress?=null,val result:JsonElement?=null,val error:String?=null)
    private var importOperation:ImportOperation?=null
    private var importJob:Job?=null
    private var cancelImportRequested=false
    private fun operationSnapshot(id:String):String=synchronized(operationLock) {
        val value=checkNotNull(importOperation) { "没有导入任务" }
        check(value.id==id) { "导入任务已改变" }
        buildJsonObject {
            put("operationId",value.id);put("status",value.status)
            value.progress?.let { put("progress",buildJsonObject {
                put("stage",it.stage);put("completed",it.completed);put("total",it.total)
            }) }
            value.result?.let { put("result",it) };value.error?.let { put("error",it) }
        }.toString()
    }
    private fun operationId(argumentsJson:String):String {
        val args=Json.parseToJsonElement(argumentsJson).jsonObject
        require(args.keys==setOf("operationId"))
        return args.getValue("operationId").jsonPrimitive.also { require(it.isString && it.content.length in 1..128) }.content
    }
    private data class TextDraft(val id:String,val path:String,val expectedSha256:String?,val total:Int,
        val text:StringBuilder=StringBuilder(),var bytes:Int=0,var sha256:String?=null)
    private var textDraft:TextDraft?=null
    private var importDraft:Pair<String,PageBinaryDocument>?=null
    private data class AttachmentOrigin(val messageId:String,val attachmentId:String,val sha256:String)
    private var importOrigin:AttachmentOrigin?=null
    private data class BackupOrigin(val id:String,val sha256:String,val path:String,val backupPath:String)
    private var importBackupOrigin:BackupOrigin?=null
    private var backupReview:BackupOrigin?=null
    private data class ImportReview(val id:String,val selectionId:String,val path:String,val sourceSha256:String,
        val target:WorkspaceImportTarget?)
    private var importReview:ImportReview?=null
    private var lastImport:WorkspaceImportedFile?=null
    private fun importMetadata():JsonElement = importDraft?.let { (id,document) -> buildJsonObject {
        put("id",id);put("name",document.name);put("mimeType",document.mimeType);put("bytes",document.sizeBytes);put("sha256",document.sha256)
        importBackupOrigin?.let { origin -> put("source",buildJsonObject {
            put("kind","workspaceBackup");put("backupId",origin.id);put("path",origin.path);put("backupPath",origin.backupPath)
        }) }
        importOrigin?.let { origin -> put("source",buildJsonObject {
            put("kind","chatAttachment");put("messageId",origin.messageId);put("attachmentId",origin.attachmentId)
        }) }
    } } ?: JsonNull
    override suspend fun stageImport(document:PageBinaryDocument,authorize:suspend ()->Unit):String {
        var adopted=false
        try {
            return mutex.withLock {
                verify(authorize);workspace()
                val next=java.util.UUID.randomUUID().toString() to document
                importDraft?.second?.close();importDraft=next;importOrigin=null;importBackupOrigin=null;importReview=null;adopted=true
                importMetadata().toString()
            }
        } finally { if(!adopted) document.close() }
    }
    private suspend fun verify(authorize: suspend () -> Unit) {
        check(!closed) { "工作区页面已关闭。" }
        authorize()
        check(activeChat() == chatId && service.state.value.bindings[chatId] == binding) { "对话或工作区已变化，请重新打开页面。" }
    }
    private fun workspace() = checkNotNull(binding) { "此对话尚未绑定工作区" }
    private fun snapshot(): JsonObject = buildJsonObject {
        put("workspaceId", binding?.let(::JsonPrimitive) ?: JsonNull)
        put("draftInstallAllowed", canInstallDrafts)
        lastImport?.let { result -> put("lastImport",buildJsonObject {
            put("path",result.path);put("sha256",result.sha256)
            put("backupPath",result.backupPath?.let(::JsonPrimitive)?:JsonNull)
        }) }
        put("workspaces", buildJsonArray { service.state.value.workspaces.forEach { w -> add(buildJsonObject { put("id", w.id); put("name", w.name) }) } })
        put("shells", buildJsonArray { service.state.value.shells.filter { it.workspaceId == binding }.forEach { s -> add(buildJsonObject { put("id", s.id); put("name", s.name); put("runtime", s.runtime.name) }) } })
    }
    private suspend fun <T> scoped(authorize: suspend () -> Unit, allowedNewWorkspace: () -> String? = { null }, block: suspend () -> T): T =
        withContext(WorkspacePageAuthority { target, session ->
            verify(authorize)
            check(session == null || session == chatId) { "不能操作其他对话" }
            check(target == null || target == binding || target == allowedNewWorkspace()) { "不能操作其他工作区" }
        }) { block() }

    override suspend fun query(operation:String,argumentsJson:String,authorize:suspend ()->Unit):String {
        // Import owns the ordinary page mutex while writing. Progress must never queue behind it.
        if(operation=="importOperation") {
            verify(authorize);workspace()
            val result=operationSnapshot(operationId(argumentsJson))
            verify(authorize);return result
        }
        return mutex.withLock {
        verify(authorize)
        val args = Json.parseToJsonElement(argumentsJson).jsonObject
        fun text(key: String) = args.getValue(key).jsonPrimitive.also { require(it.isString && it.content.length <= 1024) }.content
        val result = scoped(authorize) {
            when (operation) {
                "environmentStatus" -> {
                    require(args.isEmpty())
                    val environment = checkNotNull(linux) { "Linux 环境服务不可用" }.state.value
                    buildJsonObject {
                        put("supported", environment.supported); put("installed", environment.installed); put("stage", environment.stage)
                        put("bytes", environment.bytes); put("total", environment.total?.let(::JsonPrimitive) ?: JsonNull)
                        put("output", environment.output); put("failure", environment.failure?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
                "snapshot" -> { require(args.isEmpty()); snapshot() }
                "importBackups" -> {
                    require(args.keys==setOf("offset"))
                    val offset=args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int
                    val page=service.importBackups(workspace(),offset,5)
                    buildJsonObject {
                        put("offset",page.offset);put("total",page.total);put("logicalBytes",page.logicalBytes)
                        put("availableBytes",page.availableBytes?.let(::JsonPrimitive)?:JsonNull)
                        put("incomplete",page.incomplete);put("unreadableRecords",page.unreadableRecords)
                        put("backups",buildJsonArray { page.backups.forEach { r -> add(buildJsonObject {
                            put("id",r.id);put("path",r.path);put("backupPath",r.backupPath);put("createdAt",r.createdAt)
                            put("originalBytes",r.originalBytes);put("originalSha256",r.originalSha256)
                            put("status",r.status.name);put("pending",r.pending);put("currentBytes",r.currentBytes?.let(::JsonPrimitive)?:JsonNull)
                        }) } })
                    }
                }
                "importBackupCleanups" -> {
                    require(args.keys==setOf("offset"))
                    val offset=args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int
                    val page=service.importBackupCleanups(workspace(),offset,5)
                    buildJsonObject {
                        put("offset",page.offset);put("total",page.total);put("incomplete",page.incomplete);put("unreadableEntries",page.unreadableEntries)
                        put("cleanups",buildJsonArray { page.cleanups.forEach { r -> add(buildJsonObject {
                            put("id",r.id);put("path",r.path);put("backupPath",r.backupPath);put("phase",r.phase);put("status",r.status)
                            put("heldBytes",r.heldBytes?.let(::JsonPrimitive)?:JsonNull)
                        }) } })
                    }
                }
                "reviewBackup" -> {
                    require(args.keys==setOf("backupId"));backupReview=null
                    val content=service.readImportBackup(workspace(),text("backupId"))
                    try {
                        verify(authorize)
                        val r=content.backup
                        backupReview=BackupOrigin(r.id,content.sha256,r.path,r.backupPath)
                        buildJsonObject { put("backupId",r.id);put("path",r.path);put("backupPath",r.backupPath)
                            put("bytes",content.bytes.size);put("sha256",content.sha256) }
                    } finally { content.close() }
                }
                "importRecoveries" -> {
                    require(args.keys==setOf("offset"))
                    val offset=args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int.also { require(it in 0..100) }
                    val records=service.importRecoveries(workspace())
                    buildJsonObject { put("total",records.size);put("recoveries",buildJsonArray {
                        records.drop(offset).take(5).forEach { item -> add(buildJsonObject {
                            put("id",item.id);put("path",item.path);put("status",item.status)
                            put("backupPath",item.backupPath?.let(::JsonPrimitive)?:JsonNull)
                        }) }
                    }) }
                }
                "importSelection" -> { require(args.isEmpty());importMetadata() }
                "reviewImport" -> {
                    require(args.keys==setOf("id","path","sha256"))
                    val selected=checkNotNull(importDraft) { "请先选择要导入的文件" }
                    check(selected.first==text("id") && selected.second.sha256==text("sha256")) { "选择的文件已改变" }
                    importReview=null
                    val target=service.reviewImportTarget(workspace(),text("path"))
                    verify(authorize)
                    val review=ImportReview(java.util.UUID.randomUUID().toString(),selected.first,text("path"),selected.second.sha256,target)
                    importReview=review
                    buildJsonObject {
                        put("reviewId",review.id);put("path",review.path)
                        put("target",target?.let { buildJsonObject { put("bytes",it.bytes);put("sha256",it.sha256) } }?:JsonNull)
                    }
                }
                "chatAttachments" -> {
                    workspace();require(args.keys==setOf("offset"))
                    val offset=args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int
                    val page=checkNotNull(chatAttachments) { "聊天附件来源服务不可用" }.list(offset) { verify(authorize) }
                    buildJsonObject {
                        put("offset",page.offset);put("total",page.total)
                        put("attachments",buildJsonArray { page.entries.forEach { entry -> add(buildJsonObject {
                            put("messageId",entry.messageId);put("messageNumber",entry.messageNumber)
                            put("attachmentId",entry.attachment.id);put("name",entry.attachment.name)
                            put("mimeType",entry.attachment.mimeType);put("bytes",entry.attachment.sizeBytes)
                            put("sha256",entry.attachment.sha256)
                        }) } })
                    }
                }
                "importText" -> {
                    require(args.keys == setOf("id", "sha256"))
                    val selected = checkNotNull(importDraft) { "请先选择要导入的文件" }
                    check(selected.first == text("id") && selected.second.sha256 == text("sha256")) { "选择的文件已改变" }
                    val preview = selected.second.textPreview()
                    buildJsonObject {
                        put("kind", preview.kind); put("text", preview.text?.let(::JsonPrimitive) ?: JsonNull)
                        put("truncated", preview.truncated)
                    }
                }
                "importPicture" -> {
                    require(args.keys == setOf("id", "sha256"))
                    val selected = checkNotNull(importDraft) { "请先选择要导入的文件" }
                    check(selected.first == text("id") && selected.second.sha256 == text("sha256")) { "选择的文件已改变" }
                    checkNotNull(pictureRenderer) { "此页面没有图片预览服务" }.render(selected.second) { verify(authorize) }.use { picture ->
                        verify(authorize)
                        val jpeg = picture.jpegBytes()
                        try {
                            buildJsonObject {
                                put("width", picture.width); put("height", picture.height)
                                put("source", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg))
                            }
                        } finally { jpeg.fill(0) }
                    }
                }
                "importPdf" -> {
                    require(args.keys == setOf("id", "sha256", "page"))
                    val selected = checkNotNull(importDraft) { "请先选择要导入的文件" }
                    check(selected.first == text("id") && selected.second.sha256 == text("sha256")) { "选择的文件已改变" }
                    val page = args.getValue("page").jsonPrimitive.also { require(!it.isString) }.int
                    require(page in 1..DocumentText.MAX_TOTAL_PAGES) { "PDF页码无效" }
                    checkNotNull(pdfRenderer) { "此页面没有PDF预览服务" }.render(selected.second, page) { verify(authorize) }.use { preview ->
                        verify(authorize)
                        val jpeg = preview.picture.jpegBytes()
                        try {
                            buildJsonObject {
                                put("page", preview.page); put("pageCount", preview.pageCount)
                                put("text", preview.text); put("truncated", preview.truncated)
                                put("width", preview.picture.width); put("height", preview.picture.height)
                                put("source", "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg))
                            }
                        } finally { jpeg.fill(0) }
                    }
                }
                "list" -> {
                    require(args.keys == setOf("path", "offset"))
                    val offset = args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int.also { require(it in 0..1000) }
                    val files = service.list(workspace(), text("path"))
                    buildJsonObject {
                        put("total", files.size); put("offset", offset)
                        put("files", buildJsonArray { files.drop(offset).take(40).forEach { f -> add(buildJsonObject {
                            put("name", f.name); put("path", f.path); put("directory", f.directory); put("bytes", f.bytes)
                        }) } })
                    }
                }
                "read" -> {
                    require(args.keys == setOf("path"))
                    service.readForEdit(workspace(), text("path")).let { buildJsonObject { put("text", it.text); put("sha256", it.sha256) } }
                }
                "readChunk" -> {
                    require(args.keys == setOf("path", "offset", "expectedSha256"))
                    val offset=args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int
                    val expected=args.getValue("expectedSha256").let { value ->
                        if(value==JsonNull)null else value.jsonPrimitive.also {
                            require(it.isString && it.content.matches(Regex("[a-f0-9]{64}")))
                        }.content
                    }
                    service.readTextChunk(workspace(),text("path"),offset,expected).let { chunk -> buildJsonObject {
                        put("text",chunk.text);put("offset",chunk.offset);put("nextOffset",chunk.nextOffset)
                        put("total",chunk.total);put("sha256",chunk.sha256)
                    } }
                }
                "editBegin" -> {
                    require(args.keys==setOf("path","expectedSha256","total"))
                    val path=text("path")
                    require(path.isNotBlank() && '\\' !in path && '\u0000' !in path &&
                        path.split('/').none { it.isEmpty() || it=="." || it==".." })
                    val total=args.getValue("total").jsonPrimitive.also { require(!it.isString) }.int
                    require(total in 0..MAX_FILE_CAPTURE_BYTES)
                    val expected=args.getValue("expectedSha256").let { value ->
                        if(value==JsonNull)null else value.jsonPrimitive.also {
                            require(it.isString && it.content.matches(Regex("[a-f0-9]{64}")))
                        }.content
                    }
                    workspace()
                    if(expected!=null)service.readTextChunk(workspace(),path,0,expected)
                    TextDraft(java.util.UUID.randomUUID().toString(),path,expected,total).also { textDraft=it }
                        .let { buildJsonObject { put("draftId",it.id);put("offset",0) } }
                }
                "editAppend" -> {
                    require(args.keys==setOf("draftId","offset","text"))
                    val draft=checkNotNull(textDraft).also { check(it.id==text("draftId") && it.sha256==null) }
                    val offset=args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int
                    val chunk=args.getValue("text").jsonPrimitive.also { require(it.isString) }.content
                    require(offset==draft.text.length && chunk.isNotEmpty() && chunk.length<=4096 &&
                        draft.text.length+chunk.length<=draft.total && '\u0000' !in chunk)
                    val encoded=Charsets.UTF_8.newEncoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(chunk))
                    require(draft.bytes+encoded.remaining()<=MAX_FILE_CAPTURE_BYTES)
                    draft.bytes+=encoded.remaining();draft.text.append(chunk)
                    buildJsonObject { put("draftId",draft.id);put("offset",draft.text.length) }
                }
                "editFinish" -> {
                    require(args.keys==setOf("draftId"))
                    val draft=checkNotNull(textDraft).also { check(it.id==text("draftId") && it.text.length==it.total) }
                    if(draft.sha256==null)draft.sha256=java.security.MessageDigest.getInstance("SHA-256")
                        .digest(draft.text.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                    buildJsonObject {
                        put("draftId",draft.id);put("path",draft.path);put("sha256",checkNotNull(draft.sha256))
                        put("expectedSha256",draft.expectedSha256?.let(::JsonPrimitive)?:JsonNull)
                        put("bytes",draft.bytes)
                    }
                }
                "editDiscard" -> {
                    require(args.keys==setOf("draftId"));check(textDraft?.id==text("draftId"))
                    textDraft=null;buildJsonObject { put("discarded",true) }
                }
                "drafts" -> {
                    require(args.isEmpty())
                    buildJsonArray { drafts?.drafts?.value.orEmpty().filter { it.workspaceId == workspace() }.takeLast(40).reversed().forEach { d -> add(buildJsonObject {
                        put("id", d.id); put("name", d.name); put("pluginId", d.pluginId); put("version", d.version); put("sha256", d.sha256)
                        put("permissions", JsonArray(d.permissions.map(::JsonPrimitive)))
                    }) } }
                }
                "draft" -> {
                    require(args.keys == setOf("id", "offset"))
                    val draft = checkNotNull(drafts).drafts.value.first { it.id == text("id") && it.workspaceId == workspace() }
                    val offset = args.getValue("offset").jsonPrimitive.also { require(!it.isString) }.int.also { require(it in 0..2_097_152) }
                    val payload = drafts.payload(draft.id)
                    buildJsonObject { put("text", payload.drop(offset).take(16000)); put("total", payload.length); put("sha256", draft.sha256) }
                }
                else -> error("未开放的工作区页面查询")
            }
        }
        verify(authorize)
        result.toString()
        }
    }

    override suspend fun perform(control:PageWorkspaceControl,authorize:suspend ()->Unit):String {
        if(control.operation in setOf("startImport","cancelImport")) {
            verify(authorize);workspace()
            val args=Json.parseToJsonElement(control.argumentsJson).jsonObject
            ScriptPageHostAction.workspace(buildJsonObject { put("kind","workspace.perform");put("operation",control.operation);put("arguments",args) })
            if(control.operation=="cancelImport") {
                val id=operationId(control.argumentsJson)
                synchronized(operationLock) {
                    val value=checkNotNull(importOperation)
                    check(value.id==id) { "导入任务已改变" }
                    if(value.status=="RUNNING") { cancelImportRequested=true;importOperation=value.copy(status="CANCELLING") }
                }
                verify(authorize);return operationSnapshot(id)
            }
            val id=java.util.UUID.randomUUID().toString()
            val native=PageWorkspaceControl("importReviewed",control.argumentsJson)
            val job=importScope.launch(start=CoroutineStart.LAZY) {
                try {
                    val result=withContext(WorkspaceImportMonitor { progress ->
                        verify(authorize)
                        synchronized(operationLock) {
                            // User cancellation is cooperative at pre-publication checkpoints.
                            // After COMMITTED it reports success, including a late cancel click.
                            if(progress.stage!="COMMITTED" && cancelImportRequested)throw CancellationException("用户取消导入")
                            importOperation=checkNotNull(importOperation).copy(progress=progress)
                        }
                    }) {
                        verify(authorize)
                        synchronized(operationLock) { if(cancelImportRequested)throw CancellationException("用户取消导入") }
                        Json.parseToJsonElement(perform(native,authorize))
                    }
                    synchronized(operationLock) { importOperation=checkNotNull(importOperation).copy(status="SUCCEEDED",result=result) }
                } catch(_:CancellationException) {
                    synchronized(operationLock) { importOperation=checkNotNull(importOperation).copy(status="CANCELLED") }
                } catch(error:Exception) {
                    synchronized(operationLock) { importOperation=checkNotNull(importOperation).copy(status="FAILED",error=error.message?.take(1000)?:"导入失败") }
                }
            }
            try { synchronized(operationLock) {
                check(!closed && importJob?.isCompleted!=false) { "导入任务仍在进行" }
                cancelImportRequested=false;importOperation=ImportOperation(id);importJob=job;job.start()
            } }catch(error:Exception){job.cancel();throw error}
            return buildJsonObject { put("operationId",id) }.toString()
        }
        return mutex.withLock {
        verify(authorize)
        // Revalidate even when an implementation other than QuickJS invokes this capability.
        val args = Json.parseToJsonElement(control.argumentsJson).jsonObject
        ScriptPageHostAction.workspace(buildJsonObject { put("kind", "workspace.perform"); put("operation", control.operation); put("arguments", args) })
        fun text(key: String) = args.getValue(key).jsonPrimitive.content
        var created: String? = null
        var nextBinding = binding
        scoped(authorize, { created }) {
            when (control.operation) {
                "dismissImportRecovery" -> service.dismissImportRecovery(workspace(),text("recoveryId"))
                "cleanupImportBackup" -> {
                    val reviewed=checkNotNull(backupReview) { "请先审阅备份" };backupReview=null
                    check(reviewed.id==text("backupId") && reviewed.sha256==text("sha256") && reviewed.path==text("path")) { "备份审阅已失效" }
                    service.cleanupImportBackup(workspace(),reviewed.id,reviewed.sha256)
                }
                "restoreImportBackupCleanup" -> service.restoreImportBackupCleanup(workspace(),text("backupId"))
                "dismissImportBackupCleanup" -> service.dismissImportBackupCleanup(workspace(),text("backupId"))
                "selectImportBackup" -> {
                    val reviewed=checkNotNull(backupReview) { "请先审阅备份" };backupReview=null
                    check(reviewed.id==text("backupId") && reviewed.sha256==text("sha256") && reviewed.path==text("path")) { "备份审阅已失效" }
                    val content=service.readImportBackup(workspace(),reviewed.id,reviewed.sha256)
                    var document:PageBinaryDocument?=null;var adopted=false
                    try {
                        verify(authorize)
                        val name=reviewed.path.substringAfterLast('/')
                        val mime=java.net.URLConnection.guessContentTypeFromName(name)?:"application/octet-stream"
                        val nextDocument=PageBinaryDocument(name,mime,content.bytes);document=nextDocument
                        importDraft?.second?.close();importDraft=java.util.UUID.randomUUID().toString() to nextDocument
                        importOrigin=null;importBackupOrigin=reviewed;importReview=null;adopted=true
                    } finally { content.close();if(!adopted)document?.close() }
                }
                "selectChatAttachment" -> {
                    workspace()
                    val origin=AttachmentOrigin(text("messageId"),text("attachmentId"),text("sha256"))
                    val document=checkNotNull(chatAttachments) { "聊天附件来源服务不可用" }
                        .open(origin.messageId,origin.attachmentId,origin.sha256) { verify(authorize) }
                    var adopted=false
                    try {
                        verify(authorize)
                        val next=java.util.UUID.randomUUID().toString() to document
                        importDraft?.second?.close();importDraft=next;importOrigin=origin;importBackupOrigin=null;importReview=null;adopted=true
                    } finally { if(!adopted)document.close() }
                }
                "importFile", "importReviewed" -> {
                    val selected=checkNotNull(importDraft) { "请先选择要导入的文件" }
                    check(selected.first==text("id") && selected.second.sha256==text("sha256")) { "选择的文件已改变，请重新确认" }
                    val review=if(control.operation=="importReviewed") checkNotNull(importReview) { "请重新审阅目标文件" }.also {
                        check(it.id==text("reviewId") && it.selectionId==selected.first && it.path==text("path") && it.sourceSha256==selected.second.sha256) {
                            "导入审阅已失效，请重新确认"
                        }
                    } else null
                    importReview=null
                    importOrigin?.let { origin -> checkNotNull(chatAttachments).validate(
                        origin.messageId,origin.attachmentId,origin.sha256) { verify(authorize) } }
                    importBackupOrigin?.let { origin -> service.readImportBackup(workspace(),origin.id,origin.sha256).close() }
                    val bytes=selected.second.readBytes()
                    try {
                        lastImport=if(review?.target!=null) service.replaceImportedFile(workspace(),review.path,bytes,selected.second.sha256,review.target.sha256)
                        else service.importFile(workspace(),text("path"),bytes,selected.second.sha256)
                    }
                    finally { bytes.fill(0) }
                    verify(authorize)
                    selected.second.close();importDraft=null;importOrigin=null;importBackupOrigin=null;importReview=null
                }
                "dropImport" -> {
                    val selected=checkNotNull(importDraft) { "选择的文件已失效" }
                    check(selected.first==text("id")) { "选择的文件已改变" }
                    selected.second.close();importDraft=null;importOrigin=null;importBackupOrigin=null;importReview=null
                }

                "create" -> {
                    val value = service.create(text("name")); created = value.id
                    service.createShell(value.id, "Shell 1")
                    service.bind(chatId, value.id); nextBinding = value.id
                }
                "bind" -> { service.bind(chatId, text("id")); nextBinding = text("id") }
                "unbind" -> { service.unbind(chatId); nextBinding = null }
                "createShell" -> service.createShell(workspace(), text("name"))
                "createLinuxShell" -> { checkNotNull(linux); service.createShell(workspace(), text("name"), ShellRuntime.UBUNTU) }
                "installEnvironment" -> checkNotNull(linux) { "Linux 环境服务不可用" }.install()
                "cancelEnvironment" -> checkNotNull(linux) { "Linux 环境服务不可用" }.cancel()
                "installSoftware" -> checkNotNull(linux) { "Linux 环境服务不可用" }.installSoftware(LinuxSoftware.valueOf(text("software")))
                "write" -> service.writeForEdit(workspace(), text("path"), text("text"), args.getValue("expectedSha256").let { if (it == JsonNull) null else it.jsonPrimitive.content })
                "makeDirectory" -> service.makeDirectory(workspace(),text("path"))
                "moveEntry" -> service.moveEntry(workspace(),text("path"),text("destination"))
                "deleteEntry" -> service.deleteEntry(workspace(),text("path"))
                "writeDraft" -> {
                    val draft=checkNotNull(textDraft)
                    check(draft.id==text("draftId") && draft.path==text("path") && draft.sha256==text("sha256") &&
                        draft.sha256!=null && draft.text.length==draft.total) { "保存草稿已变化，请重新准备保存。" }
                    service.writeTextForEdit(workspace(),draft.path,draft.text.toString(),draft.expectedSha256)
                    textDraft=null
                }
                "installDraft" -> {
                    check(canInstallDrafts) { "此页面未获插件草稿安装授权" }
                    val d = checkNotNull(drafts).drafts.value.first { it.id == text("id") && it.workspaceId == workspace() && it.sha256 == text("sha256") }
                    verify(authorize); drafts.install(d.id, d.sha256)
                }
                else -> error("未开放的工作区页面操作")
            }
        }
        binding = nextBinding
        verify(authorize)
        snapshot().toString()
        }
    }
    override suspend fun close() {
        closed=true
        withContext(NonCancellable) {
            synchronized(operationLock) { importJob?.cancel() }
            importJob?.join();importScope.cancel()
            mutex.withLock { textDraft=null;backupReview=null;importDraft?.second?.close();importDraft=null;importOrigin=null;importBackupOrigin=null;importReview=null;lastImport=null }
        }
    }
}
