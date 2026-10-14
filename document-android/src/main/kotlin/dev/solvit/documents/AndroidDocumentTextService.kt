package dev.solvit.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.File
import java.io.Writer
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bundled OCR models work offline; script selection does not depend on Google Play downloads. */
class AndroidDocumentTextService(context: Context, private val attachments: ChatAttachmentStore,
    private val script: DocumentOcrScript = DocumentOcrScript.CHINESE) : DocumentTextService {
    private val cache = File(context.applicationContext.cacheDir, "document-reader")
    private val sources = PdfSourceAccess(cache)
    // A cancelled caller must not recycle pixels while ML Kit is still using them.
    private val ocrSlot = Semaphore(1)
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    override suspend fun readPdf(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit): DocumentText = read(sessionId, attachment, authorize) {}

    override fun readPdfEvents(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit): Flow<DocumentReadEvent> = channelFlow {
        val document = read(sessionId, attachment, authorize) { trySend(it) }
        send(DocumentReadEvent.Complete(document))
    }

    private suspend fun <T> withSource(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit, action: suspend (File, File) -> T): T {
        require(attachment.mimeType == "application/pdf") { "文档读取仅支持PDF" }
        return sources.read(attachment.sizeBytes, attachment.sha256, authorize,
            { attachments.read(sessionId, attachment, authorize) }, action)
    }

    private suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit,
        progress: (DocumentReadEvent.Progress) -> Unit): DocumentText = withSource(sessionId, attachment, authorize) { directory, source ->
        readablePdf(directory, source).use { document ->
            val count = document.numberOfPages
            if (count !in 1..DocumentText.MAX_TOTAL_PAGES) throw ModelException("PDF为空或超过5000页文件限制")
            val selection = attachment.documentSelection ?: DocumentSelection(ocrScript=script)
            val first = selection.firstPage
            val last = selection.lastPage ?: count
            if (first > count || last > count || last - first >= DocumentText.MAX_PAGES)
                throw ModelException("PDF选取范围超出文件或超过40页，请调整读取页范围")
            val pages = mutableListOf<DocumentTextPage>()
            var total = 0
            var renderer: PdfRenderer? = null
            var descriptor: ParcelFileDescriptor? = null
            try {
                for (number in first..last) {
                    authorize(); currentCoroutineContext().ensureActive()
                    progress(DocumentReadEvent.Progress(number, count, first, last))
                    val writer = BoundedTextWriter(DocumentText.MAX_TEXT_BYTES - total)
                    PDFTextStripper().apply { startPage=number; endPage=number; sortByPosition=true }.writeText(document, writer)
                    var text = writer.value().trim()
                    var method = DocumentPageMethod.TEXT
                    // Short text is valid; OCR is per empty page, including mixed documents.
                    if (text.isBlank()) {
                        if (renderer == null) {
                            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
                            renderer = PdfRenderer(checkNotNull(descriptor))
                            check(renderer.pageCount == count) { "PDF页面读取结果不一致" }
                        }
                        progress(DocumentReadEvent.Progress(number, count, first, last, DocumentPageMethod.OCR))
                        text = renderAndRecognize(checkNotNull(renderer), number - 1, selection.ocrScript).trim()
                        method = if (text.isBlank()) DocumentPageMethod.EMPTY else DocumentPageMethod.OCR
                    }
                    currentCoroutineContext().ensureActive(); authorize()
                    total += text.toByteArray(Charsets.UTF_8).size
                    if (total > DocumentText.MAX_TEXT_BYTES) throw ModelException("PDF文字超过256KiB读取限制，请选择或拆分需要的页面")
                    pages += DocumentTextPage(number, text, method)
                    progress(DocumentReadEvent.Progress(number, count, first, last, method))
                }
            } finally { renderer?.close(); descriptor?.close() }
            DocumentText(attachment.id, attachment.sha256, count, pages, first, last, selection.ocrScript)
        }    }

    override suspend fun previewPdfPage(sessionId: String, attachment: ChatAttachment, page: Int,
        authorize: suspend () -> Unit): DocumentPagePreview = withSource(sessionId, attachment, authorize) { directory, source ->
        val count = readablePdf(directory, source).use { document ->
            document.numberOfPages
        }
        val selection = attachment.documentSelection ?: DocumentSelection(ocrScript=script)
        require(count in 1..DocumentText.MAX_TOTAL_PAGES && page in selection.firstPage..(selection.lastPage ?: count) && page <= count) { "PDF预览页超出选取范围" }
        authorize(); currentCoroutineContext().ensureActive()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                check(renderer.pageCount == count) { "PDF页面读取结果不一致" }
                renderer.openPage(page - 1).use { original ->
                    require(original.width in 1..100_000 && original.height in 1..100_000) { "PDF页面尺寸无效" }
                    val scale = min(1600.0 / max(original.width, original.height), sqrt(2_000_000.0 / (original.width.toDouble() * original.height)))
                    val bitmap = Bitmap.createBitmap(max(1,(original.width * scale).toInt()),max(1,(original.height * scale).toInt()),Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        original.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        currentCoroutineContext().ensureActive(); authorize()
                        val output = ByteArrayOutputStream()
                        val bounded = object : OutputStream() {
                            override fun write(value: Int) { require(output.size() < DocumentPagePreview.MAX_PNG_BYTES) { "PDF预览图超过容量限制" }; output.write(value) }
                            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                require(length <= DocumentPagePreview.MAX_PNG_BYTES - output.size()) { "PDF预览图超过容量限制" }
                                output.write(bytes,offset,length)
                            }
                        }
                        check(bitmap.compress(Bitmap.CompressFormat.PNG,100,bounded)) { "PDF原页预览失败" }
                        currentCoroutineContext().ensureActive(); authorize()
                        DocumentPagePreview(attachment.id,attachment.sha256,page,count,bitmap.width,bitmap.height,output.toByteArray())
                    } finally { bitmap.recycle() }
                }
            }
        }
    }

    private fun recognizer(script: DocumentOcrScript): TextRecognizer = when (script) {
        DocumentOcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        DocumentOcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        DocumentOcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        DocumentOcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private suspend fun renderAndRecognize(renderer: PdfRenderer, index: Int, script: DocumentOcrScript): String {
        ocrSlot.acquire()
        var bitmap: Bitmap? = null
        var client: TextRecognizer? = null
        var handedOff = false
        try {
            currentCoroutineContext().ensureActive()
            renderer.openPage(index).use { page ->
                require(page.width in 1..100_000 && page.height in 1..100_000) { "PDF页面尺寸无效" }
                val scale = min(2200.0 / max(page.width, page.height), sqrt(4_000_000.0 / (page.width.toDouble() * page.height)))
                bitmap = Bitmap.createBitmap(max(1, (page.width * scale).toInt()), max(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                checkNotNull(bitmap).eraseColor(Color.WHITE)
                page.render(checkNotNull(bitmap), null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            currentCoroutineContext().ensureActive()
            client = recognizer(script)
            val pixels = checkNotNull(bitmap)
            val recognizer = checkNotNull(client)
            val task = recognizer.process(InputImage.fromBitmap(pixels, 0))
            handedOff = true
            return suspendCancellableCoroutine { continuation ->
                task.addOnCompleteListener(Executor { it.run() }) { completed ->
                    try {
                        if (continuation.isActive) {
                            if (completed.isSuccessful) continuation.resume(completed.result.text)
                            else continuation.resumeWithException(ModelException("扫描页识别失败，请检查文件或重新选择识别语言"))
                        }
                    } finally { pixels.recycle(); runCatching { recognizer.close() }; ocrSlot.release() }
                }
            }
        } finally {
            if (!handedOff) { bitmap?.recycle(); runCatching { client?.close() }; ocrSlot.release() }
        }
    }

    /** Bounds output during extraction, rather than after an unbounded getText allocation. */
    private class BoundedTextWriter(private val limit: Int) : Writer() {
        private val value = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (value.length + length > limit) throw ModelException("PDF文字超过256KiB读取限制，请选择或拆分需要的页面")
            value.append(chars, offset, length)
        }
        fun value(): String {
            val text = value.toString()
            if (text.toByteArray(Charsets.UTF_8).size > limit) throw ModelException("PDF文字超过256KiB读取限制，请选择或拆分需要的页面")
            return text
        }
        override fun flush() = Unit
        override fun close() = Unit
    }
}

class DocumentTextPlugin(private val context: Context) : SolvitPlugin {
    override val id = "builtin.documents.pdf"
    override val requires = setOf(ChatAttachmentStoreKey)
    override val provides = setOf(DocumentTextServiceKey)
    override suspend fun enable(context: PluginContext) {
        withContext(Dispatchers.IO) {
            for (name in listOf("document-reader", "workspace-pdf-previews"))
                DocumentSourceCache(File(this@DocumentTextPlugin.context.applicationContext.cacheDir, name)).removeInterruptedReads()
        }
        context.register(DocumentTextServiceKey, AndroidDocumentTextService(this.context, context.require(ChatAttachmentStoreKey)))
    }
}
