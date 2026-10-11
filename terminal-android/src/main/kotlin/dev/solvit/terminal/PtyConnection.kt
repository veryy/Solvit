package dev.solvit.terminal

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** Trusted native primitive. JS receives authorized service methods, never this handle. */
class PtyConnection private constructor(id: Long) : Closeable {
    private val handle = AtomicLong(id)
    private fun id(): Long = handle.get().also { check(it != 0L) { "终端已关闭" } }
    /** null means EOF; an empty byte array means the bounded poll had no input. */
    fun read(): ByteArray? = NativePty.read(id())
    fun write(bytes: ByteArray) { require(bytes.size in 1..32_768); NativePty.write(id(), bytes) }
    fun resize(rows: Int, columns: Int) { require(rows in 2..300 && columns in 2..500); NativePty.resize(id(), rows, columns) }
    fun interrupt() = write(byteArrayOf(3))
    fun exitCode(): Int? = NativePty.exitCode(id()).takeUnless { it == Int.MIN_VALUE }
    override fun close() { val old = handle.getAndSet(0); if (old != 0L) NativePty.close(old) }
    companion object {
        fun open(directory: File, rows: Int = 24, columns: Int = 80,
            command: List<String> = listOf("/system/bin/sh", "-i")): PtyConnection {
            require(directory.isDirectory && directory.canonicalPath.length <= 4096)
            require(rows in 2..300 && columns in 2..500)
            require(command.size in 1..16 && File(command.first()).isAbsolute)
            require(command.all { it.length <= 16_000 && '\u0000' !in it })
            val id = NativePty.open(directory.canonicalPath, command.toTypedArray(), rows, columns)
            if (id == 0L) throw IOException("PTY 创建失败")
            return PtyConnection(id)
        }
    }
}

internal object NativePty {
    init { System.loadLibrary("solvit_pty") }
    external fun open(directory: String, command: Array<String>, rows: Int, columns: Int): Long
    external fun read(handle: Long): ByteArray?
    external fun write(handle: Long, bytes: ByteArray)
    external fun resize(handle: Long, rows: Int, columns: Int)
    external fun exitCode(handle: Long): Int
    external fun close(handle: Long)
}
