package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PostTurnWorkerTest {
    private val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
    private val config = ModelConfig(providerId = "fixture", model = "model", apiKey = "fixture-private-key")
    private fun turn() = CompletedChatTurn("a", "s", "u", "a", null, null, null, "primary", "fixture", "model", 1, listOf(target))
    private class Store(turn: CompletedChatTurn) : ChatStore {
        override val supportsCompletedTurns = true
        var job: PostTurnJob? = PostTurnJob("job", turn, turn.targets.single(), PostTurnJobStatus.WAITING, 0, 1, null)
        val rows = mutableListOf(ChatMessage("u", "s", Role.USER, "I use Emacs"), ChatMessage("a", "s", Role.ASSISTANT, "Understood"))
        override fun sessions() = flowOf(listOf(ChatSession("s", "test", 0)))
        override fun messages(sessionId: String) = flowOf(rows.toList())
        override suspend fun createSession() = ChatSession("s", "test", 0)
        override suspend fun history(sessionId: String) = rows.toList()
        override suspend fun append(message: ChatMessage) { rows += message }
        override suspend fun deleteSession(sessionId: String) { rows.clear(); job = null }
        override suspend fun postTurnJob(id: String) = job?.takeIf { it.id == id }
        override suspend fun postTurnJobs(statuses: Set<PostTurnJobStatus>, limit: Int) = listOfNotNull(job).filter { it.status in statuses }.take(limit)
        override suspend fun claimPostTurnJob(id: String, expectedAttempt: Int, now: Long): Boolean {
            val old = job ?: return false
            if (old.id != id || old.status != PostTurnJobStatus.WAITING || old.attempt != expectedAttempt) return false
            job = old.copy(status = PostTurnJobStatus.RUNNING, attempt = expectedAttempt + 1); return true
        }
        override suspend fun finishPostTurnJob(id: String, attempt: Int, status: PostTurnJobStatus, failure: PostTurnFailure?, now: Long): Boolean {
            val old = job ?: return false
            if (old.id != id || old.status != PostTurnJobStatus.RUNNING || old.attempt != attempt) return false
            job = old.copy(status = status, failure = failure); return true
        }
    }
    private fun hook(enabled: () -> Boolean = { true }, process: suspend (PostTurnInput, PostTurnModel) -> Unit) = object : PostTurnHook {
        override val target = this@PostTurnWorkerTest.target
        override suspend fun enabled() = enabled()
        override suspend fun process(input: PostTurnInput, model: PostTurnModel) = process(input, model)
    }
    private fun provider(block: (ModelRequest) -> Flow<ModelChunk>) = object : ModelProvider {
        override val id = "fixture"
        override fun stream(request: ModelRequest) = block(request)
    }

    @Test fun executesOnlyCapturedSourceThroughBoundedCredentialFreePluginModelContract() = runTest {
        val store = Store(turn()); val registry = PostTurnHookRegistry(); var calls = 0; var committed = ""
        registry.register(hook { input, model ->
            assertEquals("I use Emacs", input.userText); assertEquals("Understood", input.assistantText)
            val result = model.complete("Extract explicit preferences", input.userText)
            currentCoroutineContext()[PostTurnExecutionContext]!!.ensureAuthorized()
            committed = result
        })
        val model = provider { request ->
            calls++
            assertTrue(request.tools.isEmpty()); assertNull(request.agentRole); assertNull(request.conversation)
            assertEquals("1024", request.config.providerOptions["max_tokens"])
            assertTrue(request.contextMeasurement!!.fits)
            flowOf(ModelChunk.Text("Emacs " + config.apiKey), ModelChunk.Done)
        }
        val worker = PostTurnWorker(store, registry, model, { config }, { null }, backgroundScope)
        assertTrue(worker.runNext()); assertFalse(worker.runNext())
        assertEquals(1, calls); assertEquals("Emacs <redacted>", committed)
        assertEquals(PostTurnJobStatus.SUCCEEDED, store.job!!.status)
        assertNull(worker.state.value.activeJobId); assertNull(worker.state.value.failure)
        assertEquals(2, store.rows.size)
    }

    @Test fun revokedOrChangedScopeIsSkippedAndMissingConfigurationFailsWithoutInference() = runTest {
        for (case in listOf("disabled", "replacement", "role", "configuration")) {
            val store = Store(turn()); val registry = PostTurnHookRegistry(); var calls = 0
            val unregister = registry.register(hook(enabled = { case != "disabled" }) { _, _ -> error("Should not run") })
            if (case == "replacement") unregister()
            val model = provider { calls++; error("Should not call model") }
            val worker = PostTurnWorker(store, registry, model, { if (case == "configuration") null else config },
                { if (case == "role") AgentRole("changed", "Changed") else null }, backgroundScope)
            assertTrue(worker.runNext()); assertEquals(0, calls)
            assertEquals(if (case == "configuration") PostTurnJobStatus.FAILED else PostTurnJobStatus.SKIPPED, store.job!!.status)
            if (case == "configuration") assertEquals(PostTurnFailure.CONFIGURATION, store.job!!.failure)
        }
    }

    @Test fun deadlineIsDistinctFromCancellationAndNoFailureAutomaticallyRetries() = runTest {
        val store = Store(turn()); val registry = PostTurnHookRegistry()
        registry.register(hook { input, model -> model.complete("Extract", input.userText) })
        val worker = PostTurnWorker(store, registry, provider { flow { awaitCancellation() } }, { config }, { null }, backgroundScope, deadlineMs = 50)
        assertTrue(worker.runNext())
        assertEquals(PostTurnFailure.DEADLINE, store.job!!.failure)
        assertEquals(PostTurnJobStatus.FAILED, store.job!!.status)
        assertFalse(worker.runNext()); assertNull(worker.state.value.activeJobId)
    }

    @Test fun cancellationDoesNotBlockForegroundChatOrAllowAnotherConsumer() = runTest {
        val store = Store(turn()); val registry = PostTurnHookRegistry(); val entered = CompletableDeferred<Unit>()
        registry.register(hook { input, model -> model.complete("Extract", input.userText) })
        val worker = PostTurnWorker(store, registry, provider { flow { entered.complete(Unit); awaitCancellation() } }, { config }, { null }, backgroundScope)
        val running = launch { worker.runNext() }
        entered.await(); assertFalse(worker.runNext())
        val agent = object : AgentRuntime { override fun run(request: ModelRequest) = flowOf(ModelChunk.Text("foreground answer"), ModelChunk.Done) }
        DefaultChatService(store, agent, EventBus()).send("s", "another question", config).toList()
        assertEquals("foreground answer", store.rows.last().content)
        worker.cancelCurrent(); running.join()
        assertEquals(PostTurnFailure.INTERRUPTED, store.job!!.failure)
        assertFalse(worker.runNext()); assertNull(worker.state.value.activeJobId)
    }

    @Test fun revocationWhileInferenceRunsPreventsTheFollowingWriteCheckpoint() = runTest {
        val store = Store(turn()); val registry = PostTurnHookRegistry(); var enabled = true; var commits = 0
        registry.register(hook(enabled = { enabled }) { input, model ->
            model.complete("Extract", input.userText)
            currentCoroutineContext()[PostTurnExecutionContext]!!.ensureAuthorized()
            commits++
        })
        val model = provider { flow { enabled = false; emit(ModelChunk.Text("preference")); emit(ModelChunk.Done) } }
        val worker = PostTurnWorker(store, registry, model, { config }, { null }, backgroundScope)
        assertTrue(worker.runNext()); assertEquals(0, commits)
        assertEquals(PostTurnJobStatus.SKIPPED, store.job!!.status)
    }

    @Test fun rejectsUnfinishedToolsOversizeRepeatedCallsAndInputsOutsideBudget() = runTest {
        for (case in listOf("unfinished", "tools", "oversize", "repeat", "budget")) {
            val store = Store(turn()); val registry = PostTurnHookRegistry(); var calls = 0
            registry.register(hook { _, model ->
                model.complete("Extract", if (case == "budget") "x".repeat(10_000) else "I use Emacs")
                if (case == "repeat") model.complete("Extract again", "more")
            })
            val model = provider {
                calls++
                when (case) {
                    "unfinished" -> flowOf(ModelChunk.Text("partial"))
                    "tools" -> flowOf(ModelChunk.ToolCalls(listOf(ToolCall("c", "shell", "{}"))), ModelChunk.Done)
                    "oversize" -> flowOf(ModelChunk.Text("文".repeat(12_000)), ModelChunk.Done)
                    else -> flowOf(ModelChunk.Text("valid"), ModelChunk.Done)
                }
            }
            val selected = ModelConfig(providerId = "fixture", model = "model", contextBudget = ContextBudgetPolicy(4_096, 512, 256))
            val worker = PostTurnWorker(store, registry, model, { selected }, { null }, backgroundScope)
            assertTrue(worker.runNext()); assertEquals(PostTurnJobStatus.FAILED, store.job!!.status)
            assertEquals(if (case == "budget") PostTurnFailure.INPUT_TOO_LARGE else PostTurnFailure.INVALID_OUTPUT, store.job!!.failure)
            assertEquals(if (case == "budget") 0 else 1, calls)
        }
    }
}
