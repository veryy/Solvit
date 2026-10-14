package dev.solvit.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.documents.AndroidFilePictureRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Native decode/EXIF/encode on Android, not a mocked bitmap implementation. */
class FilePictureDeviceTest {
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            return ByteArrayOutputStream().use { check(bitmap.compress(format, 100, it)); it.toByteArray() }
        } finally { bitmap.recycle() }
    }

    @Test fun downsampledPictureDecodesAndRetainsTheCompleteOriginalForImport() = runBlocking<Unit> {
        val bytes = image(2000, 1000, Bitmap.CompressFormat.PNG)
        val original = PageBinaryDocument("image.png", "image/png", bytes)
        try {
            AndroidFilePictureRenderer().render(original) {}.use { preview ->
                assertTrue(preview.width <= 640 && preview.height <= 640)
                assertEquals(2, preview.width / preview.height)
                val jpeg = preview.jpegBytes(); assertTrue(jpeg.size <= PageFilePicture.MAX_BYTES)
                val decoded = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                try {
                    assertEquals(preview.width, decoded.width); assertEquals(preview.height, decoded.height)
                    val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
                    assertTrue(Color.red(center) > 220 && Color.green(center) < 30 && Color.blue(center) < 30)
                } finally { decoded.recycle() }
                assertArrayEquals(bytes, original.readBytes())
            }
        } finally { original.close() }
    }

    @Test fun exifRotationIsShownButNeverWrittenBackToTheSelectedFile() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("picture-orientation-", ".jpg", context.cacheDir)
        try {
            file.writeBytes(image(96, 40, Bitmap.CompressFormat.JPEG))
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes()
            }
            val bytes = file.readBytes()
            PageBinaryDocument("rotated.jpg", "image/jpeg", bytes).use { original ->
                AndroidFilePictureRenderer().render(original) {}.use { preview ->
                    assertEquals(40, preview.width); assertEquals(96, preview.height)
                    assertArrayEquals(bytes, original.readBytes())
                }
            }
        } finally { file.delete() }
    }

    @Test fun malformedInputAndAuthorizationRevocationReturnNoPicture() = runBlocking<Unit> {
        PageBinaryDocument("broken.png", "image/png", byteArrayOf(1, 2, 3)).use { original ->
            try { AndroidFilePictureRenderer().render(original) {}; fail("Accepted invalid image") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.isNotBlank()) }
        }
        val bytes = image(120, 60, Bitmap.CompressFormat.PNG)
        PageBinaryDocument("picture.png", "image/png", bytes).use { original ->
            var checks = 0
            try { AndroidFilePictureRenderer().render(original) { checks++; check(checks < 3) { "fixture revoked" } }; fail("Returned revoked pixels") }
            catch (expected: IllegalStateException) { assertEquals("fixture revoked", expected.message) }
            assertArrayEquals(bytes, original.readBytes())
        }
    }
}
