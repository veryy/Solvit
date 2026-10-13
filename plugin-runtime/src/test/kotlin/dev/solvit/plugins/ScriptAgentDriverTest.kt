package dev.solvit.plugins

import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class ScriptAgentDriverTest {
    private fun source() = checkNotNull(javaClass.classLoader!!.getResourceAsStream("agent/chat-driver.js")).bufferedReader().use { it.readText() }
    @Test fun bundledJavaScriptSchedulesModelToolsSettlementAndFinish() = runBlocking {
        val operations = mutableListOf<Pair<String, Int>>()
        ScriptAgentDriver(source()).run(8, AgentStepHost { op, index ->
            operations += op to index
            if (op == "model" && index == 0) 2 else 0
        })
        assertEquals(listOf("model" to 0, "tool" to 0, "tool" to 1, "settle" to 0, "model" to 1, "finish" to 1), operations)
    }
    @Test fun actualQuickJsDriverPreservesLiveTextAndUsesAuthorizedToolResult() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var rounds = 0
        var effects = 0
        val calls = listOf(ToolCall("one", "probe", "{}"))
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> = flow {
                if (rounds++ == 0) {
                    emit(ModelChunk.ToolCalls(calls)); emit(ModelChunk.Done)
                } else {
                    assertEquals("actual-result", request.messages.last { it.role == Role.TOOL }.content)
                    emit(ModelChunk.Text("live")); first.complete(Unit)
                    finish.await(); emit(ModelChunk.Text(" final")); emit(ModelChunk.Done)
                }
            }
        }
        val tools = ToolRegistry(EventBus()).apply {
            register(object : SolvitTool {
                override val descriptor = ToolDescriptor("probe", "Probe", "{}")
                override suspend fun execute(argumentsJson: String): String { effects++; return "actual-result" }
            })
        }
        val chunks = mutableListOf<ModelChunk>()
        val turn = launch {
            ToolAgentRuntime(provider, tools, driver = ScriptAgentDriver(source())).run(ModelRequest(ModelConfig(),
                listOf(ModelMessage(Role.USER, "probe")), authorization = object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor) = true
                })).collect { chunks += it }
        }
        withTimeout(10_000) { first.await(); while (chunks.none { it is ModelChunk.Text }) yield() }
        assertTrue(turn.isActive)
        assertFalse(chunks.any { it == ModelChunk.Done })
        finish.complete(Unit)
        turn.join()
        assertEquals(1, effects); assertEquals(2, rounds)
        assertEquals(ModelChunk.Done, chunks.last())
    }
    @Test fun alteredJavaScriptCannotSkipModelAndDispatchATool() = runBlocking {
        var effects = 0
        val tools = ToolRegistry(EventBus()).apply {
            register(object : SolvitTool {
                override val descriptor = ToolDescriptor("probe", "Probe", "{}")
                override suspend fun execute(argumentsJson: String): String { effects++; return "wrong" }
            })
        }
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest) = flowOf(ModelChunk.Done)
        }
        val driver = ScriptAgentDriver("globalThis.solvitAgent={run:async()=>{await __solvitAgentCommand('tool',0)}}")
        assertFails { ToolAgentRuntime(provider, tools, driver = driver).run(ModelRequest(ModelConfig(), emptyList())).toList() }
        assertEquals(0, effects)
    }
    @Test fun userCancellationStopsAsyncNativeWorkAndClosesTheJsTurn() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val turn = launch {
            ScriptAgentDriver(source()).run(8, AgentStepHost { _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            })
        }
        withTimeout(10_000) { started.await() }
        withTimeout(10_000) { turn.cancelAndJoin() }
        assertTrue(turn.isCancelled); assertTrue(cleaned)
    }
}
