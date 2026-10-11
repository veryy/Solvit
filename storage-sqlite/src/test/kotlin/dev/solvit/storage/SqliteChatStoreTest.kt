package dev.solvit.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
        val assistant = ChatMessage("a", "s", Role.ASSISTANT, "", toolCalls = listOf(call), reasoning = "Check the live clock")
        val result = ChatMessage("t", "s", Role.TOOL, "UTC", toolCallId = call.id, toolStage = ToolStage.SUCCEEDED)
        SqliteChatStore(context).use { store ->
            assertEquals("hello", store.history("s").single().content)
            assertTrue(store.history("s").single().toolCalls.isEmpty())
            assertEquals("", store.history("s").single().reasoning)
            store.appendBatch(listOf(assistant, result))
        }
        SqliteChatStore(context).use { store ->
            assertEquals(listOf(assistant, result), store.history("s").drop(1))
            store.deleteSession("s")
            assertTrue(store.history("s").isEmpty())
        }
    }

    @Test fun branchCopiesWholeToolTurnAndSurvivesDeletingOriginalAndReopening() = runBlocking<Unit> {
        lateinit var original: ChatSession
        lateinit var branch: ChatSession
        lateinit var source: List<ChatMessage>
        SqliteChatStore(context).use { store ->
            original = store.createSession()
            source = listOf(ChatMessage("u", original.id, Role.USER, "time"),
                ChatMessage("a", original.id, Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "clock", "{}")), reasoning = "thought"),
                ChatMessage("t", original.id, Role.TOOL, "actual-time", toolCallId = "call", toolStage = ToolStage.SUCCEEDED),
                ChatMessage("final", original.id, Role.ASSISTANT, "answer"),
                ChatMessage("later", original.id, Role.USER, "later question"))
            store.appendBatch(source)
            branch = store.forkSession(original.id, BranchPoint("a", BranchMode.AFTER_REPLY))
            assertEquals(original.id, branch.originSessionId)
            assertEquals("a", branch.originMessageId)
            assertEquals(source, store.history(original.id))
            val copy = store.history(branch.id)
            assertEquals(4, copy.size)
            assertTrue(copy.map { it.id }.toSet().intersect(source.map { it.id }.toSet()).isEmpty())
            assertEquals(source.take(4).map { it.copy(id = "", sessionId = "") }, copy.map { it.copy(id = "", sessionId = "") })
            store.deleteSession(original.id)
        }
        SqliteChatStore(context).use { store ->
            assertEquals(branch, store.sessions().first().single())
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), store.history(branch.id).map { it.role })
        }
    }
    @Test fun invalidAnchorDoesNotLeaveEmptyBranchAndEditingFirstQuestionStartsEmpty() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val original = store.createSession()
            store.append(ChatMessage("u", original.id, Role.USER, "question"))
            assertFailsWith<IllegalArgumentException> { store.forkSession(original.id, BranchPoint("missing", BranchMode.AFTER_REPLY)) }
            assertEquals(1, store.sessions().first().size)
            val branch = store.forkSession(original.id, BranchPoint("u", BranchMode.BEFORE_USER))
            assertTrue(store.history(branch.id).isEmpty())
            assertEquals("question", store.history(original.id).single().content)
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
