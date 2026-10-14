package dev.solvit.linuxprobe

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.linux.RootfsArchive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real Android userland installation and package execution, not a desktop simulation. */
@RunWith(AndroidJUnit4::class)
class UbuntuProbeTest {
    @Test fun ubuntuInstallsPythonAndNodeAndUsesSharedWorkspaceWithoutRoot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(Process.myUid() >= 10_000)
        val archive = File(context.cacheDir, "ubuntu.tar.xz")
        context.assets.open("ubuntu-x86_64.tar.xz").use { input -> archive.outputStream().use { input.copyTo(it) } }
        val root = File(context.filesDir, "ubuntu")
        RootfsArchive().extract(archive,
            "1680d024bf47d2414a36759af528ca3569a73b70682daa3d9693ba63157cb5a8",
            "ubuntu-noble-x86_64", root)
        val workspace = File(context.filesDir,"ubuntu workspace").apply { mkdirs() }
        File(workspace,"from-host.txt").writeText("shared-workspace")
        File(root,"etc/resolv.conf").writeText("nameserver 10.0.2.3\n")
        val native = File(context.applicationInfo.nativeLibraryDir)
        val log = File(context.filesDir,"ubuntu-probe.log")
        val command = listOf(File(native,"libsolvit_proot.so").absolutePath,
            "-0", "--link2symlink", "-r", root.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${workspace.absolutePath}:/workspace", "-w", "/workspace",
            "/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive", "/bin/bash", "-ec",
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
        val child = ProcessBuilder(command).apply {
            environment()["PROOT_LOADER"] = File(native,"libsolvit_loader.so").absolutePath
            environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            environment()["PROOT_L2S_DIR"] = File(root,".l2s").apply { mkdirs() }.absolutePath
            environment().remove("PROOT_NO_SECCOMP")
            environment().remove("LD_LIBRARY_PATH")
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
            File(context.filesDir,"ubuntu-probe-versions.txt").writeText(versions)
        } finally { child.destroyForcibly() }
    }
}
