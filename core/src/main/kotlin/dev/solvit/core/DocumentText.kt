package dev.solvit.core

import java.util.UUID

enum class DocumentPageMethod { TEXT, OCR, EMPTY }

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
class DocumentText(val attachmentId: String, val sha256: String, val pageCount: Int, pages: List<DocumentTextPage>) {
    val pages: List<DocumentTextPage> = pages.toList()
    init {
        require(UUID.fromString(attachmentId).toString() == attachmentId) { "文档附件标识无效" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "文档内容版本无效" }
        require(pageCount in 1..MAX_PAGES && this.pages.size == pageCount &&
            this.pages.map { it.number } == (1..pageCount).toList()) { "文档超过页数限制或读取结果不完整" }
        require(this.pages.sumOf { it.text.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_TEXT_BYTES) { "文档文字超过256KiB读取限制" }
        require(this.pages.any { it.method != DocumentPageMethod.EMPTY }) { "文档没有可读取文字，请检查扫描质量" }
    }
    override fun toString() = "DocumentText(attachmentId=$attachmentId, pageCount=$pageCount, text=<redacted>)"
    companion object {
        const val MAX_PAGES = 40
        const val MAX_TEXT_BYTES = 256 * 1024
    }
}

/** The host resolves private attachment bytes. Callers never provide arbitrary paths or URLs. */
interface DocumentTextService {
    suspend fun readPdf(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit = {}): DocumentText
}
val DocumentTextServiceKey = ServiceKey("document.text", DocumentTextService::class)
