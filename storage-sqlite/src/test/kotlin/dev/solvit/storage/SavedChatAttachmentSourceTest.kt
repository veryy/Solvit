package dev.solvit.storage

import android.app.Application
import dev.solvit.core.*
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SavedChatAttachmentSourceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { app.deleteDatabase("solvit.db") }

    @Test fun metadataPagesAreBoundedAndOnlyListThisConversationsSavedUserAttachments() = runBlocking<Unit> {
        SqliteChatStore(app).use { store ->
            val a=store.createSession().id;val b=store.createSession().id
            val attachment=store.attachmentFiles.import(a,"note.txt","text/plain",ByteArrayInputStream("original".toByteArray()))
            for(i in 0 until 25)store.append(ChatMessage("u-$i",a,Role.USER,"message body",attachments=listOf(attachment)))
            store.append(ChatMessage("other-chat",b,Role.USER,"private",attachments=listOf(attachment)))
            var reads=0
            val files=object:ChatAttachmentStore by store.attachmentFiles {
                override suspend fun read(sessionId:String,attachment:ChatAttachment,authorize:suspend()->Unit):ByteArray {
                    reads++;error("Metadata must not read blobs")
                }
            }
            val source=SavedChatAttachmentSource(store,files,a) { a }
            val first=source.list(0) {};assertEquals(25,first.total);assertEquals(20,first.entries.size)
            assertEquals("u-24",first.entries.first().messageId);assertEquals(25,first.entries.first().messageNumber)
            val second=source.list(20) {};assertEquals(5,second.entries.size)
            assertEquals("u-0",second.entries.last().messageId)
            assertTrue(source.list(25) {}.entries.isEmpty());assertFails { source.list(26) {} };assertFails { source.list(-1) {} }
            assertEquals(0,reads)
        }
    }

    @Test fun reopenedPdfUsesCompleteOriginalDespiteSelectedPagesAndKeepsSavedFile() = runBlocking<Unit> {
        val original = byteArrayOf(0, -1, -2, 65) + "%PDF fixture original".toByteArray()
        lateinit var session: String
        lateinit var attachment: ChatAttachment
        SqliteChatStore(app).use { store ->
            session = store.createSession().id
            attachment = store.attachmentFiles.import(session, "original.pdf", "application/pdf", ByteArrayInputStream(original))
                .copy(documentSelection = DocumentSelection(100, 120, DocumentOcrScript.KOREAN))
            store.append(ChatMessage("pdf-source", session, Role.USER, "", attachments = listOf(attachment)))
        }
        SqliteChatStore(app).use { store ->
            val source = SavedChatAttachmentSource(store, store.attachmentFiles, session) { session }
            source.open("pdf-source", attachment.id, attachment.sha256) {}.use { selected ->
                assertEquals("original.pdf", selected.name)
                assertEquals("application/pdf", selected.mimeType)
                assertContentEquals(original, selected.readBytes())
                assertEquals(attachment.sha256, selected.sha256)
            }
            assertContentEquals(original, store.attachmentFiles.read(session, attachment))
            assertEquals(attachment, store.history(session).single().attachments.single())
        }
    }

    @Test fun wrongChatMessageAndStaleVersionAreRejectedBeforeAnyBlobRead() = runBlocking<Unit> {
        SqliteChatStore(app).use { store ->
            val a = store.createSession().id; val b = store.createSession().id
            val file = store.attachmentFiles.import(a, "note.txt", "text/plain", ByteArrayInputStream("original".toByteArray()))
            store.append(ChatMessage("message-a", a, Role.USER, "", attachments = listOf(file)))
            var reads = 0
            val files = object : ChatAttachmentStore by store.attachmentFiles {
                override suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit): ByteArray {
                    reads++; return store.attachmentFiles.read(sessionId, attachment, authorize)
                }
            }
            val other = SavedChatAttachmentSource(store, files, b) { b }
            assertFails { other.open("message-a", file.id, file.sha256) {} }
            val source = SavedChatAttachmentSource(store, files, a) { a }
            assertFails { source.open("missing-message", file.id, file.sha256) {} }
            assertFails { source.open("message-a", file.id, "0".repeat(64)) {} }
            val switched = SavedChatAttachmentSource(store, files, a) { b }
            assertFails { switched.open("message-a", file.id, file.sha256) {} }
            assertEquals(0, reads)
        }
    }

    @Test fun deletedSourceAfterBlobReadNeverReturnsASelectionAndWipesTheTransientCopy() = runBlocking<Unit> {
        SqliteChatStore(app).use { store ->
            val session = store.createSession().id
            val file = store.attachmentFiles.import(session, "ramp.bin", "application/octet-stream", ByteArrayInputStream(ByteArray(300_000) { it.toByte() }))
            store.append(ChatMessage("binary-source", session, Role.USER, "", attachments = listOf(file)))
            var copy: ByteArray? = null
            val files = object : ChatAttachmentStore by store.attachmentFiles {
                override suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit): ByteArray {
                    val bytes = store.attachmentFiles.read(sessionId, attachment, authorize)
                    copy = bytes; store.deleteSession(session); return bytes
                }
            }
            assertFails { SavedChatAttachmentSource(store, files, session) { session }.open("binary-source", file.id, file.sha256) {} }
            assertNotNull(copy); assertTrue(copy!!.all { it == 0.toByte() })
        }
    }

    @Test fun cancelledOrRevokedAfterReadWipesTheCopyAndPreservesTheSavedOriginal() = runBlocking<Unit> {
        for (cancel in listOf(false, true)) SqliteChatStore(app).use { store ->
            val session = store.createSession().id
            val original = "keep saved file".toByteArray()
            val file = store.attachmentFiles.import(session, "note.txt", "text/plain", ByteArrayInputStream(original))
            store.append(ChatMessage("source-$cancel", session, Role.USER, "", attachments = listOf(file)))
            var allowed = true; var copy: ByteArray? = null
            val reached = CompletableDeferred<Unit>(); val job = Job()
            val files = object : ChatAttachmentStore by store.attachmentFiles {
                override suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit): ByteArray {
                    val bytes = store.attachmentFiles.read(sessionId, attachment, authorize)
                    copy = bytes
                    if (cancel) job.cancel() else allowed = false
                    reached.complete(Unit); return bytes
                }
            }
            val source = SavedChatAttachmentSource(store, files, session) { session }
            try {
                withContext(job) { source.open("source-$cancel", file.id, file.sha256) { check(allowed) } }
                fail("Returned a cancelled or revoked attachment")
            } catch (_: CancellationException) { assertTrue(cancel) }
            catch (_: IllegalStateException) { assertFalse(cancel) }
            assertTrue(reached.isCompleted); assertTrue(copy!!.all { it == 0.toByte() })
            assertContentEquals(original, store.attachmentFiles.read(session, file))
            job.cancel()
        }
    }
}
