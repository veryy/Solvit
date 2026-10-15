package dev.solvit.terminal

import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AndroidStdioProcessesTest {
    private val directory get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val owner = StdioOwner("test.local.mcp", "chat-role-workspace", "fixture", "config-v1")
    private fun plan(script: String) = HostProcessPlan(listOf("/system/bin/sh", "-c", script), emptyMap())
    private suspend fun fixture(action: suspend (AndroidStdioProcesses) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidStdioProcesses(scope)
        try { action(service) } finally { service.shutdown(); scope.cancel() }
    }
    private suspend fun stopped(pid: Int) = withTimeout(5_000) {
        while (File("/proc/$pid/stat").exists()) delay(10)
    }
    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("Operation was unexpectedly accepted") }
        catch (error: AssertionError) { throw error }
        catch (_: Exception) { }
    }
    @Test fun sameIdentityReusesButOtherChatCannotSendReceiveOrCloseIt() = runBlocking<Unit> { fixture { service ->
        val plan = plan("while IFS= read -r line; do printf '%s\\n' \"${'$'}line\"; done")
        val id = service.open(owner, plan, directory.path) {}
        assertEquals(id, service.open(owner, plan, directory.path) {})
        val other = owner.copy(scope = "another-chat")
        rejected { service.send(other, id, "forged") {} }
        rejected { service.receive(other, id) {} }
        rejected { service.close(other, id) }
        service.send(owner, id, "{\"text\":\"中文🌌\"}") {}
        assertEquals("{\"text\":\"中文🌌\"}", service.receive(owner, id) {})
        service.close(owner, id); service.close(owner, id)
    } }
    @Test fun changingConfigurationReapsOldGenerationBeforeStartingAnother() = runBlocking<Unit> { fixture { service ->
        val script = plan("printf '%s\\n' \"${'$'}${'$'}\"; while IFS= read -r line; do :; done")
        val id = service.open(owner, script, directory.path) {}
        val pid = service.receive(owner, id) {}.toInt()
        val changed = owner.copy(configuration = "config-v2")
        val next = service.open(changed, script, directory.path) {}
        assertNotEquals(id, next); stopped(pid)
        assertNotEquals(pid, service.receive(changed, next) {}.toInt())
        rejected { service.send(owner, id, "old") {} }
    } }
    @Test fun permissionRevocationAndCancelledReceiveStopTheActualWorker() = runBlocking<Unit> { fixture { service ->
        val allowed = AtomicBoolean(true)
        val id = service.open(owner, plan("printf '%s\\n' \"${'$'}${'$'}\"; /system/bin/sleep 30"), directory.path) { check(allowed.get()) }
        val pid = service.receive(owner, id) {}.toInt()
        allowed.set(false)
        stopped(pid)
        rejected { service.send(owner, id, "revoked") {} }
        val next = service.open(owner, plan("printf '%s\\n' \"${'$'}${'$'}\"; /system/bin/sleep 30"), directory.path) {}
        val waitingPid = service.receive(owner, next) {}.toInt()
        val waiter = launch { service.receive(owner, next) {} }
        delay(50); waiter.cancelAndJoin(); stopped(waitingPid)
    } }
    @Test fun outputFloodCannotBlockCleanupOrKeepUnboundedQueuedFrames() = runBlocking<Unit> { fixture { service ->
        val id = service.open(owner, plan("printf '%s\\n' \"${'$'}${'$'}\"; IFS= read -r ready; while :; do printf '0123456789012345678901234567890123456789012345678901234567890123456789\\n'; done"), directory.path) {}
        val pid = service.receive(owner, id) {}.toInt()
        service.send(owner, id, "start") {}
        stopped(pid)
        service.cancelOwner(owner.pluginId)
        rejected { service.receive(owner, id) {} }
    } }
    @Test fun idleTimeoutAndOwnerCancellationRetireConnectionsIndependently() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = AndroidStdioProcesses(scope, idleMillis = 100)
        try {
            val id = service.open(owner, plan("printf '%s\\n' \"${'$'}${'$'}\"; /system/bin/sleep 30"), directory.path) {}
            val pid = service.receive(owner, id) {}.toInt(); stopped(pid)
            rejected { service.receive(owner, id) {} }
        } finally { service.shutdown(); scope.cancel() }
        fixture { managed ->
            val script = plan("printf '%s\\n' \"${'$'}${'$'}\"; /system/bin/sleep 30")
            val first = managed.open(owner, script, directory.path) {}
            val other = owner.copy(pluginId = "test.other.mcp")
            val second = managed.open(other, script, directory.path) {}
            val pid = managed.receive(owner, first) {}.toInt()
            val otherPid = managed.receive(other, second) {}.toInt()
            managed.cancelOwner(owner.pluginId); stopped(pid)
            assertTrue(File("/proc/$otherPid/stat").exists())
            managed.close(other, second); stopped(otherPid)
        }
    }
}
