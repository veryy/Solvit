package dev.solvit.system

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class StdioProtocolException(message: String) : IOException(message)

/** Single-reader UTF-8 newline framing, independent of the MCP/JSON-RPC business protocol. */
class StdioFrames(private val maximumBytes: Int = 262_144) {
    init { require(maximumBytes in 1..1_048_576) }
    private val pending = ByteArrayOutputStream(minOf(4096, maximumBytes))
    private var ended = false
    private var failed = false
    private fun reject(message: String): Nothing {
        failed = true
        pending.reset()
        throw StdioProtocolException(message)
    }
    private fun active() {
        if (ended || failed) throw StdioProtocolException("stdio 帧通道已结束")
    }
    /** Native polls yield at most 8192 bytes. A partial UTF-8 code point stays buffered until LF. */
    fun append(bytes: ByteArray): List<String> {
        active()
        if (bytes.size > 8192) reject("stdio 读取块超过限制")
        val result = mutableListOf<String>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] != 10.toByte()) continue
            val length = index - start
            if (length > maximumBytes - pending.size()) reject("stdio 单帧超过限制")
            pending.write(bytes, start, length)
            if (pending.size() == 0) reject("stdio 不接受空协议帧")
            val raw = pending.toByteArray()
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try { decoder.decode(ByteBuffer.wrap(raw)).toString() }
                catch (_: CharacterCodingException) { reject("stdio 帧不是有效 UTF-8") }
            result.add(text)
            pending.reset()
            start = index + 1
        }
        if (bytes.size - start > maximumBytes - pending.size()) reject("stdio 单帧超过限制")
        pending.write(bytes, start, bytes.size - start)
        return result
    }
    /** A valid JSON value without its final newline is still an incomplete protocol frame. */
    fun finish() {
        active()
        ended = true
        if (pending.size() != 0) reject("stdio EOF 前存在不完整协议帧")
    }
}
