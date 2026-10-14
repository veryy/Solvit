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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Android userland installation and package execution, not a desktop simulation. */
@RunWith(AndroidJUnit4::class)
class UbuntuProbeTest {
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
