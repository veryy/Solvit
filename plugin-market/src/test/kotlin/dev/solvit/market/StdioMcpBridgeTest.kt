package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.*
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StdioMcpBridgeTest {
    private val manifest = ScriptManifest("test.local.mcp", "MCP", "Test", "1.0.0", setOf("network", "shell", "workspace"),
        emptySet(), emptyList(), apiVersion = 30, nativeServices = setOf("process.stdio", "environment.linux"))
    private class Pipes : StdioProcessService {
        var starts = 0; var frames = 0; var closed = 0
        var recheck: (suspend () -> Unit)? = null
        override suspend fun open(owner: StdioOwner, plan: HostProcessPlan, directory: String, authorize: suspend () -> Unit): String {
            authorize(); recheck = authorize; starts++; return UUID.randomUUID().toString()
        }
        override suspend fun send(owner: StdioOwner, id: String, frame: String, authorize: suspend () -> Unit) { authorize(); frames++ }
        override suspend fun receive(owner: StdioOwner, id: String, authorize: suspend () -> Unit): String { authorize(); return "{}" }
        override suspend fun close(owner: StdioOwner, id: String) { closed++ }
        override suspend fun cancelOwner(pluginId: String) { }
    }
    private class Linux : LinuxEnvironmentService {
        override val state = MutableStateFlow(LinuxEnvironmentState(true, true, "ready"))
        var launches = 0
        override suspend fun install() = error("Not used")
        override suspend fun installSoftware(software: LinuxSoftware) = error("Not used")
        override suspend fun cancel() { }
        override fun command(workspaceDirectory: String, command: String, cwd: String) = error("Not used")
        override fun terminal(workspaceDirectory: String) = error("Not used")
        override fun process(workspaceDirectory: String, executable: String, arguments: List<String>, environment: Map<String, String>, cwd: String): HostProcessPlan {
            assertEquals("python3", executable); assertEquals(listOf("server.py"), arguments)
            assertEquals(mapOf("TOKEN" to "secret-not-in-results"), environment)
            launches++; return HostProcessPlan(listOf("fixture"), emptyMap())
        }
    }
    private inner class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        val pipes = Pipes(); val linux = Linux()
        var valid = true
        var encoded = """[{"name":"local","type":"stdio","command":"python3","args":["server.py"],"env":{"TOKEN":"secret-not-in-results"}}]"""
        val bridge = StdioMcpBridge(pipes, linux, workspaces, null, { encoded }, { check(valid) })
        suspend fun conversation(chat: String): ConversationContext = ConversationContext(chat, workspaces.forChat(chat).id)
        fun invocation(args: String = """{"server":"local","name":"write","arguments":{"path":"ok"}}""") =
            ToolInvocationContext(ToolDescriptor("mcp_call", "MCP", "{}", pluginId = manifest.id), ToolCall("one", "mcp_call", args))
        suspend fun close() { workspaces.close(); scope.cancel() }
    }
    private fun args(id: String? = null, frame: JsonObject? = null) = buildJsonObject {
        put("server", "local"); id?.let { put("id", it) }; frame?.let { put("frame", it) }
    }
    @Test fun savedCommandsCannotBeOverriddenAndOnlyApprovedToolArgumentsCanBeSent() = runBlocking<Unit> {
        val f = Fixture()
        try {
            withContext(f.conversation("tool") + f.invocation()) {
                assertFails { f.bridge.call(manifest, "stdio.open", buildJsonObject { put("server", "local"); put("command", "sh") }) }
                assertEquals(0, f.pipes.starts)
                val id = f.bridge.call(manifest, "stdio.open", args()).jsonPrimitive.content
                val frame = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"write","arguments":{"path":"other"}}}""").jsonObject
                assertFails { f.bridge.call(manifest, "stdio.send", args(id, frame)) }; assertEquals(0, f.pipes.frames)
                val approved = JsonObject(frame + ("params" to Json.parseToJsonElement("""{"name":"write","arguments":{"path":"ok"}}""")))
                f.bridge.call(manifest, "stdio.send", args(id, approved)); assertEquals(1, f.pipes.frames)
                assertFails { f.bridge.call(manifest, "stdio.send", args(id, approved)) }; assertEquals(1, f.pipes.frames)
            }
            assertEquals(1, f.linux.launches)
        } finally { f.close() }
    }
    @Test fun scopeBindsConversationAndLiveAuthorityRejectsConfigurationRevocationAndRebind() = runBlocking<Unit> {
        val f = Fixture()
        try {
            val a = f.conversation("a"); val b = f.conversation("b")
            suspend fun scope(c: ConversationContext) = withContext(c + f.invocation()) { f.bridge.call(manifest, "stdio.scope", buildJsonObject {}) }
            assertNotEquals(scope(a), scope(b))
            withContext(a + f.invocation()) {
                f.bridge.call(manifest, "stdio.open", args())
                f.valid = false; assertFails { f.pipes.recheck!!() }; f.valid = true
                val saved = f.encoded; f.encoded = saved.replace("server.py", "changed.py")
                assertFails { f.pipes.recheck!!() }; f.encoded = saved
                f.workspaces.bind("a", b.workspaceId); assertFails { f.pipes.recheck!!() }
            }
        } finally { f.close() }
    }
    @Test fun humanStartNeedsCurrentSingleUseReviewAndPageResourcesClose() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.conversation("page")
            val access = WorkspacePageBridge(f.workspaces, null, "page")
            val initial = ScriptPageExecutionContext(manifest.id, {}, workspace = access)
            val review = withContext(initial) { f.bridge.call(manifest, "stdio.review", args()).jsonObject }
            assertFalse(review.toString().contains("secret-not-in-results"))
            assertFalse(review.toString().contains("server.py"))
            assertFails { withContext(initial) { f.bridge.call(manifest, "stdio.open", args()) } }
            val action = buildJsonObject { put("kind", "stdio.connect"); put("server", "local"); put("reviewId", review.getValue("reviewId")) }
            val approved = ScriptPageExecutionContext(manifest.id, {}, action, workspace = access, stdioScope = initial.stdioScope)
            withContext(approved) { f.bridge.call(manifest, "stdio.open", args()); assertFails { f.bridge.call(manifest, "stdio.open", args()) } }
            approved.closeStdioResources(); assertEquals(1, f.pipes.closed)
            val second = withContext(initial) { f.bridge.call(manifest, "stdio.review", args()).jsonObject }
            val changedAction = JsonObject(action + ("reviewId" to second.getValue("reviewId")))
            f.encoded = f.encoded.replace("server.py", "changed.py")
            assertFails { withContext(ScriptPageExecutionContext(manifest.id, {}, changedAction, workspace = access, stdioScope = initial.stdioScope)) {
                f.bridge.call(manifest, "stdio.open", args())
            } }
            assertEquals(1, f.pipes.starts)
        } finally { f.close() }
    }
}
