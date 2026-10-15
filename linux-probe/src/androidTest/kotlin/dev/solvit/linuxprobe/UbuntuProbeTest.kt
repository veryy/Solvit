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
                "python3 -c \"from pathlib import Path; Path('production-python.txt').write_text('python-ok')\" && node -e \"require('fs').writeFileSync('production-node.txt','node-ok')\" && python3 --version && node --version && npm --version")
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
                val versions = log.readText().trim()
                assertTrue(versions, versions.lines().contains("v${dev.solvit.linux.NodeCatalog.VERSION}"))
                android.util.Log.i("SolvitLinuxProbe", "PRODUCTION_RUNTIME_VERSIONS|" + versions.replace("\n", "|"))
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
                assertTrue(output, output.contains("v${dev.solvit.linux.NodeCatalog.VERSION}"))
                assertEquals("pty-ok", File(workspace, "pty-python.txt").readText())
            }
            verifyNativeStdioMcp(service, workspace)
            verifyLocalMcpAgent(context, scope, service)
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

    /** Protocol fixtures in the actual production Ubuntu, not a desktop Node substitute. */
    private fun verifyNativeStdioMcp(service: dev.solvit.core.LinuxEnvironmentService, workspace: File) {
        val python = """
            import sys, json, os
            from pathlib import Path
            print('stdio-fixture-diagnostic', file=sys.stderr, flush=True)
            for raw in sys.stdin.buffer:
                request = json.loads(raw)
                if 'id' not in request:
                    continue
                method = request['method']
                if method == 'initialize':
                    result = {'protocolVersion': request['params']['protocolVersion'], 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'python-fixture', 'version': '1'}}
                elif method == 'tools/list':
                    result = {'tools': [{'name': 'write_fixture', 'inputSchema': {'type': 'object', 'properties': {'text': {'type': 'string'}}, 'required': ['text']}}]}
                elif method == 'tools/call':
                    value = request['params']['arguments']['text']
                    Path('stdio-python-result.txt').write_text(value)
                    result = {'content': [{'type': 'text', 'text': json.dumps({'text': value, 'cwd': os.getcwd(), 'argument': sys.argv[1], 'explicit': os.getenv('MCP_FIXTURE_ONLY'), 'ambient': os.getenv('ANDROID_DATA')}, ensure_ascii=False)}]}
                else:
                    raise RuntimeError('unexpected fixture method')
                print(json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}, ensure_ascii=False), flush=True)
        """.trimIndent()
        val node = """
            import readline from 'node:readline';
            import {writeFileSync} from 'node:fs';
            console.error('stdio-fixture-diagnostic');
            const lines = readline.createInterface({input: process.stdin, crlfDelay: Infinity});
            for await (const raw of lines) {
              const request = JSON.parse(raw);
              if (request.id === undefined) continue;
              let result;
              if (request.method === 'initialize') {
                result = {protocolVersion: request.params.protocolVersion, capabilities: {tools: {}}, serverInfo: {name: 'node-fixture', version: '1'}};
              } else if (request.method === 'tools/list') {
                result = {tools: [{name: 'write_fixture', inputSchema: {type: 'object', properties: {text: {type: 'string'}}, required: ['text']}}]};
              } else if (request.method === 'tools/call') {
                const text = request.params.arguments.text;
                writeFileSync('stdio-node-result.txt', text);
                result = {content: [{type: 'text', text: JSON.stringify({text, cwd: process.cwd(), argument: process.argv[2], explicit: process.env.MCP_FIXTURE_ONLY, ambient: process.env.ANDROID_DATA ?? null})}]};
              } else throw new Error('unexpected fixture method');
              process.stdout.write(JSON.stringify({jsonrpc: '2.0', id: request.id, result}) + '\n');
            }
        """.trimIndent()
        for ((executable, file, code, resultFile) in listOf(
            listOf("python3", "stdio 服务🌌.py", python, "stdio-python-result.txt"),
            listOf("node", "stdio 服务🌌.mjs", node, "stdio-node-result.txt"))) {
            File(workspace, file).writeText(code)
            val literal = "quoted ' \" ${'$'}(do-not-run); 中文🌌"
            val plan = service.process(workspace.path, executable, listOf(file, literal), mapOf("MCP_FIXTURE_ONLY" to "explicit-fixture"))
            val environment = plan.environment.filterValues { it != null }.mapValues { it.value!! }
            dev.solvit.terminal.StdioConnection.open(workspace, plan.arguments, environment).use { connection ->
                val frames = dev.solvit.system.StdioFrames()
                fun send(message: org.json.JSONObject) = connection.write((message.toString() + "\n").toByteArray())
                fun response(id: Int): org.json.JSONObject {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                    while (System.nanoTime() < deadline) {
                        val chunk = connection.readStdout() ?: error("$executable stdio EOF before response $id")
                        val complete = frames.append(chunk)
                        if (complete.isNotEmpty()) {
                            assertEquals(1, complete.size)
                            val message = org.json.JSONObject(complete.single())
                            assertEquals("2.0", message.getString("jsonrpc")); assertEquals(id, message.getInt("id"))
                            return message.getJSONObject("result")
                        }
                    }
                    error("$executable stdio request timed out")
                }
                send(org.json.JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                    .put("params", org.json.JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", org.json.JSONObject())
                        .put("clientInfo", org.json.JSONObject().put("name", "Solvit native probe").put("version", "1"))))
                assertEquals("2025-11-25", response(1).getString("protocolVersion"))
                send(org.json.JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"))
                send(org.json.JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list"))
                assertEquals("write_fixture", response(2).getJSONArray("tools").getJSONObject(0).getString("name"))
                val value = "$executable 实际 MCP 写入🌌"
                send(org.json.JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                    .put("params", org.json.JSONObject().put("name", "write_fixture").put("arguments", org.json.JSONObject().put("text", value))))
                val tool = org.json.JSONObject(response(3).getJSONArray("content").getJSONObject(0).getString("text"))
                assertEquals(value, tool.getString("text")); assertEquals("/workspace", tool.getString("cwd"))
                assertEquals(literal, tool.getString("argument")); assertEquals("explicit-fixture", tool.getString("explicit"))
                assertTrue(tool.isNull("ambient")); assertEquals(value, File(workspace, resultFile).readText())
                val diagnostics = java.io.ByteArrayOutputStream()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while ("stdio-fixture-diagnostic" !in diagnostics.toString("UTF-8") && System.nanoTime() < deadline) {
                    diagnostics.write(connection.readStderr() ?: break)
                }
                assertTrue(diagnostics.toString("UTF-8").contains("stdio-fixture-diagnostic"))
                connection.closeInput()
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (connection.exitCode() == null && System.nanoTime() < end) Thread.sleep(10)
                assertEquals(0, connection.exitCode())
                val remainder = connection.readStdout()
                assertTrue(remainder == null || remainder.isEmpty())
                frames.finish()
                android.util.Log.i("SolvitLinuxProbe", "STDIO_MCP_NATIVE|$executable|initialize|tools/list|tools/call|EOF|explicit-env|shared-workspace")
            }
        }
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
