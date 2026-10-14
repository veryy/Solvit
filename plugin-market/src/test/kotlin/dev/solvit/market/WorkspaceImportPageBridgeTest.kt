package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.workspace.AndroidWorkspaces
import dev.solvit.workspace.WorkspaceImportPublisher
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceImportPageBridgeTest {
    @Test fun reviewTokenBindsSelectionAndPathAndRejectsStaleTargetBeforeReReviewingAndReplacing()=fixture { service ->
        val workspace=service.forChat("review-import")
        val root=File(service.location(workspace.id));val destination=File(root,"file.bin").apply { writeText("old") }
        val bridge=WorkspacePageBridge(service,null,"review-import")
        try {
            val bytes=byteArrayOf(0,-1,2,3)
            val document=PageBinaryDocument("file.bin","application/octet-stream",bytes)
            val selected=Json.parseToJsonElement(bridge.stageImport(document) {}).jsonObject
            val args=buildJsonObject { put("id",selected.getValue("id"));put("path","file.bin");put("sha256",document.sha256) }
            suspend fun review()=Json.parseToJsonElement(bridge.query("reviewImport",args.toString()) {}).jsonObject
            fun approved(review:JsonObject,path:String="file.bin")=PageWorkspaceControl("importReviewed",buildJsonObject {
                args.forEach { (key,value)->put(key,value) };put("path",path);put("reviewId",review.getValue("reviewId"))
            }.toString())
            val first=review()
            assertEquals(JsonPrimitive(3),first.getValue("target").jsonObject["bytes"])
            assertFails { bridge.perform(approved(first,"other.bin")) {} }
            assertEquals("old",destination.readText());assertEquals(1,root.listFiles()!!.size)
            destination.writeText("Shell changed")
            assertFails { bridge.perform(approved(first)) {} }
            assertFails { bridge.perform(approved(first)) {} }
            assertEquals("Shell changed",destination.readText())
            val second=review();assertNotEquals(first["reviewId"],second["reviewId"])
            val result=Json.parseToJsonElement(bridge.perform(approved(second)) {}).jsonObject
            assertContentEquals(bytes,destination.readBytes())
            val backup=result.getValue("lastImport").jsonObject.getValue("backupPath").jsonPrimitive.content
            assertEquals("Shell changed",File(root,backup).readText())
            assertFails { document.readBytes() };assertEquals(2,root.listFiles()!!.size)
        } finally { bridge.close() }
    }
    @Test fun savedChatSelectionPreviewsAndImportsExactBytesWithItsMessageProvenance()=fixture { service ->
        val app=RuntimeEnvironment.getApplication()
        app.deleteDatabase("solvit.db")
        dev.solvit.storage.SqliteChatStore(app).use { chats ->
            val files=dev.solvit.storage.AttachmentFiles(File(app.filesDir,"chat-attachments"))
            val chat=chats.createSession().id
            val workspace=service.forChat(chat)
            val bytes=byteArrayOf(0,-1,-2,65)+ByteArray(300_000) { it.toByte() }
            val attachment=files.import(chat,"original.bin","application/octet-stream",java.io.ByteArrayInputStream(bytes))
            chats.append(ChatMessage("saved-binary",chat,Role.USER,"private body must not enter metadata",attachments=listOf(attachment)))
            var active=chat
            val source=SavedChatAttachmentSource(chats,files,chat) { active }
            val bridge=WorkspacePageBridge(service,null,chat,chatAttachments=source) { active }
            try {
                val listing=Json.parseToJsonElement(bridge.query("chatAttachments","{\"offset\":0}") {}).jsonObject
                val entry=listing.getValue("attachments").jsonArray.single().jsonObject
                assertEquals(JsonPrimitive(1),listing["total"])
                assertFalse(listing.toString().contains("private body"));assertFalse(listing.toString().contains(".blob"))
                val action=PageWorkspaceControl("selectChatAttachment",buildJsonObject {
                    put("messageId",entry.getValue("messageId"));put("attachmentId",entry.getValue("attachmentId"));put("sha256",entry.getValue("sha256"))
                }.toString())
                bridge.perform(action) {}
                val selected=Json.parseToJsonElement(bridge.query("importSelection","{}") {}).jsonObject
                assertEquals(JsonPrimitive("saved-binary"),selected.getValue("source").jsonObject["messageId"])
                assertEquals(JsonPrimitive("chatAttachment"),selected.getValue("source").jsonObject["kind"])
                assertTrue(service.list(workspace.id).isEmpty())
                val preview=Json.parseToJsonElement(bridge.query("importText",buildJsonObject {
                    put("id",selected.getValue("id"));put("sha256",selected.getValue("sha256"))
                }.toString()) {}).jsonObject
                assertEquals(JsonPrimitive("OVERSIZED"),preview["kind"])
                bridge.perform(control(selected,"original.bin")) {}
                assertContentEquals(bytes,File(service.location(workspace.id),"original.bin").readBytes())
                assertContentEquals(bytes,files.read(chat,attachment))
                assertEquals("null",bridge.query("importSelection","{}") {})
                active="another-chat"
                assertFails { bridge.query("chatAttachments","{\"offset\":0}") {} }
            } finally { bridge.close() }
        }
    }
    @Test fun deletedChatSourceCannotBeConfirmedAndReplacingWithSystemSelectionClearsProvenance()=fixture { service ->
        val app=RuntimeEnvironment.getApplication();app.deleteDatabase("solvit.db")
        dev.solvit.storage.SqliteChatStore(app).use { chats ->
            val files=dev.solvit.storage.AttachmentFiles(File(app.filesDir,"chat-attachments"))
            val chat=chats.createSession().id;val workspace=service.forChat(chat)
            val attachment=files.import(chat,"note.txt","text/plain",java.io.ByteArrayInputStream("saved".toByteArray()))
            chats.append(ChatMessage("saved-note",chat,Role.USER,"",attachments=listOf(attachment)))
            val bridge=WorkspacePageBridge(service,null,chat,chatAttachments=SavedChatAttachmentSource(chats,files,chat) { chat })
            try {
                bridge.perform(PageWorkspaceControl("selectChatAttachment",buildJsonObject {
                    put("messageId","saved-note");put("attachmentId",attachment.id);put("sha256",attachment.sha256)
                }.toString())) {}
                val selected=Json.parseToJsonElement(bridge.query("importSelection","{}") {}).jsonObject
                chats.deleteSession(chat)
                assertFails { bridge.perform(control(selected,"deleted.txt")) {} }
                assertTrue(service.list(workspace.id).isEmpty())
                val system=Json.parseToJsonElement(bridge.stageImport(PageBinaryDocument("system.txt","text/plain","system".toByteArray())) {}).jsonObject
                assertFalse("source" in system)
                bridge.perform(control(system,"system.txt")) {}
                assertEquals("system",service.read(workspace.id,"system.txt"))
            } finally { bridge.close() }
        }
    }
    @Test fun pdfPreviewNeedsTheReviewedSelectionAndClosesPixelsAfterScopeChanges()=fixture { service ->
        val workspace=service.forChat("pdf-page")
        var active="pdf-page";var calls=0;var changeDuringRender=false;var generated:PageFilePicture?=null
        val renderer=PagePdfPreviewRenderer { _, page, authorize ->
            authorize();calls++
            val picture=PageFilePicture(1,1,byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte()))
            generated=picture
            if(changeDuringRender)active="another-chat"
            PagePdfPreview(page,2,picture,"fixture page $page",false)
        }
        val bridge=WorkspacePageBridge(service,null,"pdf-page",pdfRenderer=renderer) { active }
        val document=PageBinaryDocument("fixture.pdf","application/pdf","fixture original bytes".toByteArray())
        val selected=Json.parseToJsonElement(bridge.stageImport(document) {}).jsonObject
        fun args(page:JsonPrimitive,id:JsonElement=selected.getValue("id"))=buildJsonObject {
            put("id",id);put("sha256",selected.getValue("sha256"));put("page",page)
        }
        assertFails { bridge.query("importPdf",args(JsonPrimitive(1),JsonPrimitive("stale")).toString()) {} }
        assertFails { bridge.query("importPdf",args(JsonPrimitive("1")).toString()) {} }
        assertFails { bridge.query("importPdf",args(JsonPrimitive(0)).toString()) {} }
        assertEquals(0,calls)
        val result=Json.parseToJsonElement(bridge.query("importPdf",args(JsonPrimitive(2)).toString()) {}).jsonObject
        assertEquals(JsonPrimitive(2),result["page"]);assertEquals(JsonPrimitive("fixture page 2"),result["text"])
        assertFails { generated!!.jpegBytes() };assertTrue(service.list(workspace.id).isEmpty())
        changeDuringRender=true
        assertFails { bridge.query("importPdf",args(JsonPrimitive(1)).toString()) {} }
        assertFails { generated!!.jpegBytes() };assertContentEquals("fixture original bytes".toByteArray(),document.readBytes())
        bridge.close();assertFails { document.readBytes() }
    }
    @Test fun textPreviewIsScopedToTheReviewedTokenAndDoesNotWrite()=fixture { service ->
        val workspace=service.forChat("preview-page")
        val bridge=WorkspacePageBridge(service,null,"preview-page")
        val original="<script>window.executed=true</script>\n中文🙂".toByteArray()
        val document=PageBinaryDocument("source.js","text/javascript",original)
        val selection=Json.parseToJsonElement(bridge.stageImport(document) {}).jsonObject
        val args=buildJsonObject { put("id",selection.getValue("id"));put("sha256",selection.getValue("sha256")) }
        val preview=Json.parseToJsonElement(bridge.query("importText",args.toString()) {}).jsonObject
        assertEquals(JsonPrimitive("TEXT"),preview["kind"])
        assertEquals(JsonPrimitive(original.toString(Charsets.UTF_8)),preview["text"])
        assertEquals(JsonPrimitive(false),preview["truncated"]);assertTrue(service.list(workspace.id).isEmpty())
        assertFails { bridge.query("importText",buildJsonObject { put("id","other");put("sha256",document.sha256) }.toString()) {} }
        bridge.perform(control(selection,"source.js")) {}
        assertContentEquals(original,File(service.location(workspace.id),"source.js").readBytes())
        assertFails { bridge.query("importText",args.toString()) {} };bridge.close()
    }
    private fun fixture(block:suspend(AndroidWorkspaces)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,
            importPublisher=WorkspaceImportPublisher { from,to -> Files.move(from.toPath(),to.toPath());Unit })
        try { block(service) } finally { service.close();scope.cancel() }
    }
    private fun control(metadata:JsonObject,path:String)=PageWorkspaceControl("importFile",buildJsonObject {
        put("id",metadata.getValue("id"));put("sha256",metadata.getValue("sha256"));put("path",path)
    }.toString())
    @Test fun hostSelectionReturnsMetadataAndConfirmedImportWritesExactBytesInItsBoundWorkspace()=fixture { service ->
        val workspace=service.forChat("import-page")
        val bridge=WorkspacePageBridge(service,null,"import-page")
        val bytes=ByteArray(300_000) { (it%256).toByte() }
        val document=PageBinaryDocument("scan.pdf","application/pdf",bytes)
        val selected=Json.parseToJsonElement(bridge.stageImport(document) {}).jsonObject
        assertEquals(setOf("id","name","mimeType","bytes","sha256"),selected.keys)
        assertEquals(document.sha256,selected.getValue("sha256").jsonPrimitive.content)
        assertEquals(selected,Json.parseToJsonElement(bridge.query("importSelection","{}") {}))
        assertTrue(service.list(workspace.id).isEmpty())
        bridge.perform(control(selected,"scan.pdf")) {}
        assertContentEquals(bytes,File(service.location(workspace.id),"scan.pdf").readBytes())
        assertEquals(JsonNull,Json.parseToJsonElement(bridge.query("importSelection","{}") {}))
        assertFails { document.readBytes() };bridge.close()
    }
    @Test fun changingChatOrReviewedSelectionCannotUseAStaleTokenAndCloseInvalidatesBytes()=fixture { service ->
        val workspace=service.forChat("a");service.bind("b",workspace.id)
        var active="a"
        val bridge=WorkspacePageBridge(service,null,"a",activeChat={ active })
        val first=PageBinaryDocument("first.bin","application/octet-stream",byteArrayOf(0,1))
        val old=Json.parseToJsonElement(bridge.stageImport(first) {}).jsonObject
        val second=PageBinaryDocument("second.bin","application/octet-stream",byteArrayOf(2,3))
        val current=Json.parseToJsonElement(bridge.stageImport(second) {}).jsonObject
        assertFails { first.readBytes() }
        assertFails { bridge.perform(control(old,"stale.bin")) {} }
        assertTrue(service.list(workspace.id).isEmpty())
        active="b"
        assertFails { bridge.perform(control(current,"cross-chat.bin")) {} }
        assertFails { bridge.query("importSelection","{}") {} }
        bridge.close();assertFails { second.readBytes() }
        assertTrue(service.list(workspace.id).isEmpty())
    }
    @Test fun conflictsKeepSelectionForRenamingAndRevocationDoesNotPublishPartialContent()=fixture { service ->
        val workspace=service.forChat("retry-import")
        val bridge=WorkspacePageBridge(service,null,"retry-import")
        val root=File(service.location(workspace.id));File(root,"existing.bin").writeText("keep")
        val document=PageBinaryDocument("file.bin","application/octet-stream",byteArrayOf(0,255.toByte()))
        val selected=Json.parseToJsonElement(bridge.stageImport(document) {}).jsonObject
        assertFails { bridge.perform(control(selected,"existing.bin")) {} }
        assertEquals("keep",File(root,"existing.bin").readText())
        assertEquals(selected,Json.parseToJsonElement(bridge.query("importSelection","{}") {}))
        var checks=0
        assertFails { bridge.perform(control(selected,"revoked.bin")) { checks++;check(checks<4) { "revoked" } } }
        assertFalse(File(root,"revoked.bin").exists())
        assertEquals(listOf("existing.bin"),root.listFiles()!!.map { it.name })
        bridge.perform(control(selected,"renamed.bin")) {}
        assertContentEquals(byteArrayOf(0,255.toByte()),File(root,"renamed.bin").readBytes());bridge.close()
    }
    @Test fun aPlainChatCannotImplicitlyCreateAWorkspaceOrRetainRejectedSelection()=fixture { service ->
        val bridge=WorkspacePageBridge(service,null,"unbound")
        val document=PageBinaryDocument("file.bin","application/octet-stream",byteArrayOf(1))
        assertFails { bridge.stageImport(document) {} }
        assertFails { document.readBytes() }
        assertNull(service.bound("unbound"));bridge.close()
    }
}
