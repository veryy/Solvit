package dev.solvit.core

import kotlin.test.*

class ExecutionJournalTest {
    private val proposed=ExecutionEvent(1,ExecutionKind.TOOL_PROPOSED,ToolCall("call","write","{}"),"files")
    @Test fun refusesUnmatchedDuplicateAndPrematureCompletion() {
        val state=ExecutionJournalState()
        assertFailsWith<IllegalArgumentException> { state.accept(proposed) }
        state.accept(ExecutionEvent(1,ExecutionKind.STEP_STARTED));state.accept(proposed)
        assertFailsWith<IllegalArgumentException> { state.accept(proposed) }
        assertFailsWith<IllegalArgumentException> { state.accept(ExecutionEvent(1,ExecutionKind.STEP_FINISHED)) }
        assertFailsWith<IllegalArgumentException> { state.accept(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="other",stage=ToolStage.FAILED,result="failed")) }
        assertFailsWith<IllegalArgumentException> { state.accept(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.SUCCEEDED,result="ok")) }
        state.accept(ExecutionEvent(1,ExecutionKind.TOOL_STARTED,callId="call"))
        assertFailsWith<IllegalArgumentException> { state.accept(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.DENIED,result="denied")) }
        state.accept(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.SUCCEEDED,result="ok"))
        state.accept(ExecutionEvent(1,ExecutionKind.STEP_FINISHED));assertFalse(state.stepOpen)
        state.accept(ExecutionEvent(2,ExecutionKind.STEP_STARTED))
        assertFailsWith<IllegalArgumentException> { state.accept(proposed.copy(step=2)) }
    }
    @Test fun recordsDenialBeforeDispatchAndRejectsUnboundedOrAmbiguousPayloads() {
        val state=ExecutionJournalState();state.accept(ExecutionEvent(1,ExecutionKind.STEP_STARTED));state.accept(proposed)
        state.accept(ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="call",stage=ToolStage.DENIED,result="denied"))
        state.accept(ExecutionEvent(1,ExecutionKind.STEP_FINISHED))
        assertFailsWith<IllegalArgumentException> { proposed.copy(call=proposed.call!!.copy(argumentsJson="中".repeat(6000))).validate() }
        assertFailsWith<IllegalArgumentException> { ExecutionEvent(1,ExecutionKind.STEP_STARTED,result="forged").validate() }
        assertFailsWith<IllegalArgumentException> { ExecutionEvent(1,ExecutionKind.TOOL_FINISHED,callId="c",stage=ToolStage.RUNNING,result="not complete").validate() }
    }
}
