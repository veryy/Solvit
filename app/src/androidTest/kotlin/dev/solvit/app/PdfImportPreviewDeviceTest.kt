package dev.solvit.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import dev.solvit.core.*
import dev.solvit.documents.AndroidPdfImportRenderer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Actual PDFBox extraction and Android PdfRenderer pixels; no fake image or page count. */
class PdfImportPreviewDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun pdf(password: Boolean = false, scanSecond: Boolean = false, longText: Boolean = false): ByteArray {
        PDFBoxResourceLoader.init(app)
        return PDDocument().use { document ->
            for (number in 1..2) {
                val page = PDPage(PDRectangle(400f, 240f)); document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    if (number == 1) stream.setNonStrokingColor(1f, 0f, 0f) else stream.setNonStrokingColor(0f, 0f, 1f)
                    stream.addRect(10f, 10f, 180f, 100f); stream.fill()
                    if (scanSecond && number == 2) {
                        val pixels = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
                        try { pixels.eraseColor(Color.BLUE); stream.drawImage(LosslessFactory.createFromImage(document, pixels), 20f, 20f, 240f, 120f) }
                        finally { pixels.recycle() }
                    } else {
                        stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 18f); stream.newLineAtOffset(20f, 180f)
                        stream.showText(if (longText) "fixture text ".repeat(900) else "PAGE $number"); stream.endText()
                    }
                }
            }
            if (password) document.protect(StandardProtectionPolicy("owner fixture", "user fixture", AccessPermission()).apply { encryptionKeyLength = 128 })
            ByteArrayOutputStream().use { document.save(it); it.toByteArray() }
        }
    }
    @Test fun selectedPdfPageIsRenderedWithItsOwnTextAndKeepsOriginalBytes() = runBlocking<Unit> {
        val bytes = pdf(); val renderer = AndroidPdfImportRenderer(app)
        PageBinaryDocument("pages.pdf", "application/pdf", bytes).use { document ->
            renderer.render(document, 2) {}.use { page ->
                assertEquals(2, page.page); assertEquals(2, page.pageCount)
                assertTrue(page.text.contains("PAGE 2")); assertFalse(page.text.contains("PAGE 1")); assertFalse(page.truncated)
                val jpeg = page.picture.jpegBytes()
                val pixels = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                try {
                    var blue = 0
                    for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                        val p = pixels.getPixel(x, y)
                        if (Color.blue(p) > 180 && Color.red(p) < 70 && Color.green(p) < 70) blue++
                    }
                    assertTrue("Rendered page must contain the second page's blue rectangle", blue > 100)
                } finally { pixels.recycle() }
                assertArrayEquals(bytes, document.readBytes())
            }
            try { renderer.render(document, 3) {}; fail("Accepted page beyond document") } catch (_: IllegalArgumentException) { }
        }
        assertNoSources()
    }
    @Test fun scanPageStillRendersWithoutInventingExtractedText() = runBlocking<Unit> {
        val bytes = pdf(scanSecond = true)
        PageBinaryDocument("scan.pdf", "application/pdf", bytes).use { document ->
            AndroidPdfImportRenderer(app).render(document, 2) {}.use { page ->
                assertTrue(page.text.isBlank()); assertFalse(page.truncated)
                val jpeg = page.picture.jpegBytes()
                val pixels = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                try {
                    val center = pixels.getPixel(80, pixels.height - 60)
                    assertTrue(Color.blue(center) > 180 && Color.red(center) < 70 && Color.green(center) < 70)
                }
                finally { pixels.recycle() }
                assertArrayEquals(bytes, document.readBytes())
            }
        }
        assertNoSources()
    }
    @Test fun oversizedPageTextIsExplicitlyTruncatedWhileTheOriginalIsPreserved() = runBlocking<Unit> {
        val bytes = pdf(longText = true)
        PageBinaryDocument("long.pdf", "application/pdf", bytes).use { document ->
            AndroidPdfImportRenderer(app).render(document, 1) {}.use { page ->
                assertTrue(page.truncated); assertEquals(8192, page.text.length)
                assertTrue(page.text.startsWith("fixture text")); assertArrayEquals(bytes, document.readBytes())
            }
        }
        assertNoSources()
    }
    @Test fun damagedProtectedAndRevokedDocumentsDoNotReturnPixelsOrLeavePrivateSources() = runBlocking<Unit> {
        val renderer = AndroidPdfImportRenderer(app)
        for (bytes in listOf("%PDF-1.7\nbroken".toByteArray(), pdf(password = true))) {
            PageBinaryDocument("unreadable.pdf", "application/pdf", bytes).use { document ->
                try { renderer.render(document, 1) {}; fail("Accepted unreadable PDF") }
                catch (expected: ModelException) { assertTrue(expected.message!!.isNotBlank()) }
                assertArrayEquals(bytes, document.readBytes())
            }
            assertNoSources()
        }
        PageBinaryDocument("pages.pdf", "application/pdf", pdf()).use { document ->
            var checks = 0
            try { renderer.render(document, 1) { if (++checks == 4) error("fixture revoked") }; fail("Returned revoked page") }
            catch (expected: IllegalStateException) { assertEquals("fixture revoked", expected.message) }
        }
        assertNoSources()
    }
    @Test fun cancellationAfterStagingCleansSourceAndTheNextPageStillWorks() = runBlocking<Unit> {
        val renderer = AndroidPdfImportRenderer(app)
        PageBinaryDocument("pages.pdf", "application/pdf", pdf()).use { document ->
            val reached = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>(); var checks = 0
            val operation = launch { renderer.render(document, 1) {
                if (++checks == 4) { reached.complete(Unit); hold.await() }
            }.close() }
            withTimeout(10_000) { reached.await() }; operation.cancelAndJoin(); assertNoSources()
            renderer.render(document, 1) {}.use { assertTrue(it.text.contains("PAGE 1")) }
        }
        assertNoSources()
    }
    private fun assertNoSources() {
        assertTrue(File(app.cacheDir, "workspace-pdf-previews").listFiles().orEmpty().isEmpty())
    }
}
