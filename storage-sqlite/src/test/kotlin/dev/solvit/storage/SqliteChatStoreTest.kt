package dev.solvit.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SqliteChatStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun before() { context.deleteDatabase("solvit.db") }
    @After fun after() { context.deleteDatabase("solvit.db") }

    @Test fun migratesOldMessagesAndPersistsToolCallsAcrossReopening() = runBlocking<Unit> {
        val file = context.getDatabasePath("solvit.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions VALUES ('s','old chat',1)")
            db.execSQL("INSERT INTO messages (id,session_id,role,content,status) VALUES ('u','s','USER','hello','COMPLETE')")
            db.version = 1
        }
        val call = ToolCall("call", "clock", "{\"zone\":\"UTC\"}")
        val assistant = ChatMessage("a", "s", Role.ASSISTANT, "", toolCalls = listOf(call))
        val result = ChatMessage("t", "s", Role.TOOL, "UTC", toolCallId = call.id)
        SqliteChatStore(context).use { store ->
            assertEquals("hello", store.history("s").single().content)
            assertTrue(store.history("s").single().toolCalls.isEmpty())
            store.appendBatch(listOf(assistant, result))
        }
        SqliteChatStore(context).use { store ->
            assertEquals(listOf(assistant, result), store.history("s").drop(1))
            store.deleteSession("s")
            assertTrue(store.history("s").isEmpty())
        }
    }

    @Test fun failedBatchRollsBackEarlierMessages() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val session = store.createSession()
            val message = ChatMessage("duplicate", session.id, Role.USER, "first")
            assertFailsWith<Exception> { store.appendBatch(listOf(message, message)) }
            assertTrue(store.history(session.id).isEmpty())
        }
    }
}
