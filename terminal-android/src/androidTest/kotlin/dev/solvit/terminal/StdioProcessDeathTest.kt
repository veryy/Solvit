package dev.solvit.terminal

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Invoked only by verify-stdio-process-death.sh, which kills the actual JVM PID. */
class StdioProcessDeathTest {
    @Test fun prepareOwnedNativeProcessesForExternalHostKill() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.filesDir, "stdio-process-death.json")
        fixture.delete()
        val command = """
            trap '' TERM
            /system/bin/sh -c 'trap "" TERM; while :; do /system/bin/sleep 1; done' &
            printf '%s %s\nREADY\n' "${'$'}${'$'}" "${'$'}!"
            wait
        """.trimIndent()
        StdioConnection.open(context.filesDir, listOf("/system/bin/sh", "-c", command)).use { connection ->
            val output = java.io.ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while ("READY" !in output.toString("UTF-8") && System.nanoTime() < deadline) {
                output.write(connection.readStdout() ?: error("Fixture process exited before readiness"))
            }
            val text = output.toString("UTF-8")
            assertTrue(text.contains("READY"))
            val pids = text.substringBefore('\n').split(' ').map(String::toInt)
            pids.forEach { assertTrue(File("/proc/$it/stat").readText().isNotEmpty()) }
            val supervisor = File("/proc/${pids.first()}/status").readLines()
                .first { it.startsWith("PPid:") }.substringAfter(':').trim().toInt()
            assertTrue(supervisor > 1 && supervisor != Process.myPid())
            val record = JSONObject().put("fixture", "solvit-native-stdio-death-v1").put("host", Process.myPid())
                .put("supervisor", supervisor).put("worker", pids[0]).put("descendant", pids[1])
            val pending = File(context.filesDir, "stdio-process-death.pending")
            java.io.FileOutputStream(pending).use { it.write(record.toString().toByteArray()); it.fd.sync() }
            check(pending.renameTo(fixture))
            // SIGKILL bypasses this use/finally and all Kotlin cleanup. The external
            // script must observe the recorded native processes stop afterwards.
            CountDownLatch(1).await(90, TimeUnit.SECONDS)
            fail("The external script did not kill the fixture JVM")
        }
    }
}
