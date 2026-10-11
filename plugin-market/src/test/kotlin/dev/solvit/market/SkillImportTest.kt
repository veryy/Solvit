package dev.solvit.market

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class SkillImportTest {
    private val markdown = "---\nname: code-review\ndescription: Review code\n---\nCheck errors."
    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun plainMarkdownJsonAndZipHaveEquivalentTextContents() {
        val standalone = SkillImports.parse(markdown.toByteArray())
        assertEquals(markdown, standalone.files["SKILL.md"])
        val imported = SkillImports.parse(zip("code-review/SKILL.md" to markdown, "code-review/references/guide.md" to "中文 😀"))
        assertEquals("code-review", imported.directory)
        assertEquals("中文 😀", imported.files["references/guide.md"])
        val json = """{"format":1,"directory":"code-review","files":{"SKILL.md":"test"}}"""
        assertEquals("test", SkillImports.parse(json.toByteArray()).files["SKILL.md"])
    }
    @Test fun traversalOutsideRootAndMultipleSkillsAreRejected() {
        for (archive in listOf(
            zip("SKILL.md" to markdown, "../secret.md" to "bad"),
            zip("code-review/SKILL.md" to markdown, "other/file.md" to "bad"),
            zip("first/SKILL.md" to markdown, "second/SKILL.md" to markdown),
            zip("/SKILL.md" to markdown),
        )) assertFailsWith<Exception> { SkillImports.parse(archive) }
    }
    @Test fun compressedBombAndInvalidUtf8AreRejectedWithoutExtraction() {
        assertFailsWith<IllegalArgumentException> { SkillImports.parse(zip("SKILL.md" to "x".repeat(100_000))) }
        assertFailsWith<Exception> { SkillImports.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFailsWith<IllegalArgumentException> { SkillImports.parse(ByteArray(SkillImports.MAX_INPUT + 1)) }
    }
}
