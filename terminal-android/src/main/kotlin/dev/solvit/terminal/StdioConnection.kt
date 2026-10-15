package dev.solvit.terminal

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Trusted host primitive, separate from PTY. Never expose this handle to JS. */
class StdioConnection private constructor(id: Long) : Closeable {
    private val handle = AtomicLong(id)
    private fun id() = handle.get().also { check(it != 0L) { "stdio 进程已关闭" } }
    /** null is EOF; an empty array is a bounded poll without data. Raw bytes, no TTY conversion. */
    fun readStdout(): ByteArray? = NativeStdio.read(id(), false)
    fun readStderr(): ByteArray? = NativeStdio.read(id(), true)
    fun write(bytes: ByteArray) {
        require(bytes.size in 1..262_144)
        try { NativeStdio.write(id(), bytes) }
        catch (error: IOException) { close(); throw error }
    }
    /** Half-close protocol input without closing the lifetime watchdog. */
    fun closeInput() = NativeStdio.closeInput(id())
    fun exitCode(): Int? = NativeStdio.exitCode(id()).takeUnless { it == Int.MIN_VALUE }
    override fun close() { val old = handle.getAndSet(0L); if (old != 0L) NativeStdio.close(old) }

    companion object {
        /** All inputs are host-owned; environment is complete, with no inherited application values. */
        fun open(directory: File, command: List<String>, environment: Map<String, String> = emptyMap()): StdioConnection {
            require(directory.isDirectory && directory.canonicalPath.length <= 4096)
            require(command.size in 1..128 && File(command.first()).isAbsolute)
            require(command.all { it.length <= 32_768 && '\u0000' !in it })
            require(environment.size <= 64 && environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) })
            require(environment.values.all { it.length <= 32_768 && '\u0000' !in it })
            val variables = environment.map { (key, value) -> "$key=$value" }
            require((command + variables).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() + 1 } <= 128 * 1024)
            val opened = NativeStdio.open(directory.canonicalPath.toByteArray(Charsets.UTF_8),
                command.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                variables.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray())
            if (opened == 0L) throw IOException("stdio 进程启动失败")
            return StdioConnection(opened)
        }
    }
}

internal object NativeStdio {
    init { System.loadLibrary("solvit_pty") }
    external fun open(directory: ByteArray, command: Array<ByteArray>, environment: Array<ByteArray>): Long
    external fun read(handle: Long, stderr: Boolean): ByteArray?
    external fun write(handle: Long, bytes: ByteArray)
    external fun closeInput(handle: Long)
    external fun exitCode(handle: Long): Int
    external fun close(handle: Long)
}
