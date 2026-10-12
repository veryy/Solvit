package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.core.*
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real Android SQLite reopen -> existing chat bubble -> collapsed durable facts; no model/tool replay. */
class ExecutionJournalUiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Test fun interruptedObservationsReopenInsideOneReplyAndNeverInventMissingResults() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        lateinit var source: ExecutionRun
        SqliteChatStore(app).use { store -> runBlocking {
            val session=store.createSession();val userId=UUID.randomUUID().toString()
            store.append(ChatMessage(userId,session.id,Role.USER,"Run my project"))
            source=ExecutionRun(UUID.randomUUID().toString(),session.id,userId,null,"fixture-workspace",1)
            assertTrue(store.beginExecutionRun(source))
            val batch=listOf(ExecutionEvent(1,ExecutionKind.STEP_STARTED),
                ExecutionEvent(1,ExecutionKind.TOOL_PROPOSED,ToolCall("saved","fixture_write","{\"path\":\"main.sh\"}"),"files"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_BASELINE,workspace=WorkspaceSnapshot("fixture-workspace",emptyMap(),true,1)),
                ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="saved"),
                ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="saved",stage=ToolStage.SUCCEEDED,result="saved journal proof"),
                ExecutionEvent(1,ExecutionKind.WORKSPACE_OBSERVED,workspace=WorkspaceSnapshot("fixture-workspace",mapOf("main.sh" to FileObservation(FileObservationKind.TEXT,17,"a".repeat(64),"saved file preview")),true,2)),
                ExecutionEvent(1,ExecutionKind.STEP_FINISHED),ExecutionEvent(2,ExecutionKind.STEP_STARTED),
                ExecutionEvent(2,ExecutionKind.TOOL_PROPOSED,ToolCall("unknown","fixture_shell","{\"command\":\"sh main.sh\"}"),"shell"),
                ExecutionEvent(2,ExecutionKind.TOOL_STARTED,callId="unknown"))
            assertTrue(store.appendExecutionEvents(source.id,0,batch,2))
        } }
        val reads=AtomicInteger()
        SqliteChatStore(app).use { reopened ->
            val rows=runBlocking {
                assertTrue(reopened.recoverInterruptedExecutions(3)>=1)
                attachExecutions(presentChat(reopened.history(source.sessionId)),reopened.executionRuns(source.sessionId).first())
            }
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
                ChatTimeline(rows,source.sessionId,loadExecutionEvents={ run -> reads.incrementAndGet();reopened.executionEvents(run.sessionId,run.id) })
            } } }
            rule.onAllNodesWithTag("assistant_message").assertCountEquals(1)
            rule.onNodeWithTag("execution_journal_details").assertDoesNotExist();assertEquals(0,reads.get())
            rule.onNodeWithTag("execution_journal_toggle").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("saved journal proof").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("saved file preview").assertDoesNotExist()
            rule.onNodeWithTag("workspace_changes_toggle").performClick()
            rule.onNodeWithText("仍有操作未记录后续观察，文件当前状态需重新检查").assertExists()
            rule.onNodeWithTag("workspace_change_main.sh").performClick()
            rule.onNodeWithText("saved file preview").assertExists()
            rule.onNodeWithText("文件不存在").assertExists()
            rule.onNodeWithTag("workspace_changes_toggle").performClick()
            rule.onNodeWithTag("execution_journal_details").performScrollToNode(hasText("结果未知，请先检查实际状态"))
            rule.onNodeWithText("结果未知，请先检查实际状态").assertIsDisplayed()
            rule.onNodeWithText("仅显示已记录的事实，不会自动重跑操作").assertExists()
            assertEquals(1,reads.get())
            rule.onNodeWithTag("execution_journal_toggle").performClick()
            rule.onNodeWithTag("execution_journal_details").assertDoesNotExist()
            rule.onNodeWithTag("execution_journal_toggle").performClick()
            rule.waitUntil(10_000) { reads.get()==2 }
            rule.onAllNodesWithTag("assistant_message").assertCountEquals(1)
            rule.onNodeWithTag("execution_journal_toggle").performClick()
            runBlocking { reopened.deleteSession(source.sessionId) }
        }
    }
}
