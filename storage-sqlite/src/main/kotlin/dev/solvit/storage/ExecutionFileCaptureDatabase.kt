package dev.solvit.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Immutable copies and exact event ownership. All calls run inside the journal transaction. */
internal object ExecutionFileCaptureDatabase {
    const val MAX_QUOTA_BYTES = 128L * 1024 * 1024
    private const val MAX_RUN_BYTES = 32L * 1024 * 1024
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS execution_file_captures (sha256 TEXT PRIMARY KEY, content BLOB NOT NULL CHECK(length(content)<=262144))")
        db.execSQL("CREATE TABLE IF NOT EXISTS execution_file_refs (run_id TEXT NOT NULL, sequence INTEGER NOT NULL, path TEXT NOT NULL, sha256 TEXT NOT NULL REFERENCES execution_file_captures(sha256), PRIMARY KEY(run_id,sequence,path), FOREIGN KEY(run_id,sequence) REFERENCES execution_events(run_id,sequence) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS execution_file_refs_digest ON execution_file_refs(sha256)")
    }
    fun collect(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM execution_file_captures WHERE NOT EXISTS (SELECT 1 FROM execution_file_refs r WHERE r.sha256=execution_file_captures.sha256)")
    }
    /** Omitted copies keep observed hashes/excerpts; a null reference is never rendered as a full diff. */
    fun prepare(db: SQLiteDatabase, runId: String, batch: List<ExecutionEvent>, quotaBytes: Long): List<ExecutionEvent> {
        require(quotaBytes in 0..MAX_QUOTA_BYTES)
        val runHashes = db.rawQuery("SELECT DISTINCT sha256 FROM execution_file_refs WHERE run_id=?", arrayOf(runId)).use { c ->
            buildSet { while(c.moveToNext()) add(c.getString(0)) }
        }.toMutableSet()
        var runBytes = db.rawQuery("SELECT COALESCE(SUM(length(content)),0) FROM execution_file_captures WHERE sha256 IN (SELECT sha256 FROM execution_file_refs WHERE run_id=?)", arrayOf(runId)).use { c -> c.moveToFirst();c.getLong(0) }
        var totalBytes = db.rawQuery("SELECT COALESCE(SUM(length(content)),0) FROM execution_file_captures",null).use { c -> c.moveToFirst();c.getLong(0) }
        return batch.map { event ->
            val snapshot = event.workspace ?: return@map event
            val files = snapshot.files.mapValues { (_, value) ->
                value.validateCapture()
                val text = value.captureText
                // Fresh producers must provide content, not claim an arbitrary existing digest.
                require(text != null || value.captureSha256 == null)
                if(text == null) value else {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    val hash = requireNotNull(value.captureSha256)
                    val existing = db.rawQuery("SELECT length(content) FROM execution_file_captures WHERE sha256=?",arrayOf(hash)).use { c ->
                        if(c.moveToFirst()) c.getLong(0) else null
                    }
                    check(existing == null || existing == bytes.size.toLong()) { "Stored capture size differs" }
                    val newToRun = hash !in runHashes
                    val admitted = (!newToRun || runBytes + bytes.size <= MAX_RUN_BYTES) &&
                        (existing != null || totalBytes + bytes.size <= quotaBytes)
                    if(admitted) {
                        if(existing == null) {
                            db.insertOrThrow("execution_file_captures",null,ContentValues().apply { put("sha256",hash);put("content",bytes) })
                            totalBytes += bytes.size
                        }
                        if(newToRun) { runHashes += hash;runBytes += bytes.size }
                    }
                    value.copy(captureText=null,captureSha256=if(admitted) hash else null)
                }
            }
            event.copy(workspace=snapshot.copy(files=files))
        }
    }
    fun reference(db: SQLiteDatabase, runId: String, sequence: Int, event: ExecutionEvent) {
        event.workspace?.files?.forEach { (path,value) ->
            value.captureSha256?.let { hash -> db.insertOrThrow("execution_file_refs",null,ContentValues().apply {
                put("run_id",runId);put("sequence",sequence);put("path",path);put("sha256",hash)
            }) }
        }
    }
    fun read(db: SQLiteDatabase, sessionId: String, runId: String, sequence: Int, path: String,
        checkActive: () -> Unit): String? {
        require(sequence in 0..511 && path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size<=1024 &&
            !path.startsWith('/') && path.none(Char::isISOControl) && path.split('/').none { it in setOf("",".","..") })
        val args = arrayOf(sessionId,runId,sequence.toString(),path)
        val where = " FROM execution_file_refs f JOIN execution_runs r ON r.id=f.run_id JOIN execution_file_captures c ON c.sha256=f.sha256 WHERE r.session_id=? AND f.run_id=? AND f.sequence=? AND f.path=?"
        checkActive()
        val first = db.rawQuery("SELECT c.sha256,length(c.content),substr(c.content,1,16384)"+where,args).use { c ->
            if(!c.moveToFirst()) null else Triple(c.getString(0),c.getInt(1),c.getBlob(2))
        } ?: return null
        val (hash,size,head) = first
        check(size in 0..MAX_FILE_CAPTURE_BYTES)
        val output=ByteArrayOutputStream(size);output.write(head)
        while(output.size()<size) {
            checkActive()
            val part = db.rawQuery("SELECT substr(c.content,?,16384)"+where,arrayOf((output.size()+1).toString(),*args)).use { c ->
                check(c.moveToFirst());c.getBlob(0)
            }
            check(part.isNotEmpty() && output.size()+part.size<=size);output.write(part)
        }
        checkActive()
        val bytes=output.toByteArray()
        check(bytes.size==size && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }==hash) { "Stored capture integrity failed" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
