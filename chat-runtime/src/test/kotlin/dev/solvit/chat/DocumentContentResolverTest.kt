package dev.solvit.chat

import dev.solvit.core.*
import dev.solvit.provider.OpenAiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Tests the service->resolver->real Provider HTTP seam; Android PDF parsing has separate gates. */
class DocumentContentResolverTest {
    private val attachment = ChatAttachment(UUID.randomUUID().toString(), "report.pdf", "application/pdf", 100, "a".repeat(64))
    private val store = object : ChatAttachmentStore {
        override suspend fun import(sessionId: String, name: String, mimeType: String, input: InputStream,
            authorize: suspend () -> Unit): ChatAttachment = error("unused")
        override suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit): ByteArray = error("PDF bytes belong to document host")
        override suspend fun remove(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit) = error("unused")
    }
    private fun document(id: String = attachment.id, hash: String = attachment.sha256) = DocumentText(id, hash, 2,
        listOf(DocumentTextPage(1, "中文 report first page", DocumentPageMethod.TEXT), DocumentTextPage(2, "scanned second page", DocumentPageMethod.OCR)))
    private fun service(block: suspend (String, ChatAttachment, suspend () -> Unit) -> DocumentText) = object : DocumentTextService {
        override suspend fun readPdf(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit) = block(sessionId, attachment, authorize)
    }
    @Test fun documentPagesReachActualOpenAiHttpBodyWithSourceAndReadingMode() = runTest {
        val reader = service { session, ref, authorize ->
            assertEquals("chat", session); assertSame(attachment, ref); authorize(); document()
        }
        val resolved = AttachmentContentResolver(store, reader).resolve("chat", ModelMessage(Role.USER, "解释", attachments = listOf(attachment)))
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            server.start()
            val client = OkHttpClient.Builder().build()
            try {
                OpenAiProvider(client).stream(ModelRequest(ModelConfig(baseUrl=server.url("/v1").toString().trimEnd('/'), model="fixture", apiKey="fixture"), listOf(resolved))).toList()
                val request = server.takeRequest(5, TimeUnit.SECONDS) ?: error("missing document request")
                val content = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
                assertTrue(content.contains("中文 report first page")); assertTrue(content.contains("scanned second page"))
                assertTrue(content.contains("第1/2页")); assertTrue(content.contains("第2/2页")); assertTrue(content.contains("扫描识别，请核对原页"))
                assertTrue(content.contains(attachment.sha256)); assertTrue(content.contains(attachment.id))
                assertEquals(listOf(attachment), resolved.attachments)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun mismatchedIdentityAndVersionNeverReturnModelInput() = runTest {
        val message = ModelMessage(Role.USER, "", attachments = listOf(attachment))
        assertFailsWith<IllegalArgumentException> { AttachmentContentResolver(store, service { _, _, _ -> document(hash="b".repeat(64)) }).resolve("chat", message) }
        assertFailsWith<IllegalArgumentException> { AttachmentContentResolver(store, service { _, _, _ -> document(id=UUID.randomUUID().toString()) }).resolve("chat", message) }
        assertFailsWith<ModelException> { AttachmentContentResolver(store).resolve("chat", message) }
    }
    @Test fun authorizationRevokedDuringExtractionDoesNotReturnPartialInput() = runTest {
        var allowed = true
        val reader = service { _, _, authorize -> authorize(); allowed=false; document() }
        assertFailsWith<IllegalStateException> {
            AttachmentContentResolver(store, reader).resolve("chat", ModelMessage(Role.USER, "", attachments=listOf(attachment))) { check(allowed) { "revoked" } }
        }
    }
    @Test fun selectedPageCoverageIsExplicitAndWrongRangeOrScriptIsRejected() = runTest {
        val selected = attachment.copy(documentSelection=DocumentSelection(100, 101, DocumentOcrScript.JAPANESE))
        val document = DocumentText(selected.id, selected.sha256, 200,
            listOf(DocumentTextPage(100,"included A",DocumentPageMethod.TEXT),DocumentTextPage(101,"included B",DocumentPageMethod.OCR)),
            100,101,DocumentOcrScript.JAPANESE)
        val message = ModelMessage(Role.USER,"read",attachments=listOf(selected))
        val resolved = AttachmentContentResolver(store,service { _,_,_->document }).resolve("chat",message)
        assertTrue(resolved.content.contains("本次读取第100至101页"))
        assertTrue(resolved.content.contains("范围外页面未读取"))
        assertTrue(resolved.content.contains("第100/200页"))
        assertFailsWith<IllegalArgumentException> {
            AttachmentContentResolver(store,service { _,_,_->document }).resolve("chat",message.copy(
                attachments=listOf(selected.copy(documentSelection=DocumentSelection(99,101,DocumentOcrScript.JAPANESE)))))
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentContentResolver(store,service { _,_,_->document }).resolve("chat",message.copy(
                attachments=listOf(selected.copy(documentSelection=DocumentSelection(100,101,DocumentOcrScript.KOREAN)))))
        }
    }
}
