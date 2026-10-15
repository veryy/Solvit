package dev.solvit.terminal

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class StdioConnectionTest {
    private val root get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private fun open(script: String, args: List<String> = emptyList(), env: Map<String, String> = emptyMap(), cwd: File = root) =
        StdioConnection.open(cwd, listOf("/system/bin/sh", "-c", script, "stdio-fixture") + args, env)
    private fun readUntil(connection: StdioConnection, marker: String, stderr: Boolean = false): String {
        val bytes = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val next = if (stderr) connection.readStderr() else connection.readStdout()
            if (next == null) break
            bytes.write(next)
            val output = bytes.toString("UTF-8")
            if (marker in output) return output
        }
        fail("Missing fixture output $marker"); error("unreachable")
    }
    private fun drain(connection: StdioConnection, stderr: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val next = if (stderr) connection.readStderr() else connection.readStdout()
            if (next == null) return bytes.toByteArray()
            bytes.write(next)
        }
        fail("stdio did not reach EOF"); error("unreachable")
    }
    private fun ended(connection: StdioConnection): Int? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) { connection.exitCode()?.let { return it }; Thread.sleep(10) }
        fail("stdio did not report an exit status"); return null
    }
    /** A zombie has stopped executing and awaits Android init; do not count it as running. */
    private fun assertStopped(pid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val stat = try { File("/proc/$pid/stat").readText() } catch (_: IOException) { return }
            if (stat.substringAfterLast(") ").startsWith("Z")) return
            Thread.sleep(10)
        }
        fail("Owned fixture process still running: $pid")
    }

    @Test fun rawProtocolHasNoEchoTtyOrMergedStderr() {
        open("""
            [ ! -t 0 ] && [ ! -t 1 ] && [ ! -t 2 ] || exit 90
            printf 'diagnostic-only\n' >&2
            IFS= read -r line
            printf '%s\n' "${'$'}line"
            IFS= read -r line
            printf '%s\n' "${'$'}line"
        """.trimIndent()).use { connection ->
            val input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"text\":\"中文🌌\\n\"}\n{\"id\":2}\n".toByteArray()
            connection.write(input)
            assertArrayEquals(input, drain(connection))
            assertEquals("diagnostic-only\n", drain(connection, true).toString(Charsets.UTF_8))
            assertEquals(0, ended(connection))
        }
    }
    @Test fun explicitEnvironmentDoesNotInheritHostOrMutateOtherSessions() {
        assertNotNull("Android fixture must have an ambient value to exclude", System.getenv("ANDROID_DATA"))
        open("printf '%s|%s' \"${'$'}SOLVIT_STDIO_EXPLICIT\" \"${'$'}{ANDROID_DATA-unset}\"", env = mapOf("SOLVIT_STDIO_EXPLICIT" to "first")).use { first ->
            open("printf '%s|%s' \"${'$'}SOLVIT_STDIO_EXPLICIT\" \"${'$'}{ANDROID_DATA-unset}\"", env = mapOf("SOLVIT_STDIO_EXPLICIT" to "second")).use { second ->
                assertEquals("first|unset", drain(first).toString(Charsets.UTF_8))
                assertEquals("second|unset", drain(second).toString(Charsets.UTF_8))
            }
        }
        assertNull(System.getenv("SOLVIT_STDIO_EXPLICIT"))
    }
    @Test fun supplementaryUnicodeSurvivesCwdArgumentsEnvironmentAndRawBytes() {
        val folder = File(root, "stdio 中文🌌").apply { check(mkdirs() || isDirectory) }
        try {
            val value = "🌌 quoted ' \" ${'$'}(not-executed);\\n"
            open("printf '%s\\000%s\\000%s' \"${'$'}PWD\" \"${'$'}1\" \"${'$'}SOLVIT_STDIO_TEXT\"; exit 7",
                listOf(value), mapOf("SOLVIT_STDIO_TEXT" to value), folder).use { connection ->
                assertArrayEquals("${folder.canonicalPath}\u0000$value\u0000$value".toByteArray(), drain(connection))
                assertEquals(7, ended(connection))
            }
        } finally { folder.deleteRecursively() }
    }
    @Test fun protocolEofDoesNotDependOnLifetimeWatchdog() {
        open("while IFS= read -r line; do :; done; printf 'protocol-eof'; exit 23").use { connection ->
            connection.write("first\n".toByteArray())
            connection.closeInput(); connection.closeInput()
            assertEquals("protocol-eof", drain(connection).toString(Charsets.UTF_8))
            assertEquals(23, ended(connection))
        }
    }
    @Test fun closeKillsTermIgnoringWorkerAndDescendant() {
        val connection = open("""
            trap '' TERM
            /system/bin/sh -c 'trap "" TERM; while :; do /system/bin/sleep 1; done' &
            printf '%s %s\nREADY\n' "${'$'}${'$'}" "${'$'}!"
            wait
        """.trimIndent())
        val pids = readUntil(connection, "READY").substringBefore('\n').split(' ').map(String::toInt)
        pids.forEach { assertTrue("Fixture PID was not observable", File("/proc/$it/stat").readText().isNotEmpty()) }
        connection.close(); connection.close()
        pids.forEach(::assertStopped)
        assertFalse("Direct worker was not reaped", File("/proc/${pids.first()}").exists())
        try { connection.write("after-close\n".toByteArray()); fail("Closed transport accepted input") }
        catch (_: IllegalStateException) { }
    }
    @Test fun normalWorkerExitStillCleansBackgroundDescendantsAndReportsItsCode() {
        open("""
            /system/bin/sh -c 'trap "" TERM; while :; do /system/bin/sleep 1; done' &
            printf '%s\n' "${'$'}!"
            exit 17
        """.trimIndent()).use { connection ->
            val pid = drain(connection).toString(Charsets.UTF_8).trim().toInt()
            assertEquals(17, ended(connection))
            assertStopped(pid)
        }
    }
    @Test fun launchFailuresAreReapedAndDoNotPreventLaterLaunch() {
        repeat(4) {
            try { StdioConnection.open(root, listOf("/solvit-no-such-executable")); fail("Nonexistent command launched") }
            catch (_: IOException) { }
        }
        open("printf 'after-failed-launch'").use { assertEquals("after-failed-launch", drain(it).toString(Charsets.UTF_8)) }
    }
    @Test fun concurrentReaderAndCloserFinishWithoutReusingDescriptors() {
        val connection = open("/system/bin/sleep 30")
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            started.countDown()
            try { connection.readStdout() }
            catch (_: IllegalStateException) { /* Close may win before the reader gets its handle. */ }
            catch (_: IOException) { /* Close may win before the native lookup. */ }
            catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        reader.start(); assertTrue(started.await(1, TimeUnit.SECONDS)); connection.close()
        assertTrue("Reader was not bounded", finished.await(2, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        open("printf 'fresh'").use { assertEquals("fresh", drain(it).toString(Charsets.UTF_8)) }
    }
    @Test fun brokenInputIsAnExceptionAndDoesNotDeliverSigpipeToTheApp() {
        val connection = open("exec 0<&-; printf 'READY'; /system/bin/sleep 30")
        try {
            readUntil(connection, "READY")
            try { connection.write("broken-input\n".toByteArray()); fail("Closed input accepted a write") }
            catch (_: IOException) { }
        } finally { connection.close() }
        open("printf 'still-alive'").use { assertEquals("still-alive", drain(it).toString(Charsets.UTF_8)) }
    }
}
