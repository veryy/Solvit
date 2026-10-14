package dev.solvit.storage

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChatAttachmentHistoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.deleteDatabase("solvit.db") }
    @Test fun attachmentReferencesReopenAndRejectDuplicateIds() = runBlocking<Unit> {
        val attachment = ChatAttachment(UUID.randomUUID().toString(), "图片.png", "image/png", 8, "a".repeat(64))
        val session = SqliteChatStore(context).use { store ->
            val session = store.createSession()
            store.append(ChatMessage("image-message", session.id, Role.USER, "", attachments = listOf(attachment)))
            session
        }
        SqliteChatStore(context).use { reopened ->
            val message = reopened.history(session.id).single()
            assertEquals(listOf(attachment), message.attachments)
            assertEquals("", message.content)
            assertFailsWith<IllegalArgumentException> {
                reopened.append(message.copy(id = "duplicate", attachments = listOf(attachment, attachment)))
            }
        }
    }
    @Test fun branchRetainsItsOwnVerifiedBytesAfterOriginalConversationIsDeleted() = runBlocking<Unit> {
        val bytes = "中文 attachment".toByteArray()
        var original = ""
        var branch = ""
        var reference: ChatAttachment? = null
        SqliteChatStore(context).use { store ->
            val session = store.createSession(); original = session.id
            val attachment = store.attachmentFiles.import(session.id, "note.txt", "text/plain", java.io.ByteArrayInputStream(bytes))
            reference = attachment
            store.append(ChatMessage("u", session.id, Role.USER, "read", attachments = listOf(attachment)))
            store.append(ChatMessage("a", session.id, Role.ASSISTANT, "reply"))
            branch = store.forkSession(session.id, BranchPoint("a", BranchMode.AFTER_REPLY)).id
            assertContentEquals(bytes, store.attachmentFiles.read(branch, attachment))
            store.deleteSession(original)
            assertFailsWith<IllegalStateException> { store.attachmentFiles.read(original, attachment) }
            assertContentEquals(bytes, store.attachmentFiles.read(branch, attachment))
        }
        SqliteChatStore(context).use { reopened ->
            val attachment = reopened.history(branch).first().attachments.single()
            assertEquals(reference, attachment)
            assertContentEquals(bytes, reopened.attachmentFiles.read(branch, attachment))
            reopened.deleteSession(branch)
            assertFailsWith<IllegalStateException> { reopened.attachmentFiles.read(branch, attachment) }
        }
    }
    @Test fun versionNineUpgradeRetainsTextAndDefaultsAttachmentsToEmpty() = runBlocking<Unit> {
        val session = SqliteChatStore(context).use { store ->
            val session = store.createSession()
            store.append(ChatMessage("old", session.id, Role.USER, "existing history"))
            session
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            // Recreate the actual v9 message schema, retaining every old column and row.
            db.execSQL("ALTER TABLE messages RENAME TO messages_v10")
            db.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, tool_calls TEXT NOT NULL DEFAULT '[]', tool_call_id TEXT, reasoning TEXT NOT NULL DEFAULT '', tool_stage TEXT, provider_transcript TEXT)")
            db.execSQL("INSERT INTO messages SELECT sequence,id,session_id,role,content,status,tool_calls,tool_call_id,reasoning,tool_stage,provider_transcript FROM messages_v10")
            db.execSQL("DROP TABLE messages_v10")
            db.execSQL("CREATE INDEX messages_session ON messages(session_id, sequence)")
            db.version = 9
        }
        SqliteChatStore(context).use { reopened ->
            val message = reopened.history(session.id).single()
            assertEquals("existing history", message.content)
            assertTrue(message.attachments.isEmpty())
        }
    }
}
