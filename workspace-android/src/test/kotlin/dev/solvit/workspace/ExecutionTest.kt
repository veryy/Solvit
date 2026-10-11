package dev.solvit.workspace

import dev.solvit.core.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExecutionTest {
    private class Root(var grant: Boolean = false) : RootAccess {
        var requests = 0
        override fun detected() = true
        override suspend fun granted(): Boolean { requests++; return grant }
    }
    private suspend fun fixture(block: suspend (AndroidWorkspaces, Root) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val root = Root()
        val workspace = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope,
            ShellLauncher(ShellPaths("/bin/sh", "/usr/bin/setsid")), root)
        try { block(workspace, root) } finally { workspace.close(); scope.cancel() }
    }
    @Test fun detectionNeverRequestsRootAndDeniedRootCannotBeSelected() = runBlocking<Unit> {
        fixture { ws, root ->
            ws.execution.refresh()
            assertEquals(0, root.requests)
            assertFalse(ws.execution.device.value.rootGranted)
            assertFailsWith<IllegalStateException> { ws.execution.setMode(TERMINAL_OWNER, ExecutionMode.ROOT) }
            assertNull(ws.execution.modes.value[TERMINAL_OWNER])
            assertTrue(ws.jobs.value.isEmpty())
        }
    }
    @Test fun rootSelectionPersistsButRestartDoesNotAssumeGrant() = runBlocking<Unit> {
        fixture { ws, root ->
            root.grant = true
            ws.execution.setMode("example.shell", ExecutionMode.ROOT)
            assertTrue(ws.execution.device.value.rootGranted)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val restored = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope, rootAccess = root)
            try {
                assertEquals(ExecutionMode.ROOT, restored.execution.modes.value["example.shell"])
                assertFalse(restored.execution.device.value.rootGranted)
                root.grant = false
                assertFailsWith<IllegalStateException> { restored.execution.start("example.shell", ShellRequest("id"), false) }
                assertTrue(restored.jobs.value.isEmpty())
            } finally { restored.close(); scope.cancel() }
        }
    }
    @Test fun modeChangeStopsExistingProcessAndInvalidatesApprovedToolIdentity() = runBlocking<Unit> {
        fixture { ws, root ->
            val workspace = ws.forChat("chat")
            val shell = ws.state.value.shells.single { it.workspaceId == workspace.id }
            val job = ws.run(workspace.id, shell.id, "sleep 30")
            withTimeout(5000) { ws.jobs.first { it.any { j -> j.id == job.id && j.stage == ShellStage.RUNNING } } }
            root.grant = true
            ws.execution.setMode(TERMINAL_OWNER, ExecutionMode.ROOT)
            assertEquals(ShellStage.CANCELLED, ws.jobs.value.single { it.id == job.id }.stage)
            ws.execution.setMode("example.shell", ExecutionMode.ROOT)
            val descriptor = ToolDescriptor("run", "Run", "{}", pluginId = "example.shell", capabilities = setOf("shell"), executionMode = ExecutionMode.ROOT)
            ws.execution.setMode("example.shell", ExecutionMode.APP)
            withContext(ToolInvocationContext(descriptor, ToolCall("one", "run", """{"shellId":"${shell.id}","command":"id"}""")) + ConversationContext("chat", workspace.id)) {
                assertFailsWith<IllegalStateException> { ws.run(workspace.id, shell.id, "id") }
            }
            assertEquals(1, ws.jobs.value.size)
        }
    }
    @Test fun rootRevokedBeforeExecutionCannotFallBackToApp() = runBlocking<Unit> {
        fixture { ws, root ->
            root.grant = true; ws.execution.setMode(TERMINAL_OWNER, ExecutionMode.ROOT)
            val workspace = ws.forChat("chat")
            val shell = ws.state.value.shells.single { it.workspaceId == workspace.id }
            root.grant = false
            assertFailsWith<IllegalStateException> { ws.run(workspace.id, shell.id, "id") }
            assertTrue(ws.jobs.value.isEmpty())
            assertEquals(ExecutionMode.ROOT, ws.execution.modes.value[TERMINAL_OWNER])
            assertFalse(ws.execution.device.value.rootGranted)
        }
    }
}
