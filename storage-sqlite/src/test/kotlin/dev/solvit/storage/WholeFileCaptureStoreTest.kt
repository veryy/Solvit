package dev.solvit.storage

import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WholeFileCaptureStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clean() { context.deleteDatabase("solvit.db") }
    private fun capture(text:String):FileObservation {
        val b=text.toByteArray(Charsets.UTF_8)
        val hash=MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        return FileObservation(FileObservationKind.TEXT,b.size.toLong(),hash,text.take(40),b.size>40,text,hash)
    }
    private suspend fun save(store:SqliteChatStore,id:String,text:String):ExecutionRun {
        val session=store.createSession()
        store.append(ChatMessage("user-$id",session.id,Role.USER,"Edit a file"))
        val run=ExecutionRun("run-$id",session.id,"user-$id",null,"workspace",1)
        assertTrue(store.beginExecutionRun(run))
        val snapshot=WorkspaceSnapshot("workspace",mapOf("src/code.txt" to capture(text)),true,2)
        assertTrue(store.appendExecutionEvents(run.id,0,listOf(
            ExecutionEvent(1,ExecutionKind.STEP_STARTED),
            ExecutionEvent(1,ExecutionKind.TOOL_PROPOSED,ToolCall("call","write","{}"),"files"),
            ExecutionEvent(1,ExecutionKind.WORKSPACE_BASELINE,workspace=snapshot)),2))
        return run
    }
    private fun blobCount():Int=SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
        db.rawQuery("SELECT COUNT(*) FROM execution_file_captures",null).use { c -> c.moveToFirst();c.getInt(0) }
    }
    @Test fun fullUnicodeSurvivesReopenAndForeignChatEventPathCannotReadIt()=runBlocking<Unit> {
        val text="完整代码🙂\n".repeat(10000)+"TAIL"
        lateinit var run:ExecutionRun
        SqliteChatStore(context).use { run=save(it,"first",text) }
        SqliteChatStore(context).use { store ->
            assertEquals(text,store.executionFileContent(run.sessionId,run.id,2,"src/code.txt"))
            assertNull(store.executionFileContent("another-chat",run.id,2,"src/code.txt"))
            assertNull(store.executionFileContent(run.sessionId,run.id,1,"src/code.txt"))
            assertNull(store.executionFileContent(run.sessionId,run.id,2,"else.txt"))
            val recorded=store.executionEvents(run.sessionId,run.id)[2].event.workspace!!.files.getValue("src/code.txt")
            assertNull(recorded.captureText);assertEquals(capture(text).sha256,recorded.captureSha256)
        }
    }
    @Test fun deduplicatedCopiesLiveUntilLastOwningChatIsDeletedAndBranchCannotInheritThem()=runBlocking<Unit> {
        val text="shared copy\n".repeat(700)
        SqliteChatStore(context).use { store ->
            val a=save(store,"a",text);val b=save(store,"b",text)
            assertEquals(text,store.executionFileContent(a.sessionId,a.id,2,"src/code.txt"))
            assertEquals(1,blobCount())
            val branch=store.forkSession(a.sessionId,BranchPoint(a.userMessageId,BranchMode.BEFORE_USER))
            assertNull(store.executionFileContent(branch.id,a.id,2,"src/code.txt"))
            store.deleteSession(a.sessionId);assertEquals(1,blobCount())
            assertEquals(text,store.executionFileContent(b.sessionId,b.id,2,"src/code.txt"))
            store.deleteSession(b.sessionId);assertEquals(0,blobCount())
        }
    }
    @Test fun failedBatchDoesNotLeakCapturesAndInvalidContentCannotReplaceAHash()=runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val run=save(store,"a","original")
            assertEquals("original",store.executionFileContent(run.sessionId,run.id,2,"src/code.txt"))
            val forged=capture("another").copy(sha256=capture("original").sha256,captureSha256=capture("original").sha256)
            val snapshot=WorkspaceSnapshot("workspace",mapOf("src/code.txt" to forged),true,3)
            assertFailsWith<IllegalArgumentException> { store.appendExecutionEvents(run.id,3,listOf(
                ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="call"),
                ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.SUCCEEDED,result="done"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_OBSERVED,workspace=snapshot)),3) }
            assertEquals(3,store.executionEvents(run.sessionId,run.id).size)
            assertEquals(1,blobCount())
            assertEquals("original",store.executionFileContent(run.sessionId,run.id,2,"src/code.txt"))
        }
    }
    @Test fun fullCopyReadRechecksAuthorityAndPreservesParentCancellation()=runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val run=save(store,"a","immutable")
            var checks=0
            assertFailsWith<IllegalStateException> { store.executionFileContent(run.sessionId,run.id,2,"src/code.txt") { if(++checks==2)error("Revoked") } }
            assertEquals(2,checks)
            val parent=Job().apply { cancel() }
            assertFailsWith<CancellationException> { withContext(parent) { store.executionFileContent(run.sessionId,run.id,2,"src/code.txt") } }
        }
    }
    @Test fun captureBudgetOmitsOnlyFullCopiesAndNeverFabricatesReferences()=runBlocking<Unit> {
        SqliteChatStore(context,captureQuotaBytes=8).use { store ->
            val run=save(store,"small","body longer than quota")
            val recorded=store.executionEvents(run.sessionId,run.id)[2].event.workspace!!.files.getValue("src/code.txt")
            assertEquals(FileObservationKind.TEXT,recorded.kind);assertNotNull(recorded.sha256)
            assertNull(recorded.captureSha256);assertNull(store.executionFileContent(run.sessionId,run.id,2,"src/code.txt"))
            assertEquals(0,blobCount())
        }
    }
    @Test fun eightLargeEscapedCopiesStayOutsideEventJsonAndExactByteBoundarySurvivesReopen()=runBlocking<Unit> {
        val text="\\\"\t\n".repeat(MAX_FILE_CAPTURE_BYTES/4)
        assertEquals(MAX_FILE_CAPTURE_BYTES,text.toByteArray().size)
        lateinit var run:ExecutionRun
        SqliteChatStore(context).use { store ->
            run=save(store,"big",text)
            assertEquals(text,store.executionFileContent(run.sessionId,run.id,2,"src/code.txt"))
            val snapshot=WorkspaceSnapshot("workspace",(0..7).associate { "copy-$it.txt" to capture(text) },true,3)
            assertTrue(store.appendExecutionEvents(run.id,3,listOf(
                ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="call"),
                ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.SUCCEEDED,result="done"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_OBSERVED,workspace=snapshot)),3))
            assertEquals(1,blobCount())
        }
        SqliteChatStore(context).use { store ->
            assertEquals(text,store.executionFileContent(run.sessionId,run.id,5,"copy-7.txt"))
            val snapshot=store.executionEvents(run.sessionId,run.id)[5].event.workspace!!
            assertTrue(snapshot.files.values.all { it.captureText==null && it.captureSha256!=null })
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT MAX(length(CAST(payload AS BLOB))) FROM execution_events",null).use { c -> c.moveToFirst();assertTrue(c.getLong(0)<16*1024) }
        }
    }
    @Test fun schemaEightMigrationKeepsOldJournalAndCannotInventMissingFullCopies()=runBlocking<Unit> {
        lateinit var run:ExecutionRun
        SqliteChatStore(context).use { store -> run=save(store,"legacy","older body") }
        SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE execution_file_refs");db.execSQL("DROP TABLE execution_file_captures")
            db.execSQL("UPDATE execution_events SET payload=replace(payload,?,?)",arrayOf("\"captureSha256\":\"${capture("older body").sha256}\"","\"captureSha256\":null"))
            db.version=8
        }
        SqliteChatStore(context).use { store ->
            assertEquals("Edit a file",store.history(run.sessionId).single().content)
            assertEquals(3,store.executionEvents(run.sessionId,run.id).size)
            assertNull(store.executionFileContent(run.sessionId,run.id,2,"src/code.txt"))
            assertEquals(0,blobCount())
        }
    }

}
