package dev.solvit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import dev.solvit.documents.AndroidDocumentTextService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Real Android PDFBox + bundled ML Kit + private attachment files, without model credentials. */
class DocumentReaderDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun app(): SolvitApplication {
        val app = rule.activity.application as SolvitApplication
        rule.waitUntil(15_000) { app.ready.value != null }
        check(app.ready.value!!.isSuccess)
        PDFBoxResourceLoader.init(app)
        return app
    }
    private fun pdf(scanned: Boolean = false, password: Boolean = false): ByteArray {
        PDDocument().use { document ->
            val first = PDPage(PDRectangle(500f, 250f)); document.addPage(first)
            PDPageContentStream(document, first).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 24f)
                stream.newLineAtOffset(30f, 180f); stream.showText("SHORT"); stream.endText()
            }
            if (scanned) {
                val bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=54f }
                    Canvas(bitmap).apply {
                        drawText("SCAN PAGE 2026", 50f, 110f, paint)
                        drawText("扫描测试 2026", 50f, 240f, paint)
                    }
                    val second = PDPage(PDRectangle(500f, 250f)); document.addPage(second)
                    val image = LosslessFactory.createFromImage(document, bitmap)
                    PDPageContentStream(document, second).use { it.drawImage(image, 0f, 0f, 500f, 250f) }
                } finally { bitmap.recycle() }
            }
            if (password) document.protect(StandardProtectionPolicy("owner", "secret", AccessPermission()).apply { encryptionKeyLength=128 })
            return ByteArrayOutputStream().use { output -> document.save(output); output.toByteArray() }
        }
    }
    private suspend fun import(app: SolvitApplication, session: String, bytes: ByteArray) =
        ByteArrayInputStream(bytes).use { app.attachments.import(session, "fixture.pdf", "application/pdf", it) }
    private fun assertNoTemporarySources(context: Context) {
        assertTrue(File(context.cacheDir,"document-reader").listFiles().orEmpty().isEmpty())
    }
    @Test fun realMixedPdfKeepsShortTextAndRecognizesEnglishAndChineseScan() = runBlocking {
        val app=app();val chat=app.ready.value!!.getOrThrow();val session=chat.createSession().id
        try {
            val ref=import(app, session, pdf(scanned=true))
            val result=withTimeout(90_000) { AndroidDocumentTextService(app,app.attachments).readPdf(session,ref) }
            assertEquals(ref.id,result.attachmentId);assertEquals(ref.sha256,result.sha256)
            assertEquals(2,result.pageCount)
            assertEquals(DocumentPageMethod.TEXT,result.pages[0].method);assertEquals("SHORT",result.pages[0].text)
            assertEquals(DocumentPageMethod.OCR,result.pages[1].method)
            val scan=result.pages[1].text.replace(Regex("\\s"),"")
            assertTrue("scan text was not recovered",scan.contains("2026") && scan.contains("扫描测试"))
            val selected=ref.copy(documentSelection=DocumentSelection(2,2,DocumentOcrScript.CHINESE))
            val events=AndroidDocumentTextService(app,app.attachments).readPdfEvents(session,selected).toList()
            val selectedResult=(events.last() as DocumentReadEvent.Complete).document
            assertEquals(listOf(2),selectedResult.pages.map { it.number })
            assertEquals(2,selectedResult.pageCount)
            assertTrue(events.filterIsInstance<DocumentReadEvent.Progress>().all { it.page == 2 && it.firstPage == 2 && it.lastPage == 2 })
            assertTrue(events.filterIsInstance<DocumentReadEvent.Progress>().any { it.method == DocumentPageMethod.OCR })
            assertNoTemporarySources(app)
        } finally { chat.deleteSession(session) }
    }
    @Test fun damagedAndPasswordProtectedPdfsDoNotReturnEmptySuccessOrLeaveTemporaryFiles() = runBlocking {
        val app=app();val chat=app.ready.value!!.getOrThrow();val session=chat.createSession().id
        try {
            val reader=AndroidDocumentTextService(app,app.attachments)
            for (bytes in listOf("%PDF-1.7\nbroken".toByteArray(),pdf(password=true))) {
                val ref=import(app,session,bytes)
                try { reader.readPdf(session,ref); fail("unreadable PDF returned success") }
                catch (expected: ModelException) { assertTrue(expected.message!!.isNotBlank()) }
                assertNoTemporarySources(app)
            }
        } finally { chat.deleteSession(session) }
    }
    @Test fun cancellationDuringPrivateReadCleansStagingAndAllowsTheNextDocument() = runBlocking {
        val app=app();val chat=app.ready.value!!.getOrThrow();val session=chat.createSession().id
        try {
            val ref=import(app,session,pdf());val loaded=CompletableDeferred<Unit>();val hold=CompletableDeferred<Unit>()
            val delayed = object : ChatAttachmentStore {
                override suspend fun import(sessionId:String,name:String,mimeType:String,input:InputStream,authorize:suspend ()->Unit):ChatAttachment=error("unused")
                override suspend fun remove(sessionId:String,attachment:ChatAttachment,authorize:suspend ()->Unit)=error("unused")
                override suspend fun read(sessionId:String,attachment:ChatAttachment,authorize:suspend ()->Unit):ByteArray {
                    val bytes=app.attachments.read(sessionId,attachment,authorize)
                    try { loaded.complete(Unit);hold.await();return bytes }
                    catch (cancelled:CancellationException) { bytes.fill(0);throw cancelled }
                }
            }
            val reader=AndroidDocumentTextService(app,delayed)
            val pending=launch { reader.readPdf(session,ref) }
            withTimeout(10_000) { loaded.await() };pending.cancelAndJoin();assertNoTemporarySources(app)
            hold.complete(Unit)
            assertEquals("SHORT",reader.readPdf(session,ref).pages.single().text)
            assertNoTemporarySources(app)
        } finally { chat.deleteSession(session) }
    }
}
