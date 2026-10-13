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
    @Test fun stoppingAgentTurnClosesARepeatedlyUsedTerminal() = runBlocking {
        fixture { _, service, workspace, shell ->
            val turn = AgentExecutionContext()
            val session = withContext(turn) {
                val terminal = service.open(workspace.id, shell.id)
                service.input(workspace.id, terminal.id, "export AGENT_PTY=retained\n")
                service.input(workspace.id, terminal.id, "sleep 30\n")
                terminal
            }
            turn.cancel()
            assertFalse(service.status(workspace.id, session.id).active)
        }
    }
    @Test fun sharedWorkspaceStillFencesTerminalToChatAndHumanAuthority() = runBlocking {
        fixture { ws, service, workspace, shell ->
            val first = ws.state.value.bindings.entries.single { it.value == workspace.id }.key
            val second = "pty-other-${java.util.UUID.randomUUID()}"
            ws.bind(second, workspace.id)
            var allowed = true
            val authority = TerminalPageAuthority(TERMINAL_OWNER, first) { check(allowed) }
            val terminal = withContext(authority) { service.open(workspace.id, shell.id) }
            assertEquals(first, terminal.chatId)
            assertTrue(terminal.human)
            assertEquals(terminal.id, withContext(authority) { service.open(workspace.id, shell.id) }.id)
            withContext(authority) {
                service.resize(workspace.id, terminal.id, 37, 91)
                service.input(workspace.id, terminal.id, "stty -echo; stty size; printf '__SIZE_%s__\\n' OK\n")
            }
            val output = waitOutput(service, terminal.id, "__SIZE_OK__")
            assertTrue(output.output.contains("37 91"))
            assertEquals(37, output.rows); assertEquals(91, output.columns)
            val other = TerminalPageAuthority(TERMINAL_OWNER, second) {}
            try { withContext(other) { service.input(workspace.id, terminal.id, "echo foreign\n") }; fail("Another chat must not write") }
            catch (_: IllegalStateException) {}
            val separate = withContext(other) { service.open(workspace.id, shell.id) }
            assertNotEquals(terminal.id, separate.id)
            val descriptor = ToolDescriptor(name="test", description="test", inputSchema="{}", pluginId=TERMINAL_OWNER,
                capabilities=setOf("workspace","shell"), executionMode=ExecutionMode.APP)
            try {
                withContext(ConversationContext(first, workspace.id) + ToolInvocationContext(descriptor,
                    ToolCall("call", "status", JSONObject().put("id",terminal.id).toString()))) { service.status(workspace.id,terminal.id) }
                fail("Model tools must not reuse a human page grant")
            } catch (_: IllegalStateException) {}
            allowed = false
            try { withContext(authority) { service.input(workspace.id,terminal.id,"echo revoked\n") }; fail("Revoked page must not write") }
            catch (_: IllegalStateException) {}
            assertTrue(service.sessions.value.single { it.id == terminal.id }.active)
        }
    }

}
