package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkspacePageBridgeTest {
    @Test fun pageCreatesOnlyAfterConfirmationAndReadsOnlyItsCapturedChat() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        try {
            val bridge = WorkspacePageBridge(service, null, "page-a")
            assertEquals(JsonNull, Json.parseToJsonElement(bridge.query("snapshot", "{}") {}).jsonObject["workspaceId"])
            assertNull(service.bound("page-a"))
            bridge.perform(PageWorkspaceControl("create", """{"name":"Page A"}""")) {}
            val a = checkNotNull(service.bound("page-a")); val b = service.forChat("page-b")
            service.write(a.id, "note.txt", "a")
            service.write(b.id, "private.txt", "b")
            val read = Json.parseToJsonElement(bridge.query("read", """{"path":"note.txt"}""") {}).jsonObject
            assertEquals("a", read.getValue("text").jsonPrimitive.content)
            assertFails { bridge.query("read", """{"path":"private.txt","workspaceId":"${b.id}"}""") {} }
            assertFails { bridge.query("read", """{"path":"../${b.id}/private.txt"}""") {} }
            service.bind("page-a", b.id)
            assertFails { bridge.query("read", """{"path":"private.txt"}""") {} }
            assertFails { bridge.perform(PageWorkspaceControl("write", """{"path":"private.txt","text":"bad","expectedSha256":null}""")) {} }
            assertEquals("b", service.read(b.id, "private.txt"))
        } finally { service.close(); scope.cancel() }
    }
    @Test fun nativeWriteRejectsChangedFileAndRechecksAuthorityInsideTheLock() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        try {
            val workspace = service.forChat("edit")
            val bridge = WorkspacePageBridge(service, null, "edit")
            service.write(workspace.id, "note.txt", "old")
            val version = service.readForEdit(workspace.id, "note.txt").sha256
            fun control(hash: String?) = PageWorkspaceControl("write", buildJsonObject {
                put("path", "note.txt"); put("text", "page"); put("expectedSha256", hash?.let(::JsonPrimitive) ?: JsonNull)
            }.toString())
            service.write(workspace.id, "note.txt", "external")
            assertFails { bridge.perform(control(version)) {} }
            assertEquals("external", service.read(workspace.id, "note.txt"))
            assertFails { bridge.perform(control(null)) {} }
            val updated = service.readForEdit(workspace.id, "note.txt").sha256
            bridge.perform(control(updated)) {}
            assertEquals("page", service.read(workspace.id, "note.txt"))
            val finalVersion = service.readForEdit(workspace.id, "note.txt").sha256
            var checks = 0
            assertFails { withContext(WorkspacePageAuthority { _, _ -> checks++; error("revoked at native boundary") }) {
                service.writeForEdit(workspace.id, "note.txt", "bad", finalVersion)
            } }
            assertEquals(1, checks); assertEquals("page", service.read(workspace.id, "note.txt"))
            service.write(workspace.id, "binary.dat", "\u0000")
            assertFails { service.readForEdit(workspace.id, "binary.dat") }
            service.write(workspace.id, "large.txt", "x".repeat(4097))
            assertFails { service.readForEdit(workspace.id, "large.txt") }
        } finally { service.close(); scope.cancel() }
    }
    @Test fun changingSelectedConversationRevokesEvenAnUnchangedSharedWorkspace() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        try {
            val workspace = service.forChat("one"); service.bind("two", workspace.id)
            var selected = "one"
            val bridge = WorkspacePageBridge(service, null, "one") { selected }
            bridge.query("snapshot", "{}") {}
            selected = "two"
            assertFails { bridge.query("snapshot", "{}") {} }
            assertFails { bridge.perform(PageWorkspaceControl("createShell", """{"name":"bad"}""")) {} }
            assertEquals(1, service.state.value.shells.count { it.workspaceId == workspace.id })
        } finally { service.close(); scope.cancel() }
    }
    @Test fun wholePreviewChunksRemainVersionedAndBoundToTheCapturedConversation()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try {
            val workspace=service.forChat("preview-a");val other=service.forChat("preview-b")
            val content="a".repeat(4095)+"🙂"+"完整代码\n".repeat(4000)
            service.write(workspace.id,"code.kt",content);service.write(other.id,"private.txt","private")
            var active="preview-a"
            val bridge=WorkspacePageBridge(service,null,active,activeChat={active})
            fun args(offset:Int=0,hash:String?=null)=buildJsonObject {
                put("path","code.kt");put("offset",offset);put("expectedSha256",hash?.let(::JsonPrimitive)?:JsonNull)
            }.toString()
            val first=Json.parseToJsonElement(bridge.query("readChunk",args()) {}).jsonObject
            assertEquals(content.length,first["total"]!!.jsonPrimitive.int)
            assertEquals(4095,first["nextOffset"]!!.jsonPrimitive.int)
            val sha=first["sha256"]!!.jsonPrimitive.content
            val next=Json.parseToJsonElement(bridge.query("readChunk",args(4095,sha)) {}).jsonObject
            assertTrue(next["text"]!!.jsonPrimitive.content.startsWith("🙂"))
            assertFails { bridge.query("readChunk",args(4095)) {} }
            assertFails { bridge.query("readChunk","""{"path":"../${other.id}/private.txt","offset":0,"expectedSha256":null}""") {} }
            assertFails { bridge.query("readChunk","""{"path":"code.kt","offset":0,"expectedSha256":null,"workspaceId":"${other.id}"}""") {} }
            service.write(workspace.id,"code.kt","changed")
            assertFails { bridge.query("readChunk",args(4095,sha)) {} }
            active="preview-b";assertFails { bridge.query("readChunk",args()) {} }
            active="preview-a";service.bind(active,other.id);assertFails { bridge.query("readChunk",args()) {} }
        }finally { service.close();scope.cancel() }
    }

}
