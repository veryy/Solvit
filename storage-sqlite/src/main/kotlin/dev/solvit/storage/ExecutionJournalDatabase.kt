package dev.solvit.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Called under SqliteChatStore's transaction and mutex; no callbacks or suspensions here. */
internal object ExecutionJournalDatabase {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE execution_runs (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, user_message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE, role_id TEXT, workspace_id TEXT, started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX execution_runs_session ON execution_runs(session_id, started_at)")
        db.execSQL("CREATE UNIQUE INDEX execution_runs_active ON execution_runs(session_id) WHERE status='RUNNING'")
        db.execSQL("CREATE TABLE execution_events (run_id TEXT NOT NULL REFERENCES execution_runs(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, recorded_at INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id, sequence))")
        ExecutionFileCaptureDatabase.create(db)
    }
    /** Rebuild the owning tables transactionally; preserve events and immutable file references. */
    fun migrateReplyAttempts(db: SQLiteDatabase) {
        val tables = listOf("execution_runs", "execution_events", "execution_file_refs")
        tables.forEach { db.execSQL("CREATE TEMP TABLE retry_backup_$it AS SELECT * FROM $it") }
        tables.asReversed().forEach { db.execSQL("DROP TABLE $it") }
        create(db)
        tables.forEach {
            db.execSQL("INSERT INTO $it SELECT * FROM retry_backup_$it")
            db.execSQL("DROP TABLE retry_backup_$it")
        }
    }
    fun begin(db: SQLiteDatabase, run: ExecutionRun): Boolean {
        run.validate(); require(run.status == ExecutionStatus.RUNNING && run.eventCount == 0)
        val source = db.rawQuery("SELECT id FROM messages WHERE session_id=? AND role='USER' AND status='COMPLETE' ORDER BY sequence DESC LIMIT 1", arrayOf(run.sessionId)).use {
            it.moveToFirst() && it.getString(0) == run.userMessageId
        }
        if (!source || runs(db, run.sessionId).any { it.id == run.id || it.status == ExecutionStatus.RUNNING || (it.userMessageId == run.userMessageId && it.status == ExecutionStatus.COMPLETE) }) return false
        db.insertOrThrow("execution_runs", null, ContentValues().apply {
            put("id",run.id);put("session_id",run.sessionId);put("user_message_id",run.userMessageId)
            put("role_id",run.roleId);put("workspace_id",run.workspaceId);put("started_at",run.startedAt)
            put("updated_at",run.updatedAt);put("status",run.status.name)
        });return true
    }
    fun runs(db: SQLiteDatabase, sessionId: String): List<ExecutionRun> =
        db.rawQuery("SELECT id,user_message_id,role_id,workspace_id,started_at,updated_at,status,(SELECT COUNT(*) FROM execution_events WHERE run_id=execution_runs.id) FROM execution_runs WHERE session_id=? ORDER BY started_at,rowid", arrayOf(sessionId)).use { c ->
            buildList { while(c.moveToNext()) add(ExecutionRun(c.getString(0),sessionId,c.getString(1),
                if(c.isNull(2)) null else c.getString(2),if(c.isNull(3)) null else c.getString(3),c.getLong(4),c.getLong(5),ExecutionStatus.valueOf(c.getString(6)),c.getInt(7))) }
        }
    private fun run(db: SQLiteDatabase, id: String): ExecutionRun? =
        db.rawQuery("SELECT session_id FROM execution_runs WHERE id=?",arrayOf(id)).use { c ->
            if(!c.moveToFirst()) null else runs(db,c.getString(0)).firstOrNull { it.id==id }
        }
    fun events(db: SQLiteDatabase, sessionId: String, runId: String): List<RecordedExecutionEvent> {
        if(run(db,runId)?.sessionId != sessionId) return emptyList()
        return db.rawQuery("SELECT sequence,recorded_at,substr(CAST(payload AS BLOB),1,16384),length(CAST(payload AS BLOB)) FROM execution_events WHERE run_id=? ORDER BY sequence",arrayOf(runId)).use { c ->
            buildList { while(c.moveToNext()) {
                val sequence=c.getInt(0);val length=c.getInt(3);require(length in 1..MAX_PAYLOAD)
                val output=ByteArrayOutputStream(length);output.write(c.getBlob(2))
                while(output.size()<length) {
                    val part=db.rawQuery("SELECT substr(CAST(payload AS BLOB),?,16384) FROM execution_events WHERE run_id=? AND sequence=?",arrayOf((output.size()+1).toString(),runId,sequence.toString())).use { chunk ->
                        check(chunk.moveToFirst());chunk.getBlob(0)
                    };check(part.isNotEmpty());output.write(part)
                }
                add(RecordedExecutionEvent(sequence,c.getLong(1),decode(JSONObject(output.toByteArray().toString(Charsets.UTF_8)))))
            } }
        }
    }
    fun append(db: SQLiteDatabase, runId: String, expectedSequence: Int, batch: List<ExecutionEvent>, now: Long, captureQuotaBytes: Long): Boolean {
        require(batch.size in 1..64 && expectedSequence in 0..512 && now>=0)
        val run=run(db,runId) ?: return false
        if(run.status != ExecutionStatus.RUNNING || now<run.updatedAt) return false
        require(batch.all { event -> event.workspace?.let { it.workspaceId==run.workspaceId } ?: true })
        val prior=events(db,run.sessionId,run.id)
        if(prior.size!=expectedSequence || prior.size+batch.size>512) return false
        val state=ExecutionJournalState();prior.forEach { state.accept(it.event) };batch.forEach(state::accept)
        val persisted=ExecutionFileCaptureDatabase.prepare(db,runId,batch,captureQuotaBytes)
        val payloads=persisted.map { encode(it).toString().also { text -> require(text.toByteArray(Charsets.UTF_8).size<=MAX_PAYLOAD) } }
        payloads.forEachIndexed { i,payload -> db.insertOrThrow("execution_events",null,ContentValues().apply {
            put("run_id",runId);put("sequence",expectedSequence+i);put("recorded_at",now);put("payload",payload)
        }).also { ExecutionFileCaptureDatabase.reference(db,runId,expectedSequence+i,persisted[i]) } }
        db.execSQL("UPDATE execution_runs SET updated_at=? WHERE id=?",arrayOf<Any>(now,runId));return true
    }
    fun finish(db: SQLiteDatabase, runId: String, status: ExecutionStatus, now: Long): Boolean {
        require(status in setOf(ExecutionStatus.COMPLETE,ExecutionStatus.CANCELLED,ExecutionStatus.FAILED) && now>=0)
        val run=run(db,runId) ?: return false
        if(run.status != ExecutionStatus.RUNNING || now<run.updatedAt) return false
        if(status==ExecutionStatus.COMPLETE) {
            val state=ExecutionJournalState();events(db,run.sessionId,runId).forEach { state.accept(it.event) }
            require(!state.stepOpen) { "Execution step has not settled" }
        }
        return db.update("execution_runs",ContentValues().apply { put("status",status.name);put("updated_at",now) },"id=? AND status='RUNNING'",arrayOf(runId))==1
    }
    fun recover(db: SQLiteDatabase, now: Long): Int {
        require(now>=0)
        db.execSQL("UPDATE execution_runs SET status='PROCESS_INTERRUPTED',updated_at=MAX(updated_at,?) WHERE status='RUNNING'",arrayOf(now))
        return db.rawQuery("SELECT changes()",null).use { it.moveToFirst();it.getInt(0) }
    }
    private fun encode(e: ExecutionEvent)=JSONObject().put("step",e.step).put("kind",e.kind.name).put("truncated",e.truncated).apply {
        e.call?.let { put("call",JSONObject().put("id",it.id).put("name",it.name).put("arguments",it.argumentsJson)) }
        e.pluginId?.let { put("plugin",it) };e.callId?.let { put("callId",it) };e.stage?.let { put("stage",it.name) };e.result?.let { put("result",it) }
        e.workspace?.let { snapshot -> put("workspace",JSONObject().put("id",snapshot.workspaceId)
            .put("complete",snapshot.complete).put("observedAt",snapshot.observedAt).put("files",JSONObject().apply {
                snapshot.files.forEach { (path,value) -> put(path,JSONObject().put("kind",value.kind.name)
                    .put("bytes",value.bytes ?: JSONObject.NULL).put("sha256",value.sha256 ?: JSONObject.NULL)
                    .put("excerpt",value.excerpt ?: JSONObject.NULL).put("truncated",value.excerptTruncated)
                    .put("captureSha256",value.captureSha256 ?: JSONObject.NULL)) }
            })) }
    }
    private fun decode(j: JSONObject)=ExecutionEvent(j.getInt("step"),ExecutionKind.valueOf(j.getString("kind")),
        j.optJSONObject("call")?.let { ToolCall(it.getString("id"),it.getString("name"),it.getString("arguments")) },
        if(j.has("plugin")) j.getString("plugin") else null,if(j.has("callId")) j.getString("callId") else null,
        if(j.has("stage")) ToolStage.valueOf(j.getString("stage")) else null,if(j.has("result")) j.getString("result") else null,
        j.getBoolean("truncated"),j.optJSONObject("workspace")?.let { s ->
            val files=s.getJSONObject("files")
            WorkspaceSnapshot(s.getString("id"),files.keys().asSequence().associateWith { path ->
                val v=files.getJSONObject(path)
                FileObservation(FileObservationKind.valueOf(v.getString("kind")),if(v.isNull("bytes")) null else v.getLong("bytes"),
                    if(v.isNull("sha256")) null else v.getString("sha256"),if(v.isNull("excerpt")) null else v.getString("excerpt"),v.getBoolean("truncated"),
                    captureSha256=if(!v.has("captureSha256") || v.isNull("captureSha256")) null else v.getString("captureSha256"))
            },s.getBoolean("complete"),s.getLong("observedAt"))
        }).also(ExecutionEvent::validate)
    private const val MAX_PAYLOAD=512*1024
}
