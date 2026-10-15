package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.ScriptManifest
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
class WorkspaceResourcesBridgeTest {
    private val manifest = ScriptManifest("test.skill.resources", "Resources", "Test", "1.0.0", setOf("workspace"),
        emptySet(), emptyList(), apiVersion = 31, nativeServices = setOf("workspace.resources"))
    private val args = Json.parseToJsonElement("""{"name":"example","files":{"scripts/main.py":"print('ok')"}}""").jsonObject
    private inner class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actual = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        var valid = true
        var copies = 0
        var during: suspend () -> Unit = { }
        val service = object : WorkspaceService by actual {
            override suspend fun prepareResources(workspaceId: String, name: String, files: Map<String, String>,
                authorize: suspend () -> Unit): WorkspaceResourceBundle {
                authorize(); during(); authorize(); copies++
                return WorkspaceResourceBundle(".solvit-resource-example-uuid", files.size, 11, "a".repeat(64))
            }
        }
        val bridge = WorkspaceResourcesBridge(service) { check(valid) }
        suspend fun conversation() = ConversationContext("resources", actual.forChat("resources").id)
        fun invocation(json: String = """{"name":"example"}""", pluginId: String = manifest.id) =
            ToolInvocationContext(ToolDescriptor("prepare", "Prepare copy", "{}", pluginId = pluginId), ToolCall("one", "prepare", json))
        suspend fun close() { actual.close(); scope.cancel() }
    }
    @Test fun onlyApprovedNameCanPublishOnceAndNoExtraModelBodyOrDestinationIsAccepted() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val conversation = f.conversation()
            withContext(conversation + f.invocation("""{"name":"other"}""")) { assertFails { f.bridge.prepare(manifest, args) } }
            withContext(conversation + f.invocation(args.toString())) { assertFails { f.bridge.prepare(manifest, args) } }
            withContext(conversation + f.invocation(pluginId = "other.plugin")) { assertFails { f.bridge.prepare(manifest, args) } }
            assertEquals(0, f.copies)
            withContext(conversation + f.invocation()) {
                assertEquals(".solvit-resource-example-uuid", f.bridge.prepare(manifest, args).getValue("path").jsonPrimitive.content)
                assertFails { f.bridge.prepare(manifest, args) }
            }
            assertEquals(1, f.copies)
        } finally { f.close() }
    }
    @Test fun noConversationNoToolDeniedRoleOrUndeclaredServiceCannotPublish() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val conversation = f.conversation()
            assertFails { f.bridge.prepare(manifest, args) }
            withContext(f.invocation()) { assertFails { f.bridge.prepare(manifest, args) } }
            withContext(conversation + f.invocation() + AgentRoleContext(AgentRole("denied", "Denied", pluginIds = emptySet()))) {
                assertFails { f.bridge.prepare(manifest, args) }
            }
            withContext(conversation + f.invocation()) {
                assertFails { f.bridge.prepare(manifest.copy(apiVersion = 30), args) }
                assertFails { f.bridge.prepare(manifest.copy(nativeServices = emptySet()), args) }
            }
            assertEquals(0, f.copies)
        } finally { f.close() }
    }
    @Test fun permissionRevocationAndChatRebindingAreRecheckedDuringPublication() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val conversation = f.conversation()
            f.during = { f.valid = false }
            withContext(conversation + f.invocation()) { assertFails { f.bridge.prepare(manifest, args) } }
            f.valid = true
            val other = f.actual.create("other")
            f.during = { f.actual.bind(conversation.sessionId, other.id) }
            withContext(conversation + f.invocation()) { assertFails { f.bridge.prepare(manifest, args) } }
            assertEquals(0, f.copies)
        } finally { f.close() }
    }
}
