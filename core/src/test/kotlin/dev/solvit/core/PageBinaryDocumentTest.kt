package dev.solvit.core

import java.security.MessageDigest
import kotlin.test.*

class PageBinaryDocumentTest {
    @Test fun textPreviewIsBoundedStrictAndNeverChangesImportBytes() {
        val text = "a".repeat(8191) + "🙂" + "tail"
        val bytes = text.toByteArray()
        val document = PageBinaryDocument("code.txt", "text/plain", bytes)
        val preview = document.textPreview()
        assertEquals("TEXT", preview.kind); assertEquals("a".repeat(8191), preview.text)
        assertTrue(preview.truncated); assertContentEquals(bytes, document.readBytes())
        assertFalse(preview.toString().contains("aaaa"))
        document.close(); assertFails { document.textPreview() }
        for (raw in listOf(byteArrayOf(-1), byteArrayOf(65, 0, 66), byteArrayOf(65, -61))) {
            val binary = PageBinaryDocument("file.bin", "text/plain", raw)
            try { assertEquals("BINARY", binary.textPreview().kind); assertNull(binary.textPreview().text) }
            finally { binary.close() }
        }
        val large = PageBinaryDocument("large.txt", "text/plain", ByteArray(MAX_FILE_CAPTURE_BYTES + 1) { 65 })
        try { assertEquals("OVERSIZED", large.textPreview().kind); assertNull(large.textPreview().text) }
        finally { large.close() }
        val empty = PageBinaryDocument("empty.txt", "text/plain", byteArrayOf())
        try { assertEquals(PageImportTextPreview("TEXT", "", false), empty.textPreview()) }
        finally { empty.close() }
    }
    @Test fun selectedBinaryKeepsExactBytesAndDigestWithoutExposingPrivateContentInDiagnostics() {
        val input=byteArrayOf(0,1,255.toByte(),13,10)
        val original=input.copyOf()
        val source=PageBinaryDocument("private-name.pdf","application/pdf",input)
        val digest=MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it.toInt() and 255) }
        input.fill(0)
        assertEquals(digest,source.sha256);assertEquals(original.size.toLong(),source.sizeBytes)
        val copy=source.readBytes();copy.fill(0)
        assertContentEquals(original,source.readBytes())
        assertFalse(source.toString().contains("private-name"));assertFalse(source.toString().contains(digest))
        source.close();source.close()
        assertFailsWith<IllegalStateException> { source.readBytes() }
    }
    @Test fun invalidNamesTypesAndOversizedFilesFailBeforeTheyBecomePageSelections() {
        for(name in listOf("","x\nprivate","x".repeat(257))) assertFailsWith<IllegalArgumentException> {
            PageBinaryDocument(name,"application/octet-stream",byteArrayOf(1))
        }
        for(mime in listOf("*/*","invalid","image/png;secret","IMAGE/PNG")) assertFailsWith<IllegalArgumentException> {
            PageBinaryDocument("file",mime,byteArrayOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            PageBinaryDocument("file","application/octet-stream",ByteArray(MAX_WORKSPACE_IMPORT_BYTES+1))
        }
        PageBinaryDocument("empty.txt","text/plain",byteArrayOf()).use { assertContentEquals(byteArrayOf(),it.readBytes()) }
    }
}
