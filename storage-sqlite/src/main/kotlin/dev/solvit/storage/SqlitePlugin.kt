package dev.solvit.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class SqliteChatStore(context: Context) : ChatStore, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "solvit.db", null, 3) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, tool_calls TEXT NOT NULL DEFAULT '[]', tool_call_id TEXT, reasoning TEXT NOT NULL DEFAULT '', tool_stage TEXT)")
            db.execSQL("CREATE INDEX messages_session ON messages(session_id, sequence)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tool_calls TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE messages ADD COLUMN tool_call_id TEXT")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN tool_stage TEXT")
            }
        }
    }
    private val lock = Mutex()
    private val revision = MutableStateFlow(0L)
    private suspend fun <T> read(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block(helper.readableDatabase) }
    }
    private suspend fun <T> write(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            val result = try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
            revision.value += 1
            result
        }
    }
    override fun sessions(): Flow<List<ChatSession>> = revision.map {
        read { db -> db.rawQuery("SELECT id,title,updated_at FROM sessions ORDER BY updated_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(ChatSession(c.getString(0), c.getString(1), c.getLong(2))) }
        } }
    }.distinctUntilChanged()
    override fun messages(sessionId: String): Flow<List<ChatMessage>> = revision.map { history(sessionId) }.distinctUntilChanged()
    override suspend fun history(sessionId: String): List<ChatMessage> = read { db ->
        db.rawQuery("SELECT id,role,content,status,tool_calls,tool_call_id,reasoning,tool_stage FROM messages WHERE session_id=? ORDER BY sequence", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(ChatMessage(c.getString(0), sessionId, Role.valueOf(c.getString(1)), c.getString(2), MessageStatus.valueOf(c.getString(3)), decodeCalls(c.getString(4)), if (c.isNull(5)) null else c.getString(5), c.getString(6), if (c.isNull(7)) null else ToolStage.valueOf(c.getString(7)))) }
        }
    }
    override suspend fun createSession(): ChatSession {
        val session = ChatSession(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis())
        write { db -> db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", session.id); put("title", session.title); put("updated_at", session.updatedAt)
        }) }
        return session
    }
    override suspend fun append(message: ChatMessage) { appendBatch(listOf(message)) }
    override suspend fun appendBatch(messages: List<ChatMessage>) {
        write { db -> messages.forEach { message ->
            val first = db.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=?", arrayOf(message.sessionId)).use { it.moveToFirst(); it.getLong(0) == 0L }
            db.insertOrThrow("messages", null, ContentValues().apply {
                put("id", message.id); put("session_id", message.sessionId)
                put("role", message.role.name); put("content", message.content); put("status", message.status.name)
                put("tool_calls", JSONArray(message.toolCalls.map { call -> JSONObject().apply {
                    put("id", call.id); put("name", call.name); put("arguments", call.argumentsJson)
                } }).toString())
                put("tool_call_id", message.toolCallId)
                put("reasoning", message.reasoning); put("tool_stage", message.toolStage?.name)
            })
            check(db.update("sessions", ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (first && message.role == Role.USER) put("title", message.content.replace('\n', ' ').take(40))
            }, "id=?", arrayOf(message.sessionId)) == 1) { "Conversation no longer exists" }
        } }
    }
    override suspend fun deleteSession(sessionId: String) { write { it.delete("sessions", "id=?", arrayOf(sessionId)) } }
    private fun decodeCalls(value: String): List<ToolCall> {
        val array = JSONArray(value)
        return (0 until array.length()).map { index ->
            val call = array.getJSONObject(index)
            ToolCall(call.getString("id"), call.getString("name"), call.getString("arguments"))
        }
    }
    override fun close() { helper.close() }
}

class SqliteStoragePlugin(private val androidContext: Context) : SolvitPlugin {
    override val id = "builtin.storage.sqlite"
    override val provides = setOf(Services.Storage)
    override suspend fun enable(context: PluginContext) {
        val store = SqliteChatStore(androidContext)
        context.own { store.close() }
        context.register(Services.Storage, store)
    }
}
