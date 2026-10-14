package dev.solvit.core

import java.io.Writer

/** A single host-rendered page; the selected PDF remains the only import source. */
class PagePdfPreview(val page: Int, val pageCount: Int, val picture: PageFilePicture,
    val text: String, val truncated: Boolean) : AutoCloseable {
    init {
        require(pageCount in 1..DocumentText.MAX_TOTAL_PAGES && page in 1..pageCount)
        require(text.length <= PagePreviewTextWriter.MAX_CHARS && '\u0000' !in text)
    }
    override fun close() = picture.close()
    override fun toString() = "PagePdfPreview(page=$page, pageCount=$pageCount, truncated=$truncated, content=<redacted>)"
}

fun interface PagePdfPreviewRenderer {
    suspend fun render(document: PageBinaryDocument, page: Int, authorize: suspend () -> Unit): PagePdfPreview
}

/** Stops extraction at an explicit preview boundary, without allocating a complete page string. */
class PagePreviewTextWriter(private val limit: Int = MAX_CHARS) : Writer() {
    private val buffer = StringBuilder()
    var truncated = false
        private set
    init { require(limit in 1..MAX_CHARS) }
    override fun write(chars: CharArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= chars.size - length)
        if (truncated) throw LimitReached()
        val count = minOf(length, limit + 1 - buffer.length)
        buffer.append(chars, offset, count)
        if (buffer.length > limit) { truncated = true; throw LimitReached() }
    }
    fun text(): String {
        var end = minOf(buffer.length, limit)
        if (end > 0 && buffer[end - 1].isHighSurrogate()) end--
        return buffer.substring(0, end)
    }
    override fun flush() = Unit
    override fun close() = Unit
    class LimitReached : RuntimeException()
    companion object { const val MAX_CHARS = 8192 }
}
