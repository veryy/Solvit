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
    var afterAppend: suspend (List<ExecutionEvent>)->Unit = {}
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
        this.events+=events;afterAppend(events);return true
    }
    override suspend fun finishExecutionRun(runId: String, status: ExecutionStatus, now: Long): Boolean {
        run=run!!.copy(status=status,updatedAt=now);return true
    }
}

class ExecutionJournalRuntimeTest {
    @Test fun authorizationRevokedDuringDurableStartPreventsEffectsAndFurtherWorkspaceReads() = runTest {
        val store=JournalStore();val ws=ObservedWorkspace();var effects=0
        val policies=object : ToolPolicyService {
            override val policies=MutableStateFlow<Map<String,ToolPolicy>>(emptyMap())
            override suspend fun set(tool: ToolDescriptor,policy: ToolPolicy) { policies.value=mapOf(toolPolicyKey(tool) to policy) }
        }
        val registry=ToolRegistry(EventBus(),policies).apply { register(object : SolvitTool {
            override val descriptor=ToolDescriptor("write","write","{}",pluginId="files",capabilities=setOf("workspace"))
            override suspend fun execute(argumentsJson: String): String { effects++;return "must not execute" }
        }) }
        store.afterAppend={ batch ->
            if(batch.any { it.kind==ExecutionKind.TOOL_STARTED }) policies.set(registry.descriptors().single(),ToolPolicy.DENY)
        }
        val agent=ToolAgentRuntime(provider { request ->
            assertEquals(ToolStage.FAILED,request.messages.last { it.role==Role.TOOL }.toolStage)
        },registry)
        DefaultChatService(store,agent,EventBus(),workspaces=ws).send("session","run",ModelConfig(),allow).toList()
        assertEquals(0,effects);assertEquals(1,ws.snapshots)
        assertTrue(store.events.none { it.kind==ExecutionKind.WORKSPACE_OBSERVED })
        assertEquals(ToolStage.FAILED,store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }.stage)
        assertEquals(ExecutionStatus.COMPLETE,store.run!!.status)
    }
    @Test fun denialAfterDispatchSettlesWithoutPretendingTheToolNeverStarted() = runTest {
        val store=JournalStore();var effects=0
        val agent=ToolAgentRuntime(provider { request ->
            assertEquals(ToolStage.FAILED,request.messages.last { it.role==Role.TOOL }.toolStage)
        },tools { effects++;throw ToolDeniedException() })
        DefaultChatService(store,agent,EventBus()).send("session","run",ModelConfig(),allow).toList()
        assertEquals(1,effects)
        assertEquals(ExecutionStatus.COMPLETE,store.run!!.status)
        assertTrue(store.events.any { it.kind==ExecutionKind.TOOL_STARTED })
        assertEquals(ToolStage.FAILED,store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }.stage)
    }
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
    private fun tools(capabilities: Set<String> = emptySet(), execute: suspend ()->String): ToolRegistry = ToolRegistry(EventBus()).apply {
        register(object : SolvitTool {
            override val descriptor=ToolDescriptor("write","write","{}",pluginId="files",capabilities=capabilities)
            override suspend fun execute(argumentsJson: String)=execute()
        })
    }
    private class ObservedWorkspace : WorkspaceService {
        private val workspace=Workspace("workspace","test")
        override val state=MutableStateFlow(WorkspaceState(listOf(workspace),mapOf("session" to workspace.id)))
        override val jobs=MutableStateFlow<List<ShellJob>>(emptyList())
        var snapshots=0
        var written=false
        var supplied: Map<String,FileObservation>?=null
        override suspend fun snapshot(workspaceId: String): WorkspaceSnapshot {
            snapshots++
            val files=supplied ?: if(written) mapOf("main.sh" to FileObservation(FileObservationKind.TEXT,2,"a".repeat(64),"ok")) else emptyMap()
            return WorkspaceSnapshot(workspaceId,files,true,0)
        }
        override suspend fun forChat(sessionId: String): Workspace=error("No implicit creation")
        override suspend fun bind(sessionId: String,workspaceId: String)=Unit
        override suspend fun unbind(sessionId: String) { state.value=state.value.copy(bindings=emptyMap()) }
        override suspend fun create(name: String): Workspace=error("Not used")
        override suspend fun createShell(workspaceId: String,name: String): ShellSession=error("Not used")
        override suspend fun read(workspaceId: String,path: String): String=error("Not used")
        override suspend fun write(workspaceId: String,path: String,text: String)=Unit
        override suspend fun run(workspaceId: String,shellId: String,command: String,timeoutMs: Long): ShellJob=error("Not used")
        override suspend fun cancel(workspaceId: String,jobId: String)=Unit
        override suspend fun cancelOwner(owner: String)=Unit
    }
    @Test fun workspaceCapturesAreAuthorizedBeforeEffectsAndDurableAfterReturn() = runTest {
        val store=JournalStore();val ws=ObservedWorkspace()
        val gate=object : ToolAuthorization {
            override suspend fun authorize(descriptor: ToolDescriptor): Boolean { assertEquals(0,ws.snapshots);return true }
        }
        val agent=ToolAgentRuntime(provider {
            val observations=store.events.mapNotNull { it.workspace }
            assertEquals(2,observations.size)
            assertEquals(WorkspaceChangeKind.CREATED,compareWorkspaceSnapshots(observations.first(),observations.last()).changes.single().kind)
        },tools(setOf("workspace")) {
            assertEquals(1,ws.snapshots)
            assertTrue(store.events.single { it.kind==ExecutionKind.WORKSPACE_BASELINE }.workspace!!.files.isEmpty())
            ws.written=true;"ok"
        })
        DefaultChatService(store,agent,EventBus(),workspaces=ws).send("session","write",ModelConfig(),gate).toList()
        assertEquals(2,ws.snapshots)
    }
    @Test fun deniedAndUnrelatedToolsNeverReadWorkspaceFiles() = runTest {
        for(denied in listOf(true,false)) {
            val store=JournalStore();val ws=ObservedWorkspace();var effects=0
            val agent=ToolAgentRuntime(provider(),tools(if(denied) setOf("workspace") else emptySet()) { effects++;"ok" })
            val deny=object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor)=false }
            DefaultChatService(store,agent,EventBus(),workspaces=ws).send("session","run",ModelConfig(),if(denied) deny else allow).toList()
            assertEquals(0,ws.snapshots);assertTrue(store.events.none { it.workspace!=null })
            assertEquals(if(denied) 0 else 1,effects)
        }
    }
    @Test fun failedBaselinePersistencePreventsWorkspaceEffects() = runTest {
        val store=JournalStore().apply { reject=ExecutionKind.WORKSPACE_BASELINE };val ws=ObservedWorkspace();var effects=0
        val agent=ToolAgentRuntime(provider(),tools(setOf("workspace")) { effects++;"ok" })
        assertFailsWith<ExecutionJournalException> {
            DefaultChatService(store,agent,EventBus(),workspaces=ws).send("session","write",ModelConfig(),allow).toList()
        }
        assertEquals(0,effects);assertEquals(1,ws.snapshots)
        assertEquals(ExecutionStatus.FAILED,store.run!!.status)
        assertTrue(store.events.none { it.kind==ExecutionKind.TOOL_STARTED || it.workspace!=null })
    }
    @Test fun workspaceJournalRedactsPreviewAndOmitsSecretPathsWithoutChangingSourceSnapshot() = runTest {
        val secret="private-model-key"
        val file=FileObservation(FileObservationKind.TEXT,12,"a".repeat(64),"Bearer upstream-key $secret")
        val source=mapOf("$secret.txt" to file,"public.txt" to file)
        val ws=ObservedWorkspace().apply { supplied=source };val store=JournalStore()
        DefaultChatService(store,ToolAgentRuntime(provider(),tools(setOf("workspace")) { "ok" }),EventBus(),workspaces=ws)
            .send("session","run",ModelConfig(apiKey=secret),allow).toList()
        val snapshots=store.events.mapNotNull { it.workspace }
        assertEquals(2,snapshots.size)
        snapshots.forEach { snapshot ->
            assertFalse(snapshot.complete);assertEquals(setOf("public.txt"),snapshot.files.keys)
            assertEquals(file.sha256,snapshot.files.getValue("public.txt").sha256)
            assertFalse(snapshot.files.getValue("public.txt").excerpt!!.contains(secret))
            assertFalse(snapshot.files.getValue("public.txt").excerpt!!.contains("upstream-key"))
        }
        assertEquals(file,source.getValue("$secret.txt"));assertEquals(file,source.getValue("public.txt"))
    }
    @Test fun immutableFullCopiesCannotBypassJournalCredentialFiltering() = runTest {
        val secret="private-model-key"
        val sensitive="ordinary code\n".repeat(200)+"Bearer upstream-key $secret"
        val clean="safe code🙂\n".repeat(200)
        fun full(text:String):FileObservation {
            val b=text.toByteArray(Charsets.UTF_8)
            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
            return FileObservation(FileObservationKind.TEXT,b.size.toLong(),hash,"ordinary preview",true,text,hash)
        }
        val original=mapOf("sensitive.txt" to full(sensitive),"clean.txt" to full(clean))
        val ws=ObservedWorkspace().apply { supplied=original };val store=JournalStore()
        DefaultChatService(store,ToolAgentRuntime(provider(),tools(setOf("workspace")) { "ok" }),EventBus(),workspaces=ws)
            .send("session","run",ModelConfig(apiKey=secret),allow).toList()
        val snapshots=store.events.mapNotNull { it.workspace };assertEquals(2,snapshots.size)
        snapshots.forEach { snapshot ->
            val withheld=snapshot.files.getValue("sensitive.txt")
            assertNull(withheld.captureText);assertNull(withheld.captureSha256)
            assertEquals(original.getValue("sensitive.txt").sha256,withheld.sha256)
            assertEquals(clean,snapshot.files.getValue("clean.txt").captureText)
            assertEquals(original.getValue("clean.txt").sha256,snapshot.files.getValue("clean.txt").captureSha256)
        }
        assertFalse(store.events.toString().contains(secret));assertFalse(store.events.toString().contains("upstream-key"))
        assertEquals(sensitive,original.getValue("sensitive.txt").captureText)
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
        val fragment=requireNotNull(recorded.result)
        assertTrue(recorded.truncated);assertTrue(fragment.toByteArray(Charsets.UTF_8).size<=64*1024)
        assertFalse(fragment.contains(secret));assertFalse(fragment.contains("upstream-secret"))
        assertFalse(fragment.contains('\uFFFD'))
    }
    @Test fun denialIsRecordedWithoutDispatchingOrInventingStartedState() = runTest {
        val store=JournalStore();var effects=0
        DefaultChatService(store,ToolAgentRuntime(provider(),tools { effects++;"not allowed" }),EventBus())
            .send("session","write",ModelConfig()).toList()
        assertEquals(0,effects);assertFalse(store.events.any { it.kind==ExecutionKind.TOOL_STARTED })
        assertEquals(ToolStage.DENIED,store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }.stage)
        assertEquals(ExecutionStatus.COMPLETE,store.run!!.status)
    }
    @Test fun escapedCredentialFieldsAreFullyRedactedFromJournalButExecutionKeepsOriginalArguments() = runTest {
        val store=JournalStore()
        val payload="""{"token":"upstream\"tail\\path","api_key":"another-secret","description":"public"}"""
        val provider=object : ModelProvider {
            override val id="fixture"
            var requests=0
            override fun stream(request: ModelRequest)=flow {
                if(requests++==0) emit(ModelChunk.ToolCalls(listOf(call.copy(argumentsJson=payload))))
                else { assertEquals(payload,request.messages.last { it.role==Role.TOOL }.content);emit(ModelChunk.Text("done")) }
                emit(ModelChunk.Done)
            }
        }
        val registry=ToolRegistry(EventBus()).apply { register(object : SolvitTool {
            override val descriptor=ToolDescriptor("write","write","{}",pluginId="files")
            override suspend fun execute(argumentsJson: String): String { assertEquals(payload,argumentsJson);return payload }
        }) }
        DefaultChatService(store,ToolAgentRuntime(provider,registry),EventBus()).send("session","run",ModelConfig(),allow).toList()
        val arguments=store.events.single { it.kind==ExecutionKind.TOOL_PROPOSED }.call!!.argumentsJson
        val result=requireNotNull(store.events.single { it.kind==ExecutionKind.TOOL_FINISHED }.result)
        for(fragment in listOf(arguments,result)) {
            assertTrue(fragment.contains("public"));assertTrue(fragment.contains("[已隐藏]"))
            for(secret in listOf("upstream","tail","path","another-secret")) assertFalse(fragment.contains(secret))
        }
    }
}
