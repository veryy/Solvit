package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContextPreparationTest {
    private val policy = ContextBudgetPolicy(8_192, 1_024, 512)
    private class Store(val rows: MutableList<ChatMessage>) : ChatStore {
        var saved: ContextSummary? = null
        var reject = false
        override fun sessions() = flowOf(emptyList<ChatSession>())
        override fun messages(sessionId: String) = flowOf(rows.toList())
        override suspend fun createSession() = ChatSession("s", "test", 0)
        override suspend fun history(sessionId: String) = rows.toList()
        override suspend fun append(message: ChatMessage) { rows += message }
        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun contextSummary(sessionId: String) = saved
        override suspend fun saveContextSummary(sessionId: String, expected: ContextSummary?, replacement: ContextSummary): Boolean {
            if (reject || saved != expected || contextPrefixDigest(rows.take(replacement.messageCount)) != replacement.prefixDigest) return false
            saved = replacement; return true
        }
    }
    private fun history(turns: Int = 2): MutableList<ChatMessage> = buildList {
        repeat(turns) { i -> add(ChatMessage("u$i", "s", Role.USER, "old task $i")); add(ChatMessage("a$i", "s", Role.ASSISTANT, "x".repeat(2_500))) }
        add(ChatMessage("current", "s", Role.USER, "current task " + "q".repeat(2_000)))
    }.toMutableList()
    private fun request(rows: List<ChatMessage>, policy: ContextBudgetPolicy = this.policy) = ModelRequest(
        ModelConfig(model = "fixture", contextBudget = policy),
        listOf(ModelMessage(Role.SYSTEM, "You are a programmer; preserve this identity")) + completeContext(rows, true), sessionId = "s")

    @Test fun summarizesAutomaticallyPersistsAndReusesAcrossServiceRestartWithoutDeletingHistory() = runTest {
        val rows = history(); val original = rows.toList(); val store = Store(rows); var calls = 0
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                calls++; assertTrue(request.tools.isEmpty()); assertNull(request.agentRole)
                assertTrue(request.messages.first().content.contains("历史摘要"))
                return flowOf(ModelChunk.Text("Old tasks completed. Keep user preference and remaining work."), ModelChunk.Done)
            }
        }
        val service = DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner())
        val prepared = service.prepare(request(rows))
        assertTrue(assertNotNull(prepared.contextMeasurement).fits)
        assertEquals(original, rows); assertNotNull(store.saved)
        assertEquals("You are a programmer; preserve this identity", prepared.messages.first().content)
        assertEquals(rows.last().content, prepared.messages.last().content)
        assertEquals(1, calls)
        val restarted = DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner())
        val next = restarted.prepare(request(rows))
        assertEquals(prepared.messages, next.messages); assertEquals(1, calls)
    }
    @Test fun batchesOversizedHistoryWithinEachRequestBudgetAndKeepsOriginalRows() = runTest {
        val rows = history(8); val original = rows.toList(); val store = Store(rows); var calls = 0
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                calls++; assertTrue(Utf8ContextTokenMeter().measure(request, request.config.contextBudget).fits)
                return flowOf(ModelChunk.Text("Completed older tasks; unfinished task remains."), ModelChunk.Done)
            }
        }
        val prepared = DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner()).prepare(request(rows))
        assertTrue(calls > 1); assertTrue(calls <= 8)
        assertTrue(assertNotNull(prepared.contextMeasurement).fits); assertEquals(original, rows)
    }
    @Test fun incompleteOversizedOrRacedSummaryLeavesPreviousCheckpointAndHistory() = runTest {
        for (mode in listOf("incomplete", "oversized", "race")) {
            val rows = history(); val original = rows.toList(); val store = Store(rows); store.reject = mode == "race"
            val provider = object : ModelProvider {
                override val id = "fixture"
                override fun stream(request: ModelRequest): Flow<ModelChunk> = when (mode) {
                    "incomplete" -> flowOf(ModelChunk.Text("partial"))
                    "oversized" -> flowOf(ModelChunk.Text("x".repeat(33 * 1024)), ModelChunk.Done)
                    else -> flowOf(ModelChunk.Text("completed"), ModelChunk.Done)
                }
            }
            assertFailsWith<ModelException> { DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner()).prepare(request(rows)) }
            assertNull(store.saved); assertEquals(original, rows)
        }
    }
    @Test fun cancelDuringSummaryDoesNotCommitOrLoseHistory() = runTest {
        val rows = history(); val original = rows.toList(); val store = Store(rows); val entered = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest) = flow<ModelChunk> { entered.complete(Unit); awaitCancellation() }
        }
        val service = DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner())
        val job = launch { service.prepare(request(rows)) }
        entered.await(); assertEquals(1, service.progress.value?.batch)
        job.cancelAndJoin(); assertNull(store.saved); assertEquals(original, rows); assertNull(service.progress.value)
    }
    @Test fun currentToolOutputIsClippedOnlyInProjectionAndToolDefinitionsCannotBeSilentlyRemoved() = runTest {
        val rows = mutableListOf(ChatMessage("u", "s", Role.USER, "run this")); val store = Store(rows)
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest) = error("no summarization expected")
        }
        val call = ToolCall("c", "run", "{}")
        val result = "HEAD" + "中🚀".repeat(5_000) + "TAIL"
        val input = request(rows).copy(messages = request(rows).messages + ModelMessage(Role.ASSISTANT, "", listOf(call)) + ModelMessage(Role.TOOL, result, toolCallId = "c"))
        val service = DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner())
        val small = input.copy(messages = input.messages.dropLast(1) + input.messages.last().copy(content = "complete output"))
        assertEquals("complete output", service.prepare(small).messages.last().content)
        val prepared = service.prepare(input)
        assertTrue(prepared.messages.last().content.startsWith("HEAD")); assertTrue(prepared.messages.last().content.endsWith("TAIL"))
        assertTrue(prepared.messages.last().content.contains("截断")); assertFalse(prepared.messages.last().content.contains('\uFFFD'))
        assertEquals(result, input.messages.last().content)
        assertFailsWith<ModelException> { service.prepare(request(rows).copy(tools = listOf(ToolDescriptor("huge", "x".repeat(10_000), "{}")))) }
    }
    @Test fun summaryDeadlineProducesVisibleFailureInsteadOfLookingLikeUserCancellation() = runTest {
        val rows = history(); val original = rows.toList(); val store = Store(rows)
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest) = flow<ModelChunk> { awaitCancellation() }
        }
        val service = DefaultContextPreparation(provider, store, BalancedContextBudgetPlanner())
        var failure: ModelException? = null
        val job = launch { try { service.prepare(request(rows)) } catch (e: ModelException) { failure = e } }
        runCurrent(); advanceTimeBy(60_001); job.join()
        assertTrue(assertNotNull(failure).message!!.contains("60 秒"))
        assertNull(store.saved); assertEquals(original, rows); assertNull(service.progress.value)
    }
}
