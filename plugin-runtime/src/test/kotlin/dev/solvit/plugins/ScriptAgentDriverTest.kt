package dev.solvit.plugins

import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class ScriptAgentDriverTest {
    @Test fun driverPreservesHostOwnedNativeTranscriptAndRefreshesSeparateReferences() = runBlocking {
        val native = ProviderTranscript("test", "fixture", "[{\"signature\":\"opaque-signature\"}]")
        val hooks = ContextHookRegistry()
        var reference = "before"
        hooks.register("test.reference", ContextHook { reference })
        var round = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun separatesRuntimeContext(config: ModelConfig) = true
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                assertFalse(request.messages.single { it.role == Role.SYSTEM }.content.contains(reference))
                assertEquals(listOf("插件上下文（test.reference）：\n$reference"), request.runtimeContext)
                return if (round++ == 0) flowOf(ModelChunk.NativeTranscript(native),
                    ModelChunk.ToolCalls(listOf(ToolCall("one", "probe", "{}"))), ModelChunk.Done)
                else {
                    assertSame(native, request.messages.last { it.role == Role.ASSISTANT }.providerTranscript)
                    flowOf(ModelChunk.Text("answer"), ModelChunk.Done)
                }
            }
        }
        val tools = ToolRegistry(EventBus()).apply {
            register(object : SolvitTool {
                override val descriptor = ToolDescriptor("probe", "Probe", "{}")
                override suspend fun execute(argumentsJson: String): String { reference = "after"; return "changed" }
            })
        }
        ToolAgentRuntime(provider, tools, hooks = hooks, driver = ScriptAgentDriver(source())).run(ModelRequest(ModelConfig(),
            listOf(ModelMessage(Role.SYSTEM, "role identity"), ModelMessage(Role.USER, "update")),
            authorization = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true })).toList()
        assertEquals(2, round)
    }
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
