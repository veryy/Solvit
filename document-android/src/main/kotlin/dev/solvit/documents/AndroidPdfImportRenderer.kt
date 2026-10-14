package dev.solvit.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.solvit.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One selected PDF and one page. No caller-supplied paths or automatic workspace writes. */
class AndroidPdfImportRenderer(context: Context) : PagePdfPreviewRenderer {
    private val sources = PdfSourceAccess(File(context.applicationContext.cacheDir, "workspace-pdf-previews"))
    init { PDFBoxResourceLoader.init(context.applicationContext) }
    override suspend fun render(document: PageBinaryDocument, page: Int,
        authorize: suspend () -> Unit): PagePdfPreview {
        require(document.mimeType == "application/pdf") { "此格式没有PDF预览" }
        require(page in 1..DocumentText.MAX_TOTAL_PAGES) { "PDF页码无效" }
        return sources.read(document.sizeBytes, document.sha256, authorize, { document.readBytes() }) { directory, source ->
            val writer = PagePreviewTextWriter()
            val count = readablePdf(directory, source).use { pdf ->
                require(pdf.numberOfPages in 1..DocumentText.MAX_TOTAL_PAGES && page <= pdf.numberOfPages) { "PDF预览页超出文件范围" }
                authorize(); currentCoroutineContext().ensureActive()
                try { PDFTextStripper().apply { startPage = page; endPage = page; sortByPosition = true }.writeText(pdf, writer) }
                catch (_: PagePreviewTextWriter.LimitReached) { /* The page explicitly reports a truncated text preview. */ }
                pdf.numberOfPages
            }
            authorize(); currentCoroutineContext().ensureActive()
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    check(renderer.pageCount == count) { "PDF页面读取结果不一致" }
                    renderer.openPage(page - 1).use { original ->
                        require(original.width in 1..100_000 && original.height in 1..100_000) { "PDF页面尺寸无效" }
                        val scale = min(1.0, 640.0 / max(original.width, original.height))
                        val bitmap = Bitmap.createBitmap(max(1, (original.width * scale).toInt()), max(1, (original.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            original.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            authorize(); currentCoroutineContext().ensureActive()
                            val picture = encodeFilePicture(bitmap, authorize)
                            try {
                                authorize(); currentCoroutineContext().ensureActive()
                                PagePdfPreview(page, count, picture, writer.text(), writer.truncated)
                            } catch (failure: Throwable) { picture.close(); throw failure }
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }
}
