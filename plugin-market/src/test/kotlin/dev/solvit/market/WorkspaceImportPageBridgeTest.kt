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
    private fun fixture(block:suspend(AndroidWorkspaces)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,
            importPublisher=WorkspaceImportPublisher { from,to -> Files.createLink(to.toPath(),from.toPath()) })
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
