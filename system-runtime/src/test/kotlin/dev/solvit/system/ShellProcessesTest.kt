package dev.solvit.system

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class ShellProcessesTest {
    @Test fun trustedRuntimePlanPreservesArgumentsEnvironmentAndProcessGroupCancellation() = runBlocking<Unit> { testEngine { engine, workspace ->
        val plan = HostProcessPlan(listOf("/bin/sh", "-c", "printf '%s' \"\$SOLVIT_RUNTIME_TEST\"; printf '%s' \"\$1\"", "guest", "quote ' and 空格"),
            mapOf("SOLVIT_RUNTIME_TEST" to "runtime:", "PROOT_NO_SECCOMP" to null))
        val job = engine.start("test.shell", null, ShellRequest("must not execute this host command"), ExecutionMode.APP, plan = plan)
        val result = finished(engine, job.id)
        assertEquals(ShellStage.COMPLETED, result.stage)
        assertEquals("runtime:quote ' and 空格", result.output.joinToString("") { it.text })
        val waiting = engine.start("test.shell", null, ShellRequest("guest wait"), ExecutionMode.APP,
            plan = HostProcessPlan(listOf("/bin/sh", "-c", "(sleep 2; printf bad > guest-cancelled.txt) & wait"), emptyMap()))
        withTimeout(5_000) { engine.jobs.first { jobs -> jobs.any { it.id == waiting.id && it.stage == ShellStage.RUNNING } } }
        engine.cancelOwner("test.shell")
        delay(2_500)
        assertFalse(File(workspace, "guest-cancelled.txt").exists())
        assertFailsWith<IllegalStateException> {
            ShellLauncher(ShellPaths("/bin/sh", "/usr/bin/setsid")).launch(ShellRequest("pwd"), ExecutionMode.ROOT, workspace, workspace, plan)
        }
    } }
    private suspend fun testEngine(block: suspend (ShellJobEngine, File) -> Unit) {
        val workspace = Files.createTempDirectory("solvit-shell-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ShellJobEngine(scope, ShellLauncher(ShellPaths("/bin/sh", "/usr/bin/setsid")), workspace, outputLimit = 8192)
        try { block(engine, workspace) } finally { engine.close(); scope.cancel(); workspace.deleteRecursively() }
    }
    private suspend fun finished(engine: ShellJobEngine, id: String) = withTimeout(8_000) {
        engine.jobs.first { jobs -> jobs.any { it.id == id && it.stage !in setOf(ShellStage.STARTING, ShellStage.RUNNING) } }.first { it.id == id }
    }
    @Test fun streamsBothOutputsBeforeCompletionAndKeepsUtf8AcrossReads() = runBlocking<Unit> { testEngine { engine, _ ->
        val job = engine.start("test.shell", "role", ShellRequest("printf '中文 😀'; printf 'error' >&2; sleep 0.3; printf 'done'"), ExecutionMode.APP)
        withTimeout(5_000) { engine.jobs.first { list -> list.any { it.id == job.id && it.stage == ShellStage.RUNNING && it.output.isNotEmpty() } } }
        val result = finished(engine, job.id)
        assertEquals(ShellStage.COMPLETED, result.stage)
        assertEquals("中文 😀done", result.output.filter { it.stream == "stdout" }.joinToString("") { it.text })
        assertEquals("error", result.output.filter { it.stream == "stderr" }.joinToString("") { it.text })
        assertEquals(0, result.exitCode)
        assertTrue(engine.read("test.shell", "role", job.id, result.nextSequence - 1).output.isEmpty())
        assertFailsWith<IllegalStateException> { engine.read("other.plugin", "role", job.id, -1) }
        assertFailsWith<IllegalStateException> { engine.read("test.shell", "other-role", job.id, -1) }
    } }
    @Test fun cancellationAndTimeoutStopTheProcessGroupBeforeDelayedSideEffects() = runBlocking<Unit> { testEngine { engine, workspace ->
        val cancelled = engine.start("test.shell", null, ShellRequest("(sleep 2; printf bad > cancelled.txt) & wait"), ExecutionMode.APP)
        withTimeout(5_000) { engine.jobs.first { list -> list.any { it.id == cancelled.id && it.stage == ShellStage.RUNNING } } }
        engine.cancelOwner("test.shell")
        assertEquals(ShellStage.CANCELLED, finished(engine, cancelled.id).stage)
        val timeout = engine.start("test.shell", null, ShellRequest("(sleep 3; printf bad > timed-out.txt) & wait", timeoutMs = 1000), ExecutionMode.APP)
        assertEquals(ShellStage.TIMED_OUT, finished(engine, timeout.id).stage)
        delay(3_000)
        assertFalse(File(workspace, "cancelled.txt").exists())
        assertFalse(File(workspace, "timed-out.txt").exists())
    } }
    @Test fun outputStaysBoundedAndNonzeroExitRetainsDiagnostics() = runBlocking<Unit> { testEngine { engine, _ ->
        val job = engine.start("test.shell", null, ShellRequest("i=0; while [ \"\$i\" -lt 2000 ]; do printf 'long-output-line'; i=\$((i+1)); done; printf tail; exit 7"), ExecutionMode.APP)
        val result = finished(engine, job.id)
        assertEquals(ShellStage.FAILED, result.stage)
        assertEquals(7, result.exitCode)
        assertTrue(result.truncated)
        assertTrue(result.output.sumOf { it.text.toByteArray().size } <= 8192)
        assertTrue(result.output.joinToString("") { it.text }.endsWith("tail"))
    } }
    @Test fun appWorkingDirectoryCannotEscapeWorkspaceAndQuotingPreservesArguments() = runBlocking<Unit> { testEngine { engine, workspace ->
        assertFailsWith<IllegalArgumentException> { engine.start("test.shell", null, ShellRequest("id", cwd = ".."), ExecutionMode.APP) }
        val directory = File(workspace, "quote ' and 空格").apply { mkdirs() }
        val job = engine.start("test.shell", null, ShellRequest("pwd", cwd = directory.path), ExecutionMode.APP)
        assertEquals(directory.path, finished(engine, job.id).output.joinToString("") { it.text }.trim())
    } }
}
