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
class WorkspaceImportRecoveryPageBridgeTest {
    @Test fun persistedNativeReportsArePagedChatBoundAndDismissNeverDeletesEitherFile()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val native=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,
            importPublisher=WorkspaceImportPublisher { source,destination ->
                if(source.name.startsWith(".solvit-import-"))destination.writeText("concurrent Shell")
                Files.move(source.toPath(),destination.toPath());Unit
            })
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        try {
            val chat="recovery-${java.util.UUID.randomUUID()}"
            val workspace=native.forChat(chat);val root=File(native.location(workspace.id))
            val bytes="new imported content".toByteArray();val original="original".toByteArray()
            repeat(7){ index ->
                File(root,"file-$index").writeBytes(original)
                assertFails { native.replaceImportedFile(workspace.id,"file-$index",bytes,sha(bytes),sha(original)) }
            }
            var current=chat
            val bridge=WorkspacePageBridge(native,null,chat){current}
            val first=Json.parseToJsonElement(bridge.query("importRecoveries","{\"offset\":0}"){}).jsonObject
            assertEquals(7,first.getValue("total").jsonPrimitive.int)
            val entries=first.getValue("recoveries").jsonArray
            assertEquals(5,entries.size)
            val last=Json.parseToJsonElement(bridge.query("importRecoveries","{\"offset\":5}"){}).jsonObject
            assertEquals(2,last.getValue("recoveries").jsonArray.size)
            assertFalse(first.toString().contains(root.absolutePath));assertTrue(entries.all { it.jsonObject.getValue("status")==JsonPrimitive("REVIEW_REQUIRED") })
            val item=entries.first().jsonObject
            val control=PageWorkspaceControl("dismissImportRecovery",buildJsonObject { put("recoveryId",item.getValue("id")) }.toString())
            val target=File(root,item.getValue("path").jsonPrimitive.content)
            val backup=File(root,item.getValue("backupPath").jsonPrimitive.content)
            assertEquals("concurrent Shell",target.readText());assertEquals("original",backup.readText())
            current="another-chat"
            assertFails { bridge.query("importRecoveries","{\"offset\":0}"){} }
            assertFails { bridge.perform(control){} }
            assertEquals(7,native.importRecoveries(workspace.id).size)
            current=chat;bridge.perform(control){}
            assertEquals(6,native.importRecoveries(workspace.id).size)
            assertEquals("concurrent Shell",target.readText());assertEquals("original",backup.readText())
            assertFails { bridge.query("importRecoveries","{\"offset\":0,\"workspaceId\":\"other\"}"){} }
            assertFails { bridge.query("importRecoveries","{\"offset\":-1}"){} }
            assertFails { bridge.perform(control){} }
            bridge.close()
        }finally{native.close();scope.cancel()}
    }
}
