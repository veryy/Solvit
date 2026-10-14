package dev.solvit.core

import java.util.UUID
import kotlin.test.*

class DocumentTextTest {
    private val id = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)
    @Test fun completePageEvidenceIsImmutableAndDiagnosticsHideContent() {
        val source = mutableListOf(DocumentTextPage(1, "private中文", DocumentPageMethod.TEXT),
            DocumentTextPage(2, "", DocumentPageMethod.EMPTY), DocumentTextPage(3, "scan", DocumentPageMethod.OCR))
        val document = DocumentText(id, hash, 3, source)
        source.clear()
        assertEquals(listOf(1, 2, 3), document.pages.map { it.number })
        assertFalse(document.toString().contains("private"))
        assertFalse(document.pages.first().toString().contains("private"))
    }
    @Test fun missingDuplicateReorderedAndExcessPagesAreRejected() {
        val first = DocumentTextPage(1, "x", DocumentPageMethod.TEXT)
        assertFailsWith<IllegalArgumentException> { DocumentText(id, hash, 2, listOf(first)) }
        assertFailsWith<IllegalArgumentException> { DocumentText(id, hash, 2, listOf(first, first)) }
        assertFailsWith<IllegalArgumentException> { DocumentText(id, hash, 2, listOf(DocumentTextPage(2, "y", DocumentPageMethod.TEXT), first)) }
        assertFailsWith<IllegalArgumentException> { DocumentText(id, hash, 41, (1..41).map { DocumentTextPage(it, "x", DocumentPageMethod.TEXT) }) }
    }
    @Test fun utf8ByteBudgetIsEnforcedAcrossPages() {
        val text = "中".repeat(50_000)
        assertFailsWith<IllegalArgumentException> { DocumentText(id, hash, 2,
            listOf(DocumentTextPage(1, text, DocumentPageMethod.TEXT), DocumentTextPage(2, text, DocumentPageMethod.TEXT))) }
        assertEquals(50_000, DocumentText(id, hash, 1, listOf(DocumentTextPage(1, text, DocumentPageMethod.TEXT))).pages.single().text.length)
    }
    @Test fun emptyBinaryAndInconsistentExtractionStatesAreRejected() {
        assertFailsWith<IllegalArgumentException> { DocumentTextPage(1, "", DocumentPageMethod.TEXT) }
        assertFailsWith<IllegalArgumentException> { DocumentTextPage(1, "x", DocumentPageMethod.EMPTY) }
        assertFailsWith<IllegalArgumentException> { DocumentTextPage(1, "x\u0000", DocumentPageMethod.OCR) }
        assertFailsWith<IllegalArgumentException> { DocumentText(id, hash, 1, listOf(DocumentTextPage(1, "", DocumentPageMethod.EMPTY))) }
    }
}
