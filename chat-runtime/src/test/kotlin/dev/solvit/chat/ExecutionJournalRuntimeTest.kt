package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class JournalStore : ChatStore {
    val saved=mutableListOf<ChatMessage>()
    var run: ExecutionRun?=null
    val events=mutableListOf<ExecutionEvent>()
    var reject: ExecutionKind?=null
    var throws=false
    override val supportsExecutionJournal=true
    override fun sessions()=flowOf(listOf(ChatSession("session","test",0)))
    override fun messages(sessionId: String)=flowOf(saved.toList())
    override suspend fun createSession()=ChatSession("session","test",0)
    override suspend fun history(sessionId: String)=saved.filter { it.sessionId==sessionId }
    override suspend fun append(message: ChatMessage) { saved+=message }
    override suspend fun deleteSession(sessionId: String)=Unit
    override suspend fun beginExecutionRun(run: ExecutionRun): Boolean {
        assertEquals(run.userMessageId,saved.last().id);this.run=run;return true
    }
    override suspend fun appendExecutionEvents(runId: String, expectedSequence: Int, events: List<ExecutionEvent>, now: Long): Boolean {
        assertEquals(run!!.id,runId);assertEquals(this.events.size,expectedSequence)
        if(events.any { it.kind==reject }) { if(throws) error("private disk failure") else return false }
        val state=ExecutionJournalState();this.events.forEach(state::accept);events.forEach(state::accept)
        this.events+=events;return true
    }
    override suspend fun finishExecutionRun(runId: String, status: ExecutionStatus, now: Long): Boolean {
        run=run!!.copy(status=status,updatedAt=now);return true
    }
}

class ExecutionJournalRuntimeTest {
    private val allow=object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor)=true }
    private val call=ToolCall("call","write","{}")
    private fun provider(onFollowUp: (ModelRequest)->Unit={})=object : ModelProvider {
        override val id="fixture"
        var requests=0
        override fun stream(request: ModelRequest)=flow {
            if(requests++==0) emit(ModelChunk.ToolCalls(listOf(call)))
            else { onFollowUp(request);emit(ModelChunk.Text("verified")) }
            emit(ModelChunk.Done)
        }
    }
    private fun tools(execute: suspend ()->String): ToolRegistry = ToolRegistry(EventBus()).apply {
        register(object : SolvitTool {
            override val descriptor=ToolDescriptor("write","write","{}",pluginId="files")
            override suspend fun execute(argumentsJson: String)=execute()
        })
    }
    @Test fun recordsProposalBeforeApprovalAndStartBeforeEffectsAndResultBeforeFollowUp() = runTest {
        val store=JournalStore()
        val gate=object : ToolAuthorization {
            override suspend fun authorize(descriptor: ToolDescriptor): Boolean {
                assertEquals(ExecutionKind.TOOL_PROPOSED,store.events.last().kind)
                assertEquals(listOf(Role.USER),store.saved.map { it.role });return true
            }
        }
        val agent=ToolAgentRuntime(provider {
            assertEquals(ExecutionKind.STEP_STARTED,store.events.last().kind)
            assertEquals("written",store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }.result)
            assertEquals("written",it.messages.last { m -> m.role==Role.TOOL }.content)
        },tools {
            assertEquals(ExecutionKind.TOOL_STARTED,store.events.last().kind)
            assertEquals(listOf(Role.USER),store.saved.map { it.role });"written"
        })
        DefaultChatService(store,agent,EventBus()).send("session","actually write",ModelConfig(),gate).toList()
        assertEquals(ExecutionStatus.COMPLETE,store.run!!.status)
        assertEquals(listOf(ExecutionKind.STEP_STARTED,ExecutionKind.TOOL_PROPOSED,ExecutionKind.TOOL_STARTED,
            ExecutionKind.TOOL_FINISHED,ExecutionKind.STEP_FINISHED,ExecutionKind.STEP_STARTED,ExecutionKind.STEP_FINISHED),store.events.map { it.kind })
    }
    @Test fun failedDurableStartStopsSideEffectsForRefusalAndStorageException() = runTest {
        for(throws in listOf(false,true)) {
            val store=JournalStore().apply { reject=ExecutionKind.TOOL_STARTED;this.throws=throws }
            var effects=0
            val agent=ToolAgentRuntime(provider(),tools { effects++;"written" })
            assertFailsWith<ExecutionJournalException> { DefaultChatService(store,agent,EventBus()).send("session","write",ModelConfig(),allow).toList() }
            assertEquals(0,effects);assertEquals(ExecutionStatus.FAILED,store.run!!.status)
            assertFalse(store.events.any { it.kind==ExecutionKind.TOOL_STARTED || it.kind==ExecutionKind.TOOL_FINISHED })
        }
    }
    @Test fun cancellationKeepsUnknownDispatchAndDoesNotCreateSuccessfulResultOrReplayIt() = runTest {
        val store=JournalStore();val entered=CompletableDeferred<Unit>();var effects=0
        val agent=ToolAgentRuntime(provider(),tools { effects++;entered.complete(Unit);awaitCancellation() })
        val chat=DefaultChatService(store,agent,EventBus())
        val task=launch { chat.send("session","run",ModelConfig(),allow).collect() }
        entered.await();task.cancelAndJoin()
        assertEquals(1,effects);assertEquals(ExecutionStatus.CANCELLED,store.run!!.status)
        assertEquals(ExecutionKind.TOOL_STARTED,store.events.last().kind)
        assertFalse(store.events.any { it.kind==ExecutionKind.TOOL_FINISHED })
        assertTrue(store.saved.any { it.status==MessageStatus.INTERRUPTED })
        var observed: ModelRequest?=null
        val next=object : AgentRuntime { override fun run(request: ModelRequest): Flow<ModelChunk> {
            observed=request;return flowOf(ModelChunk.Text("next"),ModelChunk.Done)
        } }
        DefaultChatService(store,next,EventBus()).send("session","next request",ModelConfig()).toList()
        assertEquals(1,effects);assertFalse(observed!!.messages.any { it.role==Role.TOOL || it.toolCalls.isNotEmpty() })
    }
    @Test fun journalRedactsAndBoundsObservationsWithoutChangingActualToolResult() = runTest {
        val store=JournalStore();val secret="private-model-key"
        val result="Bearer upstream-secret $secret\n"+"中".repeat(30000)
        val agent=ToolAgentRuntime(provider { request -> assertEquals(result,request.messages.last { it.role==Role.TOOL }.content) },tools { result })
        DefaultChatService(store,agent,EventBus()).send("session","run",ModelConfig(apiKey=secret),allow).toList()
        val recorded=store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }
        assertTrue(recorded.truncated);assertTrue(recorded.result!!.toByteArray(Charsets.UTF_8).size<=64*1024)
        assertFalse(recorded.result.contains(secret));assertFalse(recorded.result.contains("upstream-secret"))
        assertFalse(recorded.result.contains('\uFFFD'))
    }
    @Test fun denialIsRecordedWithoutDispatchingOrInventingStartedState() = runTest {
        val store=JournalStore();var effects=0
        DefaultChatService(store,ToolAgentRuntime(provider(),tools { effects++;"not allowed" }),EventBus())
            .send("session","write",ModelConfig()).toList()
        assertEquals(0,effects);assertFalse(store.events.any { it.kind==ExecutionKind.TOOL_STARTED })
        assertEquals(ToolStage.DENIED,store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }.stage)
        assertEquals(ExecutionStatus.COMPLETE,store.run!!.status)
    }
}
