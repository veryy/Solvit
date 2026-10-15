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
class WorkspaceBackupCleanupPageBridgeTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun cleanupBindsCurrentBackupReviewConsumesItAndNeverDeletesTargetOrAnotherChatsFiles()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val mover=WorkspaceImportPublisher { a,b->Files.move(a.toPath(),b.toPath());Unit }
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,importPublisher=mover,backupMover=mover)
        val chat="cleanup-page-${java.util.UUID.randomUUID()}"
        val workspace=service.forChat(chat);val root=File(service.location(workspace.id))
        var active=chat
        val bridge=WorkspacePageBridge(service,null,chat){active}
        try {
            val original="old".toByteArray();val bytes="current".toByteArray()
            File(root,"file.txt").writeBytes(original)
            val imported=service.replaceImportedFile(workspace.id,"file.txt",bytes,sha(bytes),sha(original))
            val backup=File(root,checkNotNull(imported.backupPath));val record=service.importBackups(workspace.id).backups.single()
            val args=buildJsonObject { put("backupId",record.id) }
            fun cleanup(hash:String)=PageWorkspaceControl("cleanupImportBackup",buildJsonObject {
                put("backupId",record.id);put("path",record.path);put("sha256",hash)
            }.toString())
            assertFails { bridge.perform(cleanup(sha(original))){} }
            bridge.query("reviewBackup",args.toString()){}
            backup.writeText("changed before approval")
            assertFails { bridge.perform(cleanup(sha(original))){} }
            assertFails { bridge.perform(cleanup(sha(original))){} }
            assertEquals("changed before approval",backup.readText());assertEquals("current",File(root,"file.txt").readText())
            val review=Json.parseToJsonElement(bridge.query("reviewBackup",args.toString()){}).jsonObject
            val hash=review.getValue("sha256").jsonPrimitive.content
            active="other-chat";assertFails { bridge.perform(cleanup(hash)){} };active=chat
            bridge.perform(cleanup(hash)){}
            assertFalse(backup.exists());assertEquals("current",File(root,"file.txt").readText())
            val pending=Json.parseToJsonElement(bridge.query("importBackupCleanups","{\"offset\":0}"){}).jsonObject
            assertEquals(0,pending.getValue("total").jsonPrimitive.int)
            assertFalse(pending.toString().contains(root.absolutePath))
            assertFails { bridge.query("importBackupCleanups","{\"offset\":0,\"workspaceId\":\"foreign\"}"){} }
        }finally { bridge.close();service.close();scope.cancel() }
    }
}
