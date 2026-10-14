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
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bundled OCR models work offline; script selection does not depend on Google Play downloads. */
enum class DocumentOcrScript { LATIN, CHINESE, JAPANESE, KOREAN }

class AndroidDocumentTextService(context: Context, private val attachments: ChatAttachmentStore,
    private val script: DocumentOcrScript = DocumentOcrScript.CHINESE) : DocumentTextService {
    private val cache = File(context.applicationContext.cacheDir, "document-reader")
    private val reads = Mutex()
    // A cancelled caller must not recycle pixels while ML Kit is still using them.
    private val ocrSlot = Semaphore(1)
    init { PDFBoxResourceLoader.init(context.applicationContext) }

    override suspend fun readPdf(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit): DocumentText = reads.withLock {
        withContext(Dispatchers.IO) {
            require(attachment.mimeType == "application/pdf") { "文档读取仅支持PDF" }
            authorize(); currentCoroutineContext().ensureActive()
            check(cache.mkdirs() || cache.isDirectory) { "无法创建文档临时目录" }
            val directory = File(cache, UUID.randomUUID().toString())
            check(directory.mkdir()) { "无法创建文档临时目录" }
            try {
                val source = File(directory, "source.pdf")
                val bytes = attachments.read(sessionId, attachment, authorize)
                try {
                    require(bytes.size.toLong() == attachment.sizeBytes && bytes.size <= ChatAttachment.MAX_ATTACHMENT_BYTES) { "文档大小与附件记录不一致" }
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(hash == attachment.sha256) { "文档内容校验失败" }
                    require(bytes.copyOfRange(0, min(bytes.size, 1024)).toString(Charsets.US_ASCII).contains("%PDF-")) { "文件不是有效PDF" }
                    source.outputStream().use { it.write(bytes) }
                } finally { bytes.fill(0) }
                authorize(); currentCoroutineContext().ensureActive()
                val memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 64L * 1024 * 1024).setTempDir(directory)
                PDDocument.load(source, memory).use { document ->
                    if (document.isEncrypted) throw ModelException("PDF受密码保护，请先导出未加密副本")
                    if (!document.currentAccessPermission.canExtractContent()) throw ModelException("PDF禁止文字提取，请使用允许读取的副本")
                    val count = document.numberOfPages
                    if (count !in 1..DocumentText.MAX_PAGES) throw ModelException("PDF为空或超过40页读取限制，请选择或拆分需要的页面")
                    val pages = mutableListOf<DocumentTextPage>()
                    var total = 0
                    var renderer: PdfRenderer? = null
                    var descriptor: ParcelFileDescriptor? = null
                    try {
                        for (number in 1..count) {
                            authorize(); currentCoroutineContext().ensureActive()
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
                                text = renderAndRecognize(checkNotNull(renderer), number - 1).trim()
                                method = if (text.isBlank()) DocumentPageMethod.EMPTY else DocumentPageMethod.OCR
                            }
                            currentCoroutineContext().ensureActive(); authorize()
                            total += text.toByteArray(Charsets.UTF_8).size
                            if (total > DocumentText.MAX_TEXT_BYTES) throw ModelException("PDF文字超过256KiB读取限制，请选择或拆分需要的页面")
                            pages += DocumentTextPage(number, text, method)
                        }
                    } finally { renderer?.close(); descriptor?.close() }
                    DocumentText(attachment.id, attachment.sha256, count, pages)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: InvalidPasswordException) { throw ModelException("PDF受密码保护，请先导出未加密副本") }
            catch (failure: ModelException) { throw failure }
            catch (_: IOException) { throw ModelException("PDF损坏或无法读取，请检查原文件") }
            finally { directory.deleteRecursively() }
        }
    }

    private fun recognizer(): TextRecognizer = when (script) {
        DocumentOcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        DocumentOcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        DocumentOcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        DocumentOcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private suspend fun renderAndRecognize(renderer: PdfRenderer, index: Int): String {
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
            client = recognizer()
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
        context.register(DocumentTextServiceKey, AndroidDocumentTextService(this.context, context.require(ChatAttachmentStoreKey)))
    }
}
