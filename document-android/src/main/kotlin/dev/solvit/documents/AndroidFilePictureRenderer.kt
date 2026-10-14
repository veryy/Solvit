package dev.solvit.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import dev.solvit.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max

/** Selected images only. No paths, URLs, EXIF metadata or original pixels reach the page. */
class AndroidFilePictureRenderer : PageFilePictureRenderer {
    override suspend fun render(document: PageBinaryDocument, authorize: suspend () -> Unit): PageFilePicture = withContext(Dispatchers.IO) {
        authorize(); currentCoroutineContext().ensureActive()
        require(document.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "此格式没有图片预览" }
        val bytes = document.readBytes()
        var decoded: Bitmap? = null
        var thumbnail: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..32_768 && bounds.outHeight in 1..32_768 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "图片尺寸无效或超过预览限制" }
            var sample = 1
            while ((bounds.outWidth + sample - 1) / sample > 640 || (bounds.outHeight + sample - 1) / sample > 640) sample *= 2
            authorize(); currentCoroutineContext().ensureActive()
            val source = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
            })) { "无法解码图片预览" }
            decoded = source
            require(source.width in 1..640 && source.height in 1..640) { "图片解码尺寸超过预览限制" }
            val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            var rotated: Bitmap? = null
            try {
                val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, transform, true)
                if (oriented !== source) rotated = oriented
                val flat = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
                thumbnail = flat
                flat.eraseColor(Color.WHITE); Canvas(flat).drawBitmap(oriented, 0f, 0f, null)
            } finally { rotated?.recycle() }
            authorize(); currentCoroutineContext().ensureActive()
            for (quality in listOf(85, 60, 35)) {
                val bitmap = checkNotNull(thumbnail)
                val output = ByteArrayOutputStream()
                val bounded = object : OutputStream() {
                    override fun write(value: Int) { if (output.size() >= PageFilePicture.MAX_BYTES) throw TooLarge(); output.write(value) }
                    override fun write(data: ByteArray, offset: Int, count: Int) {
                        if (count > PageFilePicture.MAX_BYTES - output.size()) throw TooLarge()
                        output.write(data, offset, count)
                    }
                }
                val complete = try { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bounded) } catch (_: TooLarge) { false }
                authorize(); currentCoroutineContext().ensureActive()
                if (complete) {
                    val jpeg = output.toByteArray()
                    try { return@withContext PageFilePicture(bitmap.width, bitmap.height, jpeg) }
                    finally { jpeg.fill(0) }
                }
                if (quality == 60) {
                    val smaller = Bitmap.createScaledBitmap(bitmap, max(1, bitmap.width / 2), max(1, bitmap.height / 2), true)
                    thumbnail = smaller; bitmap.recycle()
                }
            }
            error("图片预览超过容量限制")
        } finally { thumbnail?.recycle(); decoded?.recycle(); bytes.fill(0) }
    }
    private class TooLarge : RuntimeException()
}
