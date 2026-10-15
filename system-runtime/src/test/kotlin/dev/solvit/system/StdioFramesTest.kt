package dev.solvit.system

import kotlin.test.*

class StdioFramesTest {
    @Test fun everyByteBoundaryPreservesChineseSupplementaryCharactersAndEscapedNewlines() {
        val first = "{\"text\":\"中文🌌\\nnext\",\"id\":1}"
        val second = "{\"id\":2}\r"
        val wire = "$first\n$second\n".toByteArray()
        for (split in 0..wire.size) {
            val frames = StdioFrames()
            val actual = frames.append(wire.copyOfRange(0, split)) + frames.append(wire.copyOfRange(split, wire.size))
            assertEquals(listOf(first, second), actual, "UTF-8 split $split")
            frames.finish()
        }
    }
    @Test fun multipleFramesAndPartialTailAreKeptInOrderAcrossReads() {
        val frames = StdioFrames()
        assertEquals(emptyList(), frames.append(byteArrayOf()))
        assertEquals(listOf("{}", "{\"id\":1}"), frames.append("{}\n{\"id\":1}\n{\"id\":".toByteArray()))
        assertEquals(listOf("{\"id\":2}"), frames.append("2}\n".toByteArray()))
        frames.finish()
        assertFailsWith<StdioProtocolException> { frames.append("{}\n".toByteArray()) }
    }
    @Test fun completeFrameAtByteLimitPassesButOversizeAndUnterminatedEofFailClosed() {
        StdioFrames(4).apply { assertEquals(listOf("1234"), append("1234\n".toByteArray())); finish() }
        val oversized = StdioFrames(4)
        assertEquals(emptyList(), oversized.append("1234".toByteArray()))
        assertFailsWith<StdioProtocolException> { oversized.append("5\n".toByteArray()) }
        assertFailsWith<StdioProtocolException> { oversized.append("{}\n".toByteArray()) }
        val incomplete = StdioFrames().apply { append("{\"id\":1}".toByteArray()) }
        assertFailsWith<StdioProtocolException> { incomplete.finish() }
    }
    @Test fun malformedUtf8BlankFramesAndOversizedReadChunksAreRejectedWithoutEchoingPayload() {
        for (bytes in listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte(), 10), byteArrayOf(10), ByteArray(8193) { 65 })) {
            val frames = StdioFrames()
            val failure = assertFailsWith<StdioProtocolException> { frames.append(bytes) }
            assertFalse(failure.message.orEmpty().contains("AAAA"))
            assertFailsWith<StdioProtocolException> { frames.finish() }
        }
        val truncatedCodePoint = StdioFrames()
        assertEquals(emptyList(), truncatedCodePoint.append(byteArrayOf(0xf0.toByte(), 0x9f.toByte())))
        assertFailsWith<StdioProtocolException> { truncatedCodePoint.append(byteArrayOf(10)) }
    }
}
