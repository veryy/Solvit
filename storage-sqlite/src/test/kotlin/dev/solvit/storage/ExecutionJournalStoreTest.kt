package dev.solvit.storage

import android.database.sqlite.SQLiteDatabase
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ExecutionJournalStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clean() { context.deleteDatabase("solvit.db") }
    private fun proposed(id: String="call")=ExecutionEvent(1,ExecutionKind.TOOL_PROPOSED,ToolCall(id,"write","{\"path\":\"main.sh\"}"),"files")
    private suspend fun start(store: SqliteChatStore): ExecutionRun {
        val session=store.createSession();store.append(ChatMessage("user",session.id,Role.USER,"create a project"))
        return ExecutionRun("run",session.id,"user","coder","workspace",10).also { assertTrue(store.beginExecutionRun(it)) }
    }
    @Test fun versionTenUpgradePreservesFailedFactsAndAdmitsNewAttemptForSameUser() = runBlocking<Unit> {
        lateinit var run: ExecutionRun
        SqliteChatStore(context).use { store ->
            run=start(store)
            assertTrue(store.appendExecutionEvents(run.id,0,listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED)),11))
            assertTrue(store.finishExecutionRun(run.id,ExecutionStatus.FAILED,12))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                // Install the actual v10 UNIQUE owner constraint, not only a downgraded version number.
                db.execSQL("CREATE TEMP TABLE old_runs AS SELECT * FROM execution_runs")
                db.execSQL("CREATE TEMP TABLE old_events AS SELECT * FROM execution_events")
                db.execSQL("DROP TABLE execution_file_refs")
                db.execSQL("DROP TABLE execution_events")
                db.execSQL("DROP TABLE execution_runs")
                db.execSQL("CREATE TABLE execution_runs (id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, user_message_id TEXT NOT NULL UNIQUE REFERENCES messages(id) ON DELETE CASCADE, role_id TEXT, workspace_id TEXT, started_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE INDEX execution_runs_session ON execution_runs(session_id, started_at)")
                db.execSQL("CREATE UNIQUE INDEX execution_runs_active ON execution_runs(session_id) WHERE status='RUNNING'")
                db.execSQL("CREATE TABLE execution_events (run_id TEXT NOT NULL REFERENCES execution_runs(id) ON DELETE CASCADE, sequence INTEGER NOT NULL, recorded_at INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id, sequence))")
                db.execSQL("INSERT INTO execution_runs SELECT * FROM old_runs")
                db.execSQL("INSERT INTO execution_events SELECT * FROM old_events")
                ExecutionFileCaptureDatabase.create(db)
                db.execSQL("DROP TABLE old_events");db.execSQL("DROP TABLE old_runs")
                db.version=10
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        SqliteChatStore(context).use { store ->
            assertEquals(1,store.executionEvents(run.sessionId,run.id).size)
            assertEquals("create a project",store.history(run.sessionId).single().content)
            val retry=run.copy(id="retry",startedAt=20,updatedAt=20)
            assertTrue(store.beginExecutionRun(retry))
            assertFalse(store.beginExecutionRun(retry.copy(id="concurrent")))
            assertTrue(store.finishExecutionRun(retry.id,ExecutionStatus.COMPLETE,21))
            assertEquals(listOf(ExecutionStatus.FAILED,ExecutionStatus.COMPLETE),store.executionRuns(run.sessionId).first().map { it.status })
            assertFalse(store.beginExecutionRun(retry.copy(id="after-success",startedAt=30,updatedAt=30)))
            assertEquals(1,store.executionEvents(run.sessionId,run.id).size)
        }
    }
    @Test fun factsAreDurableBeforeTurnSettlementAndWritesAreOrderedAndScoped() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val run=start(store)
            assertTrue(store.appendExecutionEvents(run.id,0,listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED),proposed()),11))
            assertFalse(store.appendExecutionEvents(run.id,0,listOf(ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="call")),12))
            assertFailsWith<IllegalArgumentException> { store.appendExecutionEvents(run.id,2,listOf(ExecutionEvent(1,ExecutionKind.STEP_FINISHED)),12) }
            assertEquals(2,store.executionEvents(run.sessionId,run.id).size)
            SqliteChatStore(context).use { observer ->
                assertEquals(listOf(Role.USER),observer.history(run.sessionId).map { it.role })
                assertEquals(2,observer.executionEvents(run.sessionId,run.id).size)
                assertEquals(ExecutionStatus.RUNNING,observer.executionRuns(run.sessionId).first().single().status)
                assertTrue(observer.executionEvents("other-session",run.id).isEmpty())
            }
            assertTrue(store.appendExecutionEvents(run.id,2,listOf(ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="call")),12))
            assertTrue(store.appendExecutionEvents(run.id,3,listOf(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.SUCCEEDED,result="written"),ExecutionEvent(1,ExecutionKind.STEP_FINISHED)),13))
            assertTrue(store.finishExecutionRun(run.id,ExecutionStatus.COMPLETE,14))
            assertFalse(store.appendExecutionEvents(run.id,5,listOf(ExecutionEvent(2,ExecutionKind.STEP_STARTED)),15))
        }
    }
    @Test fun startupRecoveryPreservesFinishedFactsAndDoesNotInventResultsForPendingCalls() = runBlocking<Unit> {
        lateinit var run: ExecutionRun
        SqliteChatStore(context).use { store ->
            run=start(store)
            assertTrue(store.appendExecutionEvents(run.id,0,listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED),proposed("done"),proposed("unknown"),proposed("pending")),11))
            assertTrue(store.appendExecutionEvents(run.id,4,listOf(ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="done"),ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="done",stage=ToolStage.SUCCEEDED,result="actual result"),ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="unknown")),12))
        }
        SqliteChatStore(context).use { reopened ->
            val before=reopened.executionEvents(run.sessionId,run.id)
            assertEquals(1,reopened.recoverInterruptedExecutions(5)) // Clock rollback cannot invalidate timestamps.
            assertEquals(ExecutionStatus.PROCESS_INTERRUPTED,reopened.executionRuns(run.sessionId).first().single().status)
            assertEquals(before,reopened.executionEvents(run.sessionId,run.id))
            assertEquals(0,reopened.recoverInterruptedExecutions(20))
            assertFalse(reopened.appendExecutionEvents(run.id,7,listOf(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="unknown",stage=ToolStage.SUCCEEDED,result="invented")),21))
            assertEquals(1,reopened.history(run.sessionId).size)
        }
    }
    @Test fun onlyCurrentUserOwnsRunAndDeletingChatCascadesWithoutCopyingJournalIntoBranch() = runBlocking<Unit> {
        SqliteChatStore(context).use { store ->
            val run=start(store)
            assertFalse(store.beginExecutionRun(run.copy(id="second")))
            assertFailsWith<IllegalArgumentException> { store.finishExecutionRun(run.id,ExecutionStatus.RUNNING,11) }
            assertTrue(store.finishExecutionRun(run.id,ExecutionStatus.CANCELLED,11))
            store.append(ChatMessage("new",run.sessionId,Role.USER,"new task"))
            assertFalse(store.beginExecutionRun(run.copy(id="stale")))
            val branch=store.forkSession(run.sessionId,BranchPoint("new",BranchMode.BEFORE_USER))
            assertTrue(store.executionRuns(branch.id).first().isEmpty())
            store.deleteSession(run.sessionId)
            assertTrue(store.executionRuns(run.sessionId).first().isEmpty());assertTrue(store.executionEvents(run.sessionId,run.id).isEmpty())
        }
    }
    @Test fun largeMultibyteAndEscapedObservationsSurviveReopenWithoutCursorWindowOverflow() = runBlocking<Unit> {
        lateinit var run: ExecutionRun
        val result="中\u0000".repeat(16000)
        SqliteChatStore(context).use { store ->
            run=start(store)
            val batch=listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED),proposed(),ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="call"),ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.SUCCEEDED,result=result,truncated=true),ExecutionEvent(1,ExecutionKind.STEP_FINISHED))
            assertTrue(store.appendExecutionEvents(run.id,0,batch,11));assertTrue(store.finishExecutionRun(run.id,ExecutionStatus.COMPLETE,12))
        }
        SqliteChatStore(context).use { store -> assertEquals(result,store.executionEvents(run.sessionId,run.id)[3].event.result) }
    }
    @Test fun versionSevenMigrationPreservesChatsAndCompletedTurnAnchorsWithoutInventingExecutions() = runBlocking<Unit> {
        lateinit var session: ChatSession
        SqliteChatStore(context).use { store ->
            session=store.createSession();store.append(ChatMessage("user",session.id,Role.USER,"old task"))
            val turn=CompletedChatTurn("answer",session.id,"user","answer",null,null,null,null,"fixture","model",1,emptyList())
            store.appendCompletedTurn(listOf(ChatMessage("answer",session.id,Role.ASSISTANT,"old answer")),turn)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath("solvit.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE execution_events");db.execSQL("DROP TABLE execution_runs");db.version=7
        }
        SqliteChatStore(context).use { store ->
            assertEquals(listOf("old task","old answer"),store.history(session.id).map { it.content })
            assertEquals("answer",store.recordedTurns(0).single().turn.id)
            assertTrue(store.executionRuns(session.id).first().isEmpty())
        }
    }
}
