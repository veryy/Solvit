package dev.solvit.terminal

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PtyConnectionTest {
    private val root get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private fun readUntil(pty: PtyConnection, marker: String): String {
        val output = java.io.ByteArrayOutputStream()
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            val bytes = pty.read() ?: break
            output.write(bytes)
            val text = output.toString("UTF-8")
            if (marker in text) return text
        }
        fail("未收到 $marker；输出：${output.toString("UTF-8")}")
        error("unreachable")
    }
    private fun ready(pty: PtyConnection) {
        // Disable echo so assertions cannot accidentally match the submitted command.
        pty.write("stty -echo; printf '__PTY_%s__\\n' READY\n".toByteArray())
        readUntil(pty, "__PTY_READY__")
    }
    @Test fun ttyAndDirectoryEnvironmentPersistAcrossInputs() {
        val folder = File(root, "pty-state").apply { mkdirs() }
        PtyConnection.open(root).use { pty ->
            ready(pty)
            pty.write("cd '${folder.path}'; export SOLVIT_PTY_TEST=retained; printf '__SET_%s__\\n' OK\n".toByteArray())
            readUntil(pty, "__SET_OK__")
            pty.write("[ -t 0 ] && [ -t 1 ] && printf 'tty:yes\\n'; printf 'cwd:%s\\nvalue:%s\\n__CHECK_%s__\\n' \"\$PWD\" \"\$SOLVIT_PTY_TEST\" OK\n".toByteArray())
            val text = readUntil(pty, "__CHECK_OK__")
            assertTrue(text, text.contains("tty:yes"))
            assertTrue(text, text.contains("cwd:${folder.path}"))
            assertTrue(text, text.contains("value:retained"))
        }
    }
    @Test fun interactiveInputAndInterruptLeaveShellUsable() {
        PtyConnection.open(root).use { pty ->
            ready(pty)
            pty.write("printf '__WAIT_%s__\\n' INPUT; read answer; printf 'answer:%s\\n__READ_%s__\\n' \"\$answer\" OK\n".toByteArray())
            readUntil(pty, "__WAIT_INPUT__")
            pty.write("hello-interactive\n".toByteArray())
            assertTrue(readUntil(pty, "__READ_OK__").contains("answer:hello-interactive"))
            pty.write("sleep 30\n".toByteArray())
            // Queue a sentinel behind sleep: it must only run after Ctrl-C interrupts it.
            Thread.sleep(200)
            pty.interrupt()
            pty.write("printf '__AFTER_%s__\\n' INTERRUPT\n".toByteArray())
            readUntil(pty, "__AFTER_INTERRUPT__")
        }
    }
    @Test fun windowResizeIsVisibleToChildAndExitStatusIsReaped() {
        PtyConnection.open(root).use { pty ->
            ready(pty); pty.resize(33, 101)
            pty.write("stty size; printf '__SIZE_%s__\\n' OK\n".toByteArray())
            assertTrue(readUntil(pty, "__SIZE_OK__").contains("33 101"))
            pty.write("exit 7\n".toByteArray())
            val deadline = System.nanoTime() + 3_000_000_000L
            while (pty.exitCode() == null && System.nanoTime() < deadline) pty.read()
            assertEquals(7, pty.exitCode())
        }
    }
    @Test fun launchFailureAndRepeatedCloseAreSafe() {
        try { PtyConnection.open(root, command = listOf("/does/not/exist")); fail("无效命令不应启动") }
        catch (_: java.io.IOException) { }
        val pty = PtyConnection.open(root)
        pty.close(); pty.close()
        try { pty.write("pwd\n".toByteArray()); fail("关闭后不应接受输入") }
        catch (_: IllegalStateException) { }
    }
}
