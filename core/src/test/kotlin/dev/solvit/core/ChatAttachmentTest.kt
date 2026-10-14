package dev.solvit.core

import java.util.UUID
import kotlin.test.*

class ChatAttachmentTest {
    @Test fun changingAttachmentsInvalidatesHistorySummaryWithoutChangingLegacyTextDigests() {
        val reference = ChatAttachment(UUID.randomUUID().toString(), "note.txt", "text/plain", 4, "a".repeat(64))
        val plain = ChatMessage("user", "chat", Role.USER, "question")
        val attached = plain.copy(attachments = listOf(reference))
        assertEquals(contextPrefixDigest(listOf(plain)), contextPrefixDigest(listOf(plain.copy(attachments = emptyList()))))
        assertNotEquals(contextPrefixDigest(listOf(plain)), contextPrefixDigest(listOf(attached)))
        assertNotEquals(contextPrefixDigest(listOf(attached)), contextPrefixDigest(listOf(attached.copy(attachments = listOf(reference.copy(sha256 = "b".repeat(64)))))))
    }
    @Test fun imageBytesAreRedactedAndVisualInputsReceiveABudgetReserve() {
        val image = ModelImage(UUID.randomUUID().toString(), "image/png", "AQIDBA==")
        assertFalse(image.toString().contains(image.base64))
        val meter = Utf8ContextTokenMeter()
        val plain = ModelMessage(Role.USER, "question")
        assertEquals(4096L, meter.messageTokens(plain.copy(images = listOf(image))) - meter.messageTokens(plain))
        assertFailsWith<IllegalArgumentException> { plain.copy(role = Role.SYSTEM, images = listOf(image)) }
        assertFailsWith<IllegalArgumentException> { ModelImage(image.attachmentId, "text/plain", image.base64) }
        assertFailsWith<IllegalArgumentException> { ModelImage(image.attachmentId, "image/png", "not base64!") }
    }
}
