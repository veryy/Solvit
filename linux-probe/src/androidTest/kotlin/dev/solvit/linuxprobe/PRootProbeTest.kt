package dev.solvit.linuxprobe

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real API35 app-UID execution; an Android shell probe, not a Linux distro proof. */
@RunWith(AndroidJUnit4::class)
class PRootProbeTest {
    @Test fun installedNativeProotRunsWithoutRootAndMapsWorkspaceWithSpaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Must run as ordinary application", Process.myUid() >= 10_000)
        val native = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(native, "libsolvit_proot.so")
        val loader = File(native, "libsolvit_loader.so")
        assertTrue("Packaged PRoot missing", proot.isFile)
        assertTrue("Packaged loader missing", loader.isFile)
        val workspace = File(context.filesDir, "probe workspace").apply { mkdirs() }
        val output = File(context.filesDir, "proot-probe-output.txt")
        val command = listOf(proot.absolutePath, "-0", "-r", "/", "-b",
            "${workspace.absolutePath}:/workspace", "-w", "/workspace", "/system/bin/sh", "-c",
            "printf 'guest-write' > result.txt; printf 'PROOT_UID='; id -u; cat result.txt")
        val child = ProcessBuilder(command).apply {
            environment()["PROOT_LOADER"] = loader.absolutePath
            environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            environment()["PROOT_NO_SECCOMP"] = "1"
            environment().remove("LD_LIBRARY_PATH")
            redirectErrorStream(true); redirectOutput(output)
        }.start()
        try {
            assertTrue("PRoot timed out", child.waitFor(20, TimeUnit.SECONDS))
            val log = output.readText()
            assertEquals(log, 0, child.exitValue())
            assertTrue(log, "PROOT_UID=0" in log)
            assertEquals("guest-write", File(workspace, "result.txt").readText())
            File(workspace, "from-host.txt").writeText("host-write")
            val second = ProcessBuilder(command.dropLast(1) + "cat /workspace/from-host.txt").apply {
                environment().putAll(mapOf("PROOT_LOADER" to loader.absolutePath,
                    "PROOT_TMP_DIR" to context.cacheDir.absolutePath, "PROOT_NO_SECCOMP" to "1"))
                environment().remove("LD_LIBRARY_PATH")
                redirectErrorStream(true); redirectOutput(output)
            }.start()
            try {
                assertTrue(second.waitFor(20, TimeUnit.SECONDS))
                assertEquals(output.readText(), 0, second.exitValue())
                assertTrue(output.readText(), "host-write" in output.readText())
            } finally { second.destroyForcibly() }
        } finally { child.destroyForcibly() }
    }
}
