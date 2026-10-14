package dev.solvit.core

/** A host-generated thumbnail, never the original file or a filesystem grant. */
class PageFilePicture(val width: Int, val height: Int, jpeg: ByteArray) : AutoCloseable {
    private val pixels = jpeg.copyOf()
    private var closed = false
    init {
        require(width in 1..640 && height in 1..640)
        require(jpeg.size in 4..MAX_BYTES && jpeg[0] == 0xff.toByte() && jpeg[1] == 0xd8.toByte() &&
            jpeg[jpeg.lastIndex - 1] == 0xff.toByte() && jpeg.last() == 0xd9.toByte())
    }
    @Synchronized fun jpegBytes(): ByteArray { check(!closed); return pixels.copyOf() }
    @Synchronized override fun close() { closed = true; pixels.fill(0) }
    override fun toString() = "PageFilePicture(width=$width, height=$height, pixels=<redacted>)"
    companion object { const val MAX_BYTES = 64 * 1024 }
}

/** Native decoding remains bounded and rechecks the current page authority. */
fun interface PageFilePictureRenderer {
    suspend fun render(document: PageBinaryDocument, authorize: suspend () -> Unit): PageFilePicture
}
