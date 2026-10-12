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
}
