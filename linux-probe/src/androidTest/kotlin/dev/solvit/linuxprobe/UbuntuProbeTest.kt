package dev.solvit.linuxprobe

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.linux.LinuxLaunch
import dev.solvit.linux.RootfsInstallation
import dev.solvit.linux.RootfsDownload
import dev.solvit.linux.UbuntuCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Android userland installation and package execution, not a desktop simulation. */
@RunWith(AndroidJUnit4::class)
class UbuntuProbeTest {
    @Test fun productionDownloaderAndManagedJobsInstallUsableSharedEnvironment(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Process.myUid() >= 10_000)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = dev.solvit.environment.AndroidLinuxEnvironment(context, scope)
        try {
            assertTrue("APK must provide the emulator ABI runtime", service.state.value.supported)
            service.install()
            val result = withTimeout(12 * 60_000L) {
                service.state.first { it.stage in setOf("READY", "FAILED", "CANCELLED") }
            }
            assertEquals("${result.failure}\n${result.output}", "READY", result.stage)
            assertTrue(result.installed)
            service.installSoftware(dev.solvit.core.LinuxSoftware.JAVA)
            assertEquals("INSTALLING_SOFTWARE", service.state.value.stage)
            val javaResult = withTimeout(10 * 60_000L) { service.state.first { it.stage in setOf("READY", "FAILED", "CANCELLED") } }
            assertEquals("${javaResult.failure}\n${javaResult.output}", "READY", javaResult.stage)
            assertTrue(javaResult.output, javaResult.output.contains("openjdk version \"17."))
            android.util.Log.i("SolvitLinuxProbe", "JAVA_RUNTIME_VERIFIED|" + javaResult.output.lines().first { it.contains("openjdk version") })
            val workspace = File(context.filesDir, "production shared workspace").apply { mkdirs() }
            val plan = service.command(workspace.absolutePath,
                "python3 -c \"from pathlib import Path; Path('production-python.txt').write_text('python-ok')\" && node -e \"require('fs').writeFileSync('production-node.txt','node-ok')\"")
            val log = File(context.cacheDir, "production-check.log")
            val child = ProcessBuilder(plan.arguments).apply {
                plan.environment.forEach { (key, value) -> if (value == null) environment().remove(key) else environment()[key] = value }
                redirectErrorStream(true); redirectOutput(log)
            }.start()
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS))
                assertEquals(log.readText(), 0, child.exitValue())
                assertEquals("python-ok", File(workspace, "production-python.txt").readText())
                assertEquals("node-ok", File(workspace, "production-node.txt").readText())
            } finally { child.destroyForcibly() }
            val terminal = service.terminal(workspace.absolutePath)
            dev.solvit.terminal.PtyConnection.open(workspace, command = terminal.arguments, environment = terminal.environment).use { pty ->
                fun readUntil(marker: String): String {
                    val output = java.io.ByteArrayOutputStream()
                    val deadline = System.nanoTime() + 15_000_000_000L
                    while (System.nanoTime() < deadline) {
                        val bytes = pty.read() ?: break
                        output.write(bytes)
                        val text = output.toString("UTF-8")
                        if (marker in text) return text
                    }
                    error("Ubuntu PTY 未收到 $marker：${output.toString("UTF-8")}")
                }
                pty.write("stty -echo; printf '__UBUNTU_%s__\\n' READY\n".toByteArray())
                readUntil("__UBUNTU_READY__")
                pty.write("[ -t 0 ] && [ -t 1 ] && python3 -c \"from pathlib import Path; Path('pty-python.txt').write_text('pty-ok')\" && node --version && printf '__UBUNTU_%s__\\n' DONE\n".toByteArray())
                val output = readUntil("__UBUNTU_DONE__")
                assertTrue(output, output.contains("v18."))
                assertEquals("pty-ok", File(workspace, "pty-python.txt").readText())
            }
            // Exercise the same post-authorization native path used by AI Shell tools.
            val workspaces = dev.solvit.workspace.AndroidWorkspaces(context, scope, linux = service)
            try {
                val project = workspaces.create("Linux agent workspace")
                val shell = workspaces.createShell(project.id, "Ubuntu agent", dev.solvit.core.ShellRuntime.UBUNTU)
                val chat = "linux-authorized-turn"
                workspaces.bind(chat, project.id)
                val command = "python3 -c \"from pathlib import Path; Path('agent-python.txt').write_text('agent-ok')\" && node --version && java -version"
                val args = org.json.JSONObject().put("shellId", shell.id).put("command", command).toString()
                val descriptor = dev.solvit.core.ToolDescriptor("shell_start", "Approved Ubuntu command", "{}",
                    pluginId = "test.linux.agent", capabilities = setOf("shell"), executionMode = dev.solvit.core.ExecutionMode.APP)
                val invocation = dev.solvit.core.ToolInvocationContext(descriptor,
                    dev.solvit.core.ToolCall("linux-call", descriptor.name, args))
                val started = withContext(invocation + dev.solvit.core.ConversationContext(chat, project.id)) {
                    workspaces.run(project.id, shell.id, command)
                }
                val completed = withTimeout(30_000) { workspaces.jobs.first { jobs ->
                    jobs.any { it.id == started.id && it.stage !in setOf(dev.solvit.core.ShellStage.STARTING, dev.solvit.core.ShellStage.RUNNING) }
                }.first { it.id == started.id } }
                assertEquals(completed.output.joinToString("") { it.text }, dev.solvit.core.ShellStage.COMPLETED, completed.stage)
                assertEquals(project.id, completed.workspaceId); assertEquals(shell.id, completed.shellSessionId)
                assertEquals("agent-ok", workspaces.read(project.id, "agent-python.txt"))
                invocation.cancelResources()
                val restored = dev.solvit.workspace.AndroidWorkspaces(context, scope, linux = service)
                try { assertEquals(dev.solvit.core.ShellRuntime.UBUNTU, restored.state.value.shells.first { it.id == shell.id }.runtime) }
                finally { restored.close() }
            } finally { workspaces.close() }
        } finally { service.close(); scope.cancel() }
    }

    @Test fun ubuntuInstallsPythonAndNodeAndUsesSharedWorkspaceWithoutRoot(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Process.myUid() >= 10_000)
        val installer = RootfsInstallation(File(context.filesDir, "environments"),
            UbuntuCatalog.forAbi("x86_64")!!, RootfsDownload { _, destination, progress ->
                context.assets.open("ubuntu-x86_64.tar.xz").use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var bytes = 0L
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count); bytes += count; progress(bytes, null)
                        }
                    }
                }
            })
        val workspace = File(context.filesDir,"ubuntu workspace").apply { mkdirs() }
        File(workspace,"from-host.txt").writeText("shared-workspace")
        val native = File(context.applicationInfo.nativeLibraryDir)
        val log = File(context.filesDir,"ubuntu-probe.log")
        val installed = installer.install { root ->
            File(root,"etc/resolv.conf").writeText("nameserver 10.0.2.3\n")
            val plan = LinuxLaunch(native, context.cacheDir).command(root, workspace,
                """
                    test "$(cat from-host.txt)" = shared-workspace
                    . /etc/os-release
                    test "${'$'}ID" = ubuntu
                    test "${'$'}VERSION_ID" = 24.04
                    apt-get -o Acquire::Retries=2 update
                    apt-get -o Acquire::Retries=2 install -y --no-install-recommends python3 python3-venv nodejs npm
                    python3 --version > versions.txt
                    node --version >> versions.txt
                    npm --version >> versions.txt
                    python3 -c "from pathlib import Path; Path('python-result.txt').write_text('python-ok')"
                    node -e "require('fs').writeFileSync('node-result.txt','node-ok')"
                    python3 -m venv .venv
                    .venv/bin/python -c "print('venv-ok')"
                    cat versions.txt
                """.trimIndent())
            val child = plan.processBuilder().apply {
                redirectErrorStream(true); redirectOutput(log)
            }.start()
            try {
                val finished = child.waitFor(8,TimeUnit.MINUTES)
                assertTrue("Ubuntu package install timed out: ${log.readText().takeLast(12000)}", finished)
                assertEquals(log.readText().takeLast(12000),0,child.exitValue())
                assertEquals("python-ok",File(workspace,"python-result.txt").readText())
                assertEquals("node-ok",File(workspace,"node-result.txt").readText())
                val versions = File(workspace,"versions.txt").readText()
                assertTrue(versions,versions.startsWith("Python 3."))
                assertTrue(versions,versions.lines().any { it.startsWith("v") })
                android.util.Log.i("SolvitLinuxProbe", "RUNTIME_VERSIONS|" + versions.trim().replace("\n", "|"))
            } finally { child.destroyForcibly() }
        }
        assertEquals(installed, installer.activeRoot())
        assertEquals(dev.solvit.linux.InstallationStage.READY, installer.state.value.stage)
    }
}
