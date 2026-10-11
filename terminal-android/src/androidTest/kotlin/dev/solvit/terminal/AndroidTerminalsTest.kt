package dev.solvit.terminal

import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.workspace.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AndroidTerminalsTest {
    private suspend fun fixture(block: suspend (AndroidWorkspaces, AndroidTerminals, Workspace, ShellSession) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope, rootAccess = object : RootAccess {
            override fun detected() = true
            override suspend fun granted() = true // Tests the gate, never executes a real su process.
        })
        val service = AndroidTerminals(scope, workspaces, workspaces.execution)
        val remove = workspaces.execution.onIdentityInvalidated { service.cancelOwner(it) }
        try {
            workspaces.execution.setMode(TERMINAL_OWNER, ExecutionMode.APP)
            val workspace = workspaces.forChat("pty-test-${java.util.UUID.randomUUID()}")
            val shell = workspaces.state.value.shells.first { it.workspaceId == workspace.id }
            block(workspaces, service, workspace, shell)
        } finally { remove(); service.shutdown(); workspaces.close(); scope.cancel() }
    }
    private suspend fun waitOutput(service: TerminalService, id: String, marker: String) =
        withTimeout(8000) { service.sessions.first { list -> list.any { it.id == id && marker in it.output } }.single { it.id == id } }
    @Test fun liveServicePreservesStateAndSupportsInteractiveAnswers() = runBlocking {
        fixture { _, service, workspace, shell ->
            val session = service.open(workspace.id, shell.id)
            assertEquals(session.id, service.open(workspace.id, shell.id).id)
            service.input(workspace.id, session.id, "stty -echo; export SOLVIT_STATE=保持; printf '__READY_%s__\\n' OK\n")
            waitOutput(service, session.id, "__READY_OK__")
            service.input(workspace.id, session.id, "read answer; printf 'result:%s:%s\\n' \"\$SOLVIT_STATE\" \"\$answer\"\n")
            service.input(workspace.id, session.id, "输入\n")
            assertTrue(waitOutput(service, session.id, "result:保持:输入").active)
            service.close(workspace.id, session.id)
            assertFalse(service.status(workspace.id, session.id).active)
        }
    }
    @Test fun pluginOwnershipAndApprovedInputAreEnforced() = runBlocking {
        fixture { ws, service, workspace, shell ->
            suspend fun <T> invoke(owner: String, operation: String, args: JSONObject, block: suspend () -> T): T {
                val descriptor = ToolDescriptor(name="test",description="test",inputSchema="{}",pluginId=owner,
                    capabilities=setOf("workspace","shell"),executionMode=ExecutionMode.APP)
                val chat = ws.state.value.bindings.entries.single { it.value == workspace.id }.key
                return withContext(ConversationContext(chat, workspace.id) + ToolInvocationContext(descriptor, ToolCall("call", operation, args.toString()))) { block() }
            }
            val owner = "test.pty.owner"
            ws.execution.setMode(owner, ExecutionMode.APP)
            val terminal = invoke(owner,"open",JSONObject().put("shellId",shell.id)) { service.open(workspace.id,shell.id) }
            try {
                invoke("test.pty.other","input",JSONObject().put("id",terminal.id).put("text","echo bad\n")) { service.input(workspace.id,terminal.id,"echo bad\n") }
                fail("其他插件不应写入此终端")
            } catch (_: IllegalStateException) { }
            try {
                invoke(owner,"input",JSONObject().put("id",terminal.id).put("text","echo approved\n")) { service.input(workspace.id,terminal.id,"echo changed\n") }
                fail("不应执行被替换的输入")
            } catch (_: IllegalStateException) { }
            service.cancelOwner(owner)
            assertFalse(service.sessions.value.single { it.id == terminal.id }.active)
        }
    }
    @Test fun identityChangeClosesPtyAndRootCannotSilentlyFallBackToApp() = runBlocking {
        fixture { ws, service, workspace, shell ->
            val session = service.open(workspace.id, shell.id)
            ws.execution.setMode(TERMINAL_OWNER, ExecutionMode.ROOT)
            assertFalse(service.status(workspace.id, session.id).active)
            try { service.open(workspace.id, shell.id); fail("Root PTY 尚未提供，不应降级") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains("Root")) }
            assertTrue(service.sessions.value.none { it.active })
            ws.execution.setMode(TERMINAL_OWNER, ExecutionMode.APP)
            assertTrue(service.open(workspace.id, shell.id).active)
        }
    }
}
