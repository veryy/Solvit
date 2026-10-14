package dev.solvit.core

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class DocumentPageMethod { TEXT, OCR, EMPTY }
enum class DocumentOcrScript { LATIN, CHINESE, JAPANESE, KOREAN }

data class DocumentSelection(val firstPage: Int = 1, val lastPage: Int? = null,
    val ocrScript: DocumentOcrScript = DocumentOcrScript.CHINESE) {
    init {
        require(firstPage in 1..DocumentText.MAX_TOTAL_PAGES && (lastPage == null ||
            lastPage in firstPage..DocumentText.MAX_TOTAL_PAGES && lastPage - firstPage < DocumentText.MAX_PAGES)) { "PDF选取页范围无效，每次最多40页" }
    }
}

/** Page numbers are one-based; reading mode stays visible alongside extracted content. */
class DocumentTextPage(val number: Int, val text: String, val method: DocumentPageMethod) {
    init {
        require(number > 0) { "文档页码无效" }
        require(text.length <= DocumentText.MAX_TEXT_BYTES && '\u0000' !in text) { "文档文字超过限制或包含二进制内容" }
        require((method == DocumentPageMethod.EMPTY) == text.isBlank()) { "文档页面读取状态与内容不匹配" }
    }
    override fun toString() = "DocumentTextPage(number=$number, method=$method, text=<redacted>)"
}

/** Complete, bounded document text. A missing page must never silently become model input. */
class DocumentText(val attachmentId: String, val sha256: String, val pageCount: Int, pages: List<DocumentTextPage>,
    val firstPage: Int = 1, val lastPage: Int = pageCount, val ocrScript: DocumentOcrScript = DocumentOcrScript.CHINESE) {
    val pages: List<DocumentTextPage> = pages.toList()
    init {
        require(UUID.fromString(attachmentId).toString() == attachmentId) { "文档附件标识无效" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "文档内容版本无效" }
        require(pageCount in 1..MAX_TOTAL_PAGES && firstPage in 1..pageCount && lastPage in firstPage..pageCount &&
            lastPage - firstPage < MAX_PAGES && this.pages.map { it.number } == (firstPage..lastPage).toList()) { "文档超过页数限制或读取结果不完整" }
        require(this.pages.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_TEXT_BYTES) { "文档文字超过256KiB读取限制" }
        require(this.pages.any { it.method != DocumentPageMethod.EMPTY }) { "文档没有可读取文字，请检查扫描质量" }
    }
    override fun toString() = "DocumentText(attachmentId=$attachmentId, pageCount=$pageCount, selected=$firstPage..$lastPage, text=<redacted>)"
    companion object {
        const val MAX_PAGES = 40
        const val MAX_TOTAL_PAGES = 5000
        const val MAX_TEXT_BYTES = 256 * 1024
    }
}

sealed interface DocumentReadEvent {
    data class Progress(val page: Int, val pageCount: Int, val firstPage: Int, val lastPage: Int,
        val method: DocumentPageMethod? = null) : DocumentReadEvent
    class Complete(val document: DocumentText) : DocumentReadEvent {
        override fun toString() = "DocumentReadEvent.Complete($document)"
    }
}

/** One bounded original page. PNG pixels are private data, never included in diagnostics. */
class DocumentPagePreview(val attachmentId: String, val sha256: String, val page: Int,
    val pageCount: Int, val width: Int, val height: Int, png: ByteArray) {
    private val pixels = png.copyOf()
    fun pngBytes(): ByteArray = pixels.copyOf()
    init {
        require(UUID.fromString(attachmentId).toString() == attachmentId)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(pageCount in 1..DocumentText.MAX_TOTAL_PAGES && page in 1..pageCount)
        require(width in 1..1600 && height in 1..1600 && width.toLong() * height <= 2_000_000)
        require(pixels.size in 8..MAX_PNG_BYTES && pixels.take(8) == listOf(137,80,78,71,13,10,26,10).map { it.toByte() })
    }
    override fun toString() = "DocumentPagePreview(page=$page, pageCount=$pageCount, pixels=<redacted>)"
    companion object { const val MAX_PNG_BYTES = 8 * 1024 * 1024 }
}

/** The host resolves private attachment bytes. Callers never provide arbitrary paths or URLs. */
interface DocumentTextService {
    suspend fun previewPdfPage(sessionId: String, attachment: ChatAttachment, page: Int,
        authorize: suspend () -> Unit = {}): DocumentPagePreview = throw ModelException("PDF原页预览未启用")
    suspend fun readPdf(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit = {}): DocumentText
    fun readPdfEvents(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit = {}): Flow<DocumentReadEvent> = flow {
        emit(DocumentReadEvent.Complete(readPdf(sessionId, attachment, authorize)))
    }
}
val DocumentTextServiceKey = ServiceKey("document.text", DocumentTextService::class)
