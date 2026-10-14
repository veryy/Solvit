package dev.solvit.app

import android.app.Application
import android.graphics.Bitmap
import dev.solvit.core.ChatAttachment
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatAttachmentValidationTest {
    private fun attachment(mime: String, bytes: ByteArray) = ChatAttachment(UUID.randomUUID().toString(), "fixture", mime,
        bytes.size.toLong(), "a".repeat(64))
    @Test fun actualPngIsAcceptedButInvalidImageAndMimeSpoofAreRejected() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray()
        }
        bitmap.recycle()
        validateChatAttachment(attachment("image/png", bytes), bytes)
        assertFailsWith<IllegalArgumentException> { validateChatAttachment(attachment("image/jpeg", bytes), bytes) }
        assertFailsWith<IllegalArgumentException> { validateChatAttachment(attachment("image/png", byteArrayOf(1)), byteArrayOf(1)) }
    }
    @Test fun invalidTextBinaryAndUnsupportedDocumentsAreExplicitlyRejected() {
        val good = "中文 😀 code".toByteArray()
        validateChatAttachment(attachment("text/plain", good), good)
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        assertFailsWith<IllegalStateException> { validateChatAttachment(attachment("text/plain", invalid), invalid) }
        val binary = byteArrayOf(0, 1)
        assertFailsWith<IllegalArgumentException> { validateChatAttachment(attachment("text/plain", binary), binary) }
        assertFailsWith<IllegalArgumentException> { validateChatAttachment(attachment("application/pdf", good), good) }
    }
}
