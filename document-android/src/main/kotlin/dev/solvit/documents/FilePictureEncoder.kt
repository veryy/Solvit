package dev.solvit.documents

import android.graphics.Bitmap
import dev.solvit.core.PageFilePicture
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

/** The caller owns the input; this encoder closes only its own resized bitmap. */
internal suspend fun encodeFilePicture(input: Bitmap, authorize: suspend () -> Unit): PageFilePicture {
    require(input.width in 1..640 && input.height in 1..640)
    var bitmap = input
    try {
        for (quality in listOf(85, 60, 35)) {
            authorize(); currentCoroutineContext().ensureActive()
            val output = ByteArrayOutputStream()
            val bounded = object : OutputStream() {
                override fun write(value: Int) { if (output.size() >= PageFilePicture.MAX_BYTES) throw PictureTooLarge(); output.write(value) }
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    if (count > PageFilePicture.MAX_BYTES - output.size()) throw PictureTooLarge()
                    output.write(data, offset, count)
                }
            }
            val complete = try { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bounded) }
                catch (_: PictureTooLarge) { false }
            authorize(); currentCoroutineContext().ensureActive()
            if (complete) {
                val jpeg = output.toByteArray()
                try { return PageFilePicture(bitmap.width, bitmap.height, jpeg) }
                finally { jpeg.fill(0) }
            }
            if (quality == 60) bitmap = Bitmap.createScaledBitmap(input, max(1, input.width / 2), max(1, input.height / 2), true)
        }
        error("图片预览超过容量限制")
    } finally { if (bitmap !== input) bitmap.recycle() }
}
private class PictureTooLarge : RuntimeException()
