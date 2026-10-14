package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class AttachmentContentResolverTest {
    private class Store(val session: String, val bytes: Map<String, ByteArray>) : ChatAttachmentStore {
        var reads = 0
        override suspend fun import(sessionId: String, name: String, mimeType: String, input: InputStream,
            authorize: suspend () -> Unit): ChatAttachment = error("unused")
        override suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit): ByteArray {
            check(sessionId == session); authorize(); reads++
            return bytes.getValue(attachment.id).copyOf()
        }
        override suspend fun remove(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit) = error("unused")
    }
    private fun reference(name: String, mime: String, bytes: ByteArray) = ChatAttachment(UUID.randomUUID().toString(), name,
        mime, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
    @Test fun textAndImagesBecomeActualUserContentWithStableReferences() = runTest {
        val textBytes = "中文 😀\nprint(1)".toByteArray()
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val text = reference("code.py", "text/plain", textBytes)
        val image = reference("image.png", "image/png", imageBytes)
        val store = Store("chat", mapOf(text.id to textBytes, image.id to imageBytes))
        val result = AttachmentContentResolver(store).resolve("chat", ModelMessage(Role.USER, "解释", attachments = listOf(text, image)))
        assertTrue(result.content.contains("中文 😀\nprint(1)"))
        assertTrue(result.content.contains(text.sha256))
        assertEquals(listOf(text, image), result.attachments)
        assertEquals("AQIDBA==", result.images.single().base64)
        assertEquals(2, store.reads)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), imageBytes)
    }
    @Test fun invalidUtf8UnsupportedFormatsAndRevocationDoNotReturnPartialModelInput() = runTest {
        val invalid = byteArrayOf(0xc3.toByte(), 0x28)
        val text = reference("bad.txt", "text/plain", invalid)
        val pdf = reference("report.pdf", "application/pdf", byteArrayOf(1))
        val store = Store("chat", mapOf(text.id to invalid))
        val resolver = AttachmentContentResolver(store)
        assertFailsWith<ModelException> { resolver.resolve("chat", ModelMessage(Role.USER, "", attachments = listOf(text))) }
        assertFailsWith<ModelException> { resolver.resolve("chat", ModelMessage(Role.USER, "", attachments = listOf(pdf))) }
        assertEquals(1, store.reads)
        assertFailsWith<IllegalStateException> {
            resolver.resolve("chat", ModelMessage(Role.USER, "", attachments = listOf(text))) { error("revoked") }
        }
        assertEquals(1, store.reads)
    }
}
