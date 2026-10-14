package dev.solvit.core

import org.junit.Assert.*
import org.junit.Test

class PagePdfPreviewTest {
    @Test fun textPreviewStopsAtItsExplicitBoundaryWithoutSplittingASupplementaryCharacter() {
        val writer = PagePreviewTextWriter(4)
        writer.write("abc")
        try { writer.write("\uD83D\uDE00tail"); fail("Unbounded preview accepted") }
        catch (_: PagePreviewTextWriter.LimitReached) { }
        assertEquals("abc", writer.text()); assertTrue(writer.truncated)
        val complete = PagePreviewTextWriter(5)
        complete.write("abc\uD83D\uDE00")
        assertEquals("abc\uD83D\uDE00", complete.text()); assertFalse(complete.truncated)
    }
    @Test fun aPagePreviewRedactsContentAndClosesItsOwnedPicture() {
        val picture = PageFilePicture(1, 1, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
        val preview = PagePdfPreview(2, 3, picture, "private fixture text", false)
        assertFalse(preview.toString().contains("private fixture"))
        preview.close()
        try { picture.jpegBytes(); fail("Closed picture was exposed") } catch (_: IllegalStateException) { }
        try { PagePdfPreview(4, 3, picture, "", false); fail("Out-of-range page accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
