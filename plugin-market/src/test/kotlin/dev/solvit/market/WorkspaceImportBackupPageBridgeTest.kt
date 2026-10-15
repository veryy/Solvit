package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.workspace.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceImportBackupPageBridgeTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fixture(block:suspend(AndroidWorkspaces,File,String,WorkspacePageBridge)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,
            importPublisher=WorkspaceImportPublisher { source,destination -> Files.move(source.toPath(),destination.toPath());Unit })
        val chat="backup-page-${java.util.UUID.randomUUID()}"
        val workspace=service.forChat(chat);val root=File(service.location(workspace.id))
        val bridge=WorkspacePageBridge(service,null,chat)
        try { block(service,root,workspace.id,bridge) } finally { bridge.close();service.close();scope.cancel() }
    }
    @Test fun nativeBackupReviewSelectsAValidatedCopyAndRestoresThroughExistingDestinationReview()=fixture { service,root,id,bridge ->
        val original="original".toByteArray();val replacement="replacement".toByteArray()
        File(root,"file.txt").writeBytes(original)
        val imported=service.replaceImportedFile(id,"file.txt",replacement,sha(replacement),sha(original))
        val backup=File(root,checkNotNull(imported.backupPath))
        val listing=Json.parseToJsonElement(bridge.query("importBackups","{\"offset\":0}"){}).jsonObject
        assertEquals(1,listing.getValue("total").jsonPrimitive.int)
        assertFalse(listing.toString().contains(root.absolutePath))
        val record=listing.getValue("backups").jsonArray.single().jsonObject
        val backupId=record.getValue("id")
        fun selection(hash:String)=PageWorkspaceControl("selectImportBackup",buildJsonObject {
            put("backupId",backupId);put("path","file.txt");put("sha256",hash)
        }.toString())
        assertFails { bridge.perform(selection(sha(original))){} }
        // An existing Shell descriptor can change a retained inode. Review returns its current bytes.
        backup.writeText("changed backup")
        val review=Json.parseToJsonElement(bridge.query("reviewBackup",buildJsonObject { put("backupId",backupId) }.toString()){}).jsonObject
        val currentSha=sha(backup.readBytes())
        assertEquals(currentSha,review.getValue("sha256").jsonPrimitive.content)
        assertNotEquals(record.getValue("originalSha256"),review.getValue("sha256"))
        bridge.perform(selection(currentSha)){}
        assertFails { bridge.perform(selection(currentSha)){} }
        assertEquals("replacement",File(root,"file.txt").readText())
        val selected=Json.parseToJsonElement(bridge.query("importSelection","{}"){}).jsonObject
        assertEquals(JsonPrimitive("workspaceBackup"),selected.getValue("source").jsonObject["kind"])
        val args=buildJsonObject { put("id",selected.getValue("id"));put("sha256",selected.getValue("sha256"));put("path","file.txt") }
        val target=Json.parseToJsonElement(bridge.query("reviewImport",args.toString()){}).jsonObject
        assertEquals(JsonPrimitive(sha(replacement)),target.getValue("target").jsonObject["sha256"])
        bridge.perform(PageWorkspaceControl("importReviewed",buildJsonObject {
            args.forEach { (k,v)->put(k,v) };put("reviewId",target.getValue("reviewId"))
        }.toString())){}
        assertEquals("changed backup",File(root,"file.txt").readText())
        assertEquals("changed backup",backup.readText())
        assertEquals(2,service.importBackups(id).total)
        assertEquals("null",bridge.query("importSelection","{}"){})
    }
    @Test fun backupChangesAfterSelectionRejectImportAndKeepBothFilesAndSelection()=fixture { service,root,id,bridge ->
        val original="original".toByteArray();val replacement="replacement".toByteArray()
        File(root,"file.txt").writeBytes(original)
        val imported=service.replaceImportedFile(id,"file.txt",replacement,sha(replacement),sha(original))
        val backup=File(root,checkNotNull(imported.backupPath))
        val record=service.importBackups(id).backups.single()
        val args=buildJsonObject { put("backupId",record.id) }
        bridge.query("reviewBackup",args.toString()){}
        backup.writeText("changed before approval")
        val select=PageWorkspaceControl("selectImportBackup",buildJsonObject {
            put("backupId",record.id);put("path",record.path);put("sha256",sha(original))
        }.toString())
        assertFails { bridge.perform(select){} };assertEquals("null",bridge.query("importSelection","{}"){})
        val current=Json.parseToJsonElement(bridge.query("reviewBackup",args.toString()){}).jsonObject
        bridge.perform(PageWorkspaceControl("selectImportBackup",buildJsonObject {
            put("backupId",record.id);put("path",record.path);put("sha256",current.getValue("sha256"))
        }.toString())){}
        val selected=Json.parseToJsonElement(bridge.query("importSelection","{}"){}).jsonObject
        val targetArgs=buildJsonObject { put("id",selected.getValue("id"));put("sha256",selected.getValue("sha256"));put("path","restored.txt") }
        val target=Json.parseToJsonElement(bridge.query("reviewImport",targetArgs.toString()){}).jsonObject
        backup.writeText("changed after selection")
        assertFails { bridge.perform(PageWorkspaceControl("importReviewed",buildJsonObject {
            targetArgs.forEach { (k,v)->put(k,v) };put("reviewId",target.getValue("reviewId"))
        }.toString())){} }
        assertFalse(File(root,"restored.txt").exists());assertEquals("replacement",File(root,"file.txt").readText())
        assertEquals("changed after selection",backup.readText())
        assertNotEquals("null",bridge.query("importSelection","{}"){})
        assertFails { bridge.query("importBackups","{\"offset\":0,\"workspaceId\":\"foreign\"}"){} }
    }
}
