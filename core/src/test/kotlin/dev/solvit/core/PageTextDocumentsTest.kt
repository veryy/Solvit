package dev.solvit.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PageTextDocumentsTest {
    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output -> files.forEach { (name, content) ->
            output.putNextEntry(ZipEntry(name)); output.write(content); output.closeEntry()
        } }
        return bytes.toByteArray()
    }
    @Test fun selectedTextAndArchiveKeepExactContentWithoutExtractingPaths() {
        val text = "---\nname: review\n---\n中文内容"
        val single = PageTextDocuments.decode("SKILL.md", text.toByteArray())
        assertEquals(text, single.text); assertNull(single.files)
        val archive = PageTextDocuments.decode("review.zip", zip(mapOf(
            "review/SKILL.md" to text.toByteArray(), "review/references/code.txt" to "code".toByteArray())))
        assertNull(archive.text); assertEquals(text, archive.files?.get("review/SKILL.md"))
        assertEquals("code", archive.files?.get("review/references/code.txt"))
    }
    @Test fun traversalBinaryTextZipBombAndEntryCountAreRejected() {
        for (path in listOf("../outside.txt", "/absolute.txt", "review/../SKILL.md", "review\\SKILL.md"))
            assertFails { PageTextDocuments.decode("archive.zip", zip(mapOf(path to byteArrayOf(65)))) }
        assertFails { PageTextDocuments.decode("text.txt", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFails { PageTextDocuments.decode("text.txt", byteArrayOf(65, 0)) }
        assertFails { PageTextDocuments.decode("large.zip", zip(mapOf("large.txt" to ByteArray(32 * 1024 + 1) { 65 }))) }
        assertFails { PageTextDocuments.decode("total.zip", zip((0..6).associate { "$it.txt" to ByteArray(32 * 1024) { 65 } })) }
        assertFails { PageTextDocuments.decode("entries.zip", zip((0..40).associate { "$it.txt" to byteArrayOf(65) })) }
        assertFails { PageTextDocuments.decode("empty.txt", byteArrayOf()) }
        assertFails { PageTextDocuments.decode("large.txt", ByteArray(PageDocumentControl.MAX_BYTES + 1) { 65 }) }
    }
}
