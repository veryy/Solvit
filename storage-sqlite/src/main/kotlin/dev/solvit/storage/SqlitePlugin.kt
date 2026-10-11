package dev.solvit.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.Cursor
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class SqliteChatStore(context: Context) : ChatStore, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "solvit.db", null, 7) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, updated_at INTEGER NOT NULL, origin_session_id TEXT, origin_message_id TEXT)")
            db.execSQL("CREATE TABLE messages (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, tool_calls TEXT NOT NULL DEFAULT '[]', tool_call_id TEXT, reasoning TEXT NOT NULL DEFAULT '', tool_stage TEXT, provider_transcript TEXT)")
            db.execSQL("CREATE INDEX messages_session ON messages(session_id, sequence)")
            createSummaryTable(db)
            createTurnTable(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 7) createTurnTable(db)
            if (oldVersion < 6) createSummaryTable(db)
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
        lock.withLock {
            val db=helper.readableDatabase
            // A stable snapshot also covers additional chunks of a large message.
            db.beginTransactionNonExclusive()
            try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
        }
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
    override suspend fun contextSummary(sessionId: String): ContextSummary? = read { loadSummary(it, sessionId) }
    override suspend fun saveContextSummary(sessionId: String, expected: ContextSummary?, replacement: ContextSummary): Boolean {
        replacement.validate()
        return write { db ->
            if (loadSummary(db, sessionId) != expected) return@write false
            val history = history(db, sessionId).filter { it.status == MessageStatus.COMPLETE }
            val boundary = history.indexOfFirst { it.id == replacement.throughMessageId }
            if (boundary < 0 || boundary + 1 != replacement.messageCount) return@write false
            if (contextPrefixDigest(history.take(boundary + 1)) != replacement.prefixDigest) return@write false
            if (expected != null && replacement.messageCount <= expected.messageCount) return@write false
            db.insertWithOnConflict("context_summaries", null, ContentValues().apply {
                put("session_id", sessionId); put("payload", encodeSummary(replacement).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        }
    }
    private fun loadSummary(db: SQLiteDatabase, sessionId: String): ContextSummary? =
        db.rawQuery("SELECT payload FROM context_summaries WHERE session_id=?", arrayOf(sessionId)).use { c ->
            if (!c.moveToFirst()) null else decodeSummary(JSONObject(c.getString(0)))
        }
    private fun encodeSummary(s: ContextSummary) = JSONObject().put("id", s.id).put("through", s.throughMessageId)
        .put("digest", s.prefixDigest).put("count", s.messageCount).put("text", s.text).put("provider", s.providerId)
        .put("model", s.model).put("before", s.estimatedBefore).put("after", s.estimatedAfter).put("created", s.createdAt)
    private fun decodeSummary(j: JSONObject) = ContextSummary(j.getString("id"), j.getString("through"), j.getString("digest"),
        j.getInt("count"), j.getString("text"), j.getString("provider"), j.getString("model"), j.getLong("before"),
        j.getLong("after"), j.getLong("created")).also { it.validate() }
    private fun createSummaryTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE context_summaries (session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
    }
    override suspend fun postTurnMessages(turn: CompletedChatTurn): List<ChatMessage> = read {
        history(it, turn.sessionId, listOf(turn.userMessageId, turn.assistantMessageId))
    }
    private fun history(db: SQLiteDatabase, sessionId: String, ids: List<String>? = null): List<ChatMessage> =
        db.rawQuery("""SELECT id,role,substr(CAST(content AS BLOB),1,$READ_CHUNK),status,
            substr(CAST(tool_calls AS BLOB),1,$READ_CHUNK),tool_call_id,
            substr(CAST(reasoning AS BLOB),1,$READ_CHUNK),tool_stage,
            substr(CAST(provider_transcript AS BLOB),1,$READ_CHUNK),
            length(CAST(content AS BLOB)),length(CAST(tool_calls AS BLOB)),
            length(CAST(reasoning AS BLOB)),length(CAST(provider_transcript AS BLOB))
            FROM messages WHERE session_id=? ${if (ids == null) "" else "AND id IN (${ids.joinToString(",") { "?" }})"} ORDER BY sequence""", (listOf(sessionId) + ids.orEmpty()).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) {
                val id=c.getString(0)
                add(ChatMessage(id,sessionId,Role.valueOf(c.getString(1)),readText(db,c,id,"content",2,9),
                    MessageStatus.valueOf(c.getString(3)),decodeCalls(readText(db,c,id,"tool_calls",4,10)),
                    if(c.isNull(5)) null else c.getString(5),readText(db,c,id,"reasoning",6,11),
                    if(c.isNull(7)) null else ToolStage.valueOf(c.getString(7)),
                    if(c.isNull(8)) null else decodeTranscript(readText(db,c,id,"provider_transcript",8,12))))
            } }
        }
    /** Read UTF-8 as bytes, so chunk boundaries and embedded NULs do not alter text. */
    private fun readText(db:SQLiteDatabase,cursor:Cursor,id:String,column:String,prefixIndex:Int,lengthIndex:Int):String {
        val length=cursor.getInt(lengthIndex)
        // Android may represent a zero-length blob as null in CursorWindow.
        if(length==0) return ""
        val prefix=checkNotNull(cursor.getBlob(prefixIndex)) { "Missing message data" }
        if(prefix.size==length) return prefix.toString(Charsets.UTF_8)
        val data=ByteArrayOutputStream(length);data.write(prefix)
        var offset=prefix.size+1
        while(offset<=length) {
            db.rawQuery("SELECT substr(CAST($column AS BLOB),?,$READ_CHUNK) FROM messages WHERE id=?",arrayOf(offset.toString(),id)).use {
                check(it.moveToFirst()) { "Conversation changed while reading" }
                val chunk=it.getBlob(0);check(chunk.isNotEmpty()) { "Incomplete message data" }
                data.write(chunk);offset+=chunk.size
            }
        }
        return data.toByteArray().toString(Charsets.UTF_8)
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
        write { appendMessages(it, messages) }
    }
    override val supportsCompletedTurns = true
    override suspend fun appendCompletedTurn(messages: List<ChatMessage>, turn: CompletedChatTurn) {
        turn.validate()
        require(messages.isNotEmpty() && messages.all { it.sessionId == turn.sessionId && it.status == MessageStatus.COMPLETE && it.role != Role.USER })
        val final = messages.last()
        require(final.id == turn.assistantMessageId && final.role == Role.ASSISTANT && final.toolCalls.isEmpty() && final.content.isNotBlank())
        write { db ->
            val source = db.rawQuery("SELECT id,status FROM messages WHERE session_id=? AND role='USER' ORDER BY sequence DESC LIMIT 1", arrayOf(turn.sessionId)).use { c ->
                c.moveToFirst() && c.getString(0) == turn.userMessageId && c.getString(1) == MessageStatus.COMPLETE.name
            }
            require(source) { "Completed turn has no matching current user message" }
            appendMessages(db, messages)
            db.insertOrThrow("completed_turns", null, ContentValues().apply {
                put("id", turn.id); put("session_id", turn.sessionId)
                put("user_id", turn.userMessageId); put("assistant_id", turn.assistantMessageId)
                put("payload", encodeTurn(turn).toString())
            })
            turn.targets.forEach { target ->
                db.insertOrThrow("post_turn_jobs", null, ContentValues().apply {
                    put("id", turn.id + ":" + target.pluginId); put("turn_id", turn.id)
                    put("plugin_id", target.pluginId); put("version", target.version); put("digest", target.digest)
                    put("status", PostTurnJobStatus.WAITING.name); put("attempt", 0); put("updated_at", turn.createdAt)
                })
            }
        }
    }
    override suspend fun recordedTurns(afterSequence: Long, limit: Int): List<RecordedChatTurn> {
        require(afterSequence >= 0 && limit in 1..100)
        return read { db ->
            db.rawQuery("SELECT sequence,payload FROM completed_turns WHERE sequence>? ORDER BY sequence LIMIT ?", arrayOf(afterSequence.toString(), limit.toString())).use { c ->
                buildList { while (c.moveToNext()) add(RecordedChatTurn(c.getLong(0), decodeTurn(JSONObject(c.getString(1))))) }
            }
        }
    }
    private fun createTurnTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE completed_turns (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, user_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE, assistant_id TEXT UNIQUE NOT NULL REFERENCES messages(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE post_turn_jobs (id TEXT PRIMARY KEY, turn_id TEXT NOT NULL REFERENCES completed_turns(id) ON DELETE CASCADE, plugin_id TEXT NOT NULL, version TEXT NOT NULL, digest TEXT NOT NULL, status TEXT NOT NULL, attempt INTEGER NOT NULL, updated_at INTEGER NOT NULL, failure TEXT, UNIQUE(turn_id,plugin_id))")
        db.execSQL("CREATE INDEX post_turn_jobs_status ON post_turn_jobs(status,updated_at)")
    }
    private fun encodeTurn(t: CompletedChatTurn) = JSONObject().put("id", t.id).put("session", t.sessionId)
        .put("user", t.userMessageId).put("assistant", t.assistantMessageId)
        .put("roleId", t.roleId ?: JSONObject.NULL).put("roleName", t.roleName ?: JSONObject.NULL)
        .put("plugins", t.allowedPluginIds?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
        .put("profile", t.requestedProfileId ?: JSONObject.NULL).put("provider", t.requestedProviderId)
        .put("model", t.requestedModel).put("created", t.createdAt)
        .put("targets", JSONArray(t.targets.map { target -> JSONObject().put("id", target.pluginId).put("version", target.version).put("digest", target.digest) }))
    private fun decodeTurn(j: JSONObject) = CompletedChatTurn(j.getString("id"), j.getString("session"),
        j.getString("user"), j.getString("assistant"), if (j.isNull("roleId")) null else j.getString("roleId"),
        if (j.isNull("roleName")) null else j.getString("roleName"),
        if (j.isNull("plugins")) null else j.getJSONArray("plugins").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
        if (j.isNull("profile")) null else j.getString("profile"), j.getString("provider"), j.getString("model"), j.getLong("created"),
        j.getJSONArray("targets").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { t -> PostTurnTarget(t.getString("id"), t.getString("version"), t.getString("digest")) } } }).also { it.validate() }
    override suspend fun postTurnJobs(statuses: Set<PostTurnJobStatus>, limit: Int): List<PostTurnJob> {
        require(limit in 1..100)
        if (statuses.isEmpty()) return emptyList()
        val ordered = statuses.sortedBy { it.name }
        return read { db -> loadJobs(db, "j.status IN (${ordered.joinToString(",") { "?" }})", ordered.map { it.name }, limit) }
    }
    override suspend fun recentPostTurnJobs(before: PostTurnJobCursor?, limit: Int): List<PostTurnJob> {
        require(limit in 1..100)
        val condition = if (before == null) "1=1" else "(j.updated_at<? OR (j.updated_at=? AND j.id<?))"
        val arguments = before?.let { listOf(it.updatedAt.toString(), it.updatedAt.toString(), it.id) }.orEmpty()
        return read { db -> loadJobs(db, condition, arguments, limit, newestFirst = true) }
    }
    override suspend fun postTurnJob(id: String): PostTurnJob? = read { loadJobs(it, "j.id=?", listOf(id), 1).singleOrNull() }
    private fun loadJobs(db: SQLiteDatabase, condition: String, arguments: List<String>, limit: Int, newestFirst: Boolean = false): List<PostTurnJob> =
            db.rawQuery("SELECT j.id,t.payload,j.plugin_id,j.version,j.digest,j.status,j.attempt,j.updated_at,j.failure FROM post_turn_jobs j JOIN completed_turns t ON t.id=j.turn_id WHERE $condition ORDER BY j.updated_at ${if (newestFirst) "DESC" else "ASC"},j.id ${if (newestFirst) "DESC" else "ASC"} LIMIT ?",
                (arguments + limit.toString()).toTypedArray()).use { c ->
                buildList { while (c.moveToNext()) add(PostTurnJob(c.getString(0), decodeTurn(JSONObject(c.getString(1))),
                    PostTurnTarget(c.getString(2), c.getString(3), c.getString(4)), PostTurnJobStatus.valueOf(c.getString(5)),
                    c.getInt(6), c.getLong(7), if (c.isNull(8)) null else PostTurnFailure.valueOf(c.getString(8)))) }
            }
    override suspend fun claimPostTurnJob(id: String, expectedAttempt: Int, now: Long): Boolean {
        require(expectedAttempt in 0..2 && now >= 0)
        return write { db -> db.update("post_turn_jobs", ContentValues().apply {
            put("status", PostTurnJobStatus.RUNNING.name); put("attempt", expectedAttempt + 1); put("updated_at", now); putNull("failure")
        }, "id=? AND status=? AND attempt=?", arrayOf(id, PostTurnJobStatus.WAITING.name, expectedAttempt.toString())) == 1 }
    }
    override suspend fun finishPostTurnJob(id: String, attempt: Int, status: PostTurnJobStatus, failure: PostTurnFailure?, now: Long): Boolean {
        require(attempt in 1..3 && now >= 0 && status in setOf(PostTurnJobStatus.SUCCEEDED, PostTurnJobStatus.FAILED, PostTurnJobStatus.SKIPPED))
        require((status == PostTurnJobStatus.FAILED) == (failure != null))
        return write { db -> db.update("post_turn_jobs", ContentValues().apply {
            put("status", status.name); put("updated_at", now); put("failure", failure?.name)
        }, "id=? AND status=? AND attempt=?", arrayOf(id, PostTurnJobStatus.RUNNING.name, attempt.toString())) == 1 }
    }
    override suspend fun retryPostTurnJob(id: String, expectedAttempt: Int, now: Long): Boolean {
        require(expectedAttempt in 1..3 && now >= 0)
        if (expectedAttempt == 3) return false
        return write { db -> db.update("post_turn_jobs", ContentValues().apply {
            put("status", PostTurnJobStatus.WAITING.name); put("updated_at", now); putNull("failure")
        }, "id=? AND status=? AND attempt=?", arrayOf(id, PostTurnJobStatus.FAILED.name, expectedAttempt.toString())) == 1 }
    }
    override suspend fun recoverInterruptedPostTurnJobs(now: Long): Int {
        require(now >= 0)
        return write { db -> db.update("post_turn_jobs", ContentValues().apply {
            put("status", PostTurnJobStatus.FAILED.name); put("failure", PostTurnFailure.INTERRUPTED.name); put("updated_at", now)
        }, "status=?", arrayOf(PostTurnJobStatus.RUNNING.name)) }
    }
    private fun appendMessages(db: SQLiteDatabase, messages: List<ChatMessage>) {
        messages.forEach { message ->
            val first = db.rawQuery("SELECT COUNT(*) FROM messages WHERE session_id=?", arrayOf(message.sessionId)).use { it.moveToFirst(); it.getLong(0) == 0L }
            insertMessage(db, message)
            check(db.update("sessions", ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (first && message.role == Role.USER) put("title", message.content.replace('\n', ' ').take(40))
            }, "id=?", arrayOf(message.sessionId)) == 1) { "Conversation no longer exists" }
        }
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
    private companion object { const val READ_CHUNK=16*1024 }
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
