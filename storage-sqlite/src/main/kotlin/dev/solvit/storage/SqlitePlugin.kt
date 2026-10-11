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
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "solvit.db", null, 5) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL, origin_session_id TEXT, origin_message_id TEXT)")
            db.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, tool_calls TEXT NOT NULL DEFAULT '[]', tool_call_id TEXT, reasoning TEXT NOT NULL DEFAULT '', tool_stage TEXT, provider_transcript TEXT)")
            db.execSQL("CREATE INDEX messages_session ON messages(session_id, sequence)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 5) db.execSQL("ALTER TABLE messages ADD COLUMN provider_transcript TEXT")
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tool_calls TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE messages ADD COLUMN tool_call_id TEXT")
            }
            if (oldVersion < 4) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN origin_session_id TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN origin_message_id TEXT")
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
        read { db -> db.rawQuery("SELECT id,title,updated_at,origin_session_id,origin_message_id FROM sessions ORDER BY updated_at DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(ChatSession(c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getString(4))) }
        } }
    }.distinctUntilChanged()
    override fun messages(sessionId: String): Flow<List<ChatMessage>> = revision.map { history(sessionId) }.distinctUntilChanged()
    override suspend fun history(sessionId: String): List<ChatMessage> = read { history(it, sessionId) }
    private fun history(db: SQLiteDatabase, sessionId: String): List<ChatMessage> =
        db.rawQuery("SELECT id,role,content,status,tool_calls,tool_call_id,reasoning,tool_stage,provider_transcript FROM messages WHERE session_id=? ORDER BY sequence", arrayOf(sessionId)).use { c ->
            buildList { while (c.moveToNext()) add(ChatMessage(c.getString(0), sessionId, Role.valueOf(c.getString(1)), c.getString(2), MessageStatus.valueOf(c.getString(3)), decodeCalls(c.getString(4)), if (c.isNull(5)) null else c.getString(5), c.getString(6), if (c.isNull(7)) null else ToolStage.valueOf(c.getString(7)), if(c.isNull(8)) null else decodeTranscript(c.getString(8)))) }
        }
    override suspend fun createSession(): ChatSession {
        val session = ChatSession(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis())
        write { db -> db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", session.id); put("title", session.title); put("updated_at", session.updatedAt)
        }) }
        return session
    }
    override suspend fun forkSession(sessionId: String, point: BranchPoint): ChatSession = write { db ->
        val title = db.rawQuery("SELECT title FROM sessions WHERE id=?", arrayOf(sessionId)).use {
            require(it.moveToFirst()) { "原对话已不存在" }; it.getString(0)
        }
        val prefix = branchPrefix(history(db, sessionId), point)
        val session = ChatSession(UUID.randomUUID().toString(), (title.take(32) + " · 分支"),
            System.currentTimeMillis(), sessionId, point.messageId)
        db.insertOrThrow("sessions", null, ContentValues().apply {
            put("id", session.id); put("title", session.title); put("updated_at", session.updatedAt)
            put("origin_session_id", sessionId); put("origin_message_id", point.messageId)
        })
        prefix.forEach { insertMessage(db, it.copy(id = UUID.randomUUID().toString(), sessionId = session.id)) }
        session
    }
    override suspend fun append(message: ChatMessage) { appendBatch(listOf(message)) }
    override suspend fun appendBatch(messages: List<ChatMessage>) {
        write { db -> messages.forEach { message ->
            val first = db.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=?", arrayOf(message.sessionId)).use { it.moveToFirst(); it.getLong(0) == 0L }
            insertMessage(db, message)
            check(db.update("sessions", ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (first && message.role == Role.USER) put("title", message.content.replace('\n', ' ').take(40))
            }, "id=?", arrayOf(message.sessionId)) == 1) { "Conversation no longer exists" }
        } }
    }
    private fun insertMessage(db: SQLiteDatabase, message: ChatMessage) {
        db.insertOrThrow("messages", null, ContentValues().apply {
            put("id", message.id); put("session_id", message.sessionId)
            put("role", message.role.name); put("content", message.content); put("status", message.status.name)
            put("tool_calls", JSONArray(message.toolCalls.map { call -> JSONObject().apply {
                put("id", call.id); put("name", call.name); put("arguments", call.argumentsJson)
            } }).toString())
            put("tool_call_id", message.toolCallId)
            put("reasoning", message.reasoning); put("tool_stage", message.toolStage?.name)
            put("provider_transcript", message.providerTranscript?.let { encodeTranscript(it) })
        })
    }
    override suspend fun deleteSession(sessionId: String) { write { it.delete("sessions", "id=?", arrayOf(sessionId)) } }
    private fun decodeCalls(value: String): List<ToolCall> {
        val array = JSONArray(value)
        return (0 until array.length()).map { index ->
            val call = array.getJSONObject(index)
            ToolCall(call.getString("id"), call.getString("name"), call.getString("arguments"))
        }
    }
    private fun encodeTranscript(value: ProviderTranscript): String = JSONObject().put("provider",value.providerId).put("model",value.model)
        .put("blocks",value.blocksJson).put("options",JSONObject(value.options)).put("prefix",value.prefixHash).toString()
    private fun decodeTranscript(value: String): ProviderTranscript {
        val root=JSONObject(value);val options=root.optJSONObject("options") ?: JSONObject()
        return ProviderTranscript(root.getString("provider"),root.getString("model"),root.getString("blocks"),
            options.keys().asSequence().associateWith { options.getString(it) },root.optString("prefix",""))
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
