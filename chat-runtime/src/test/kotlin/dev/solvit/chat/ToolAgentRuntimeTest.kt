package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolAgentRuntimeTest {
    @Test fun everyStepRefreshesHookAfterToolsWithoutAccumulatingOldContext() = runTest {
        val hooks = ContextHookRegistry()
        var memory = "before"
        hooks.register("test.memory", ContextHook { "memory=$memory" })
        var round = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                val system = request.messages.single { it.role == Role.SYSTEM }.content
                assertEquals(1, Regex("base identity").findAll(system).count())
                assertFalse(system.contains("memory="))
                val reference = request.messages.single { it.role == Role.USER }.content
                if (round++ == 0) {
                    assertTrue(reference.contains("memory=before"))
                    return flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
                }
                assertTrue(reference.contains("memory=after")); assertFalse(reference.contains("memory=before"))
                return flowOf(ModelChunk.Text("updated"), ModelChunk.Done)
            }
        }
        ToolAgentRuntime(provider, registry { memory = "after"; "saved" }, hooks = hooks).run(
            ModelRequest(ModelConfig(), listOf(ModelMessage(Role.SYSTEM, "base identity"), ModelMessage(Role.USER, "update memory")), authorization = allow)).toList()
        assertEquals(2, round)
    }

    @Test fun inspectionTracksActualFilteredToolsAndEveryPreparedRound() = runTest {
        val inspection = RequestInspectionService()
        var round = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                val info = inspection.latest.value!!
                assertEquals(request.tools.map { it.name }, info.tools)
                assertEquals(request.messages.size, info.messageCount)
                assertEquals("session", info.sessionId)
                return if (round++ == 0) flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
                    else flowOf(ModelChunk.Text("answer"), ModelChunk.Done)
            }
        }
        ToolAgentRuntime(provider, registry { "result" }, inspection = inspection)
            .run(ModelRequest(ModelConfig(apiKey = "PRIVATE"), listOf(ModelMessage(Role.USER, "time")),
                authorization = allow, sessionId = "session")).toList()
        assertEquals(2, round)
        assertFalse(inspection.latest.value.toString().contains("PRIVATE"))
    }
    private val call = ToolCall("call_1", "clock", "{}")
    private val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
    private fun registry(execute: suspend () -> String): ToolRegistry = ToolRegistry(EventBus()).apply {
        register(object : SolvitTool {
            override val descriptor = ToolDescriptor("clock", "Clock", "{}", requiresConfirmation = true)
            override suspend fun execute(argumentsJson: String) = execute()
        })
    }
    @Test fun feedsToolResultBackAndOnlyFinishesAfterFinalModelReply() = runTest {
        var requests = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                requests++
                assertEquals("clock", request.tools.single().name)
                return if (requests == 1) flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
                else {
                    assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), request.messages.filter { it.role != Role.SYSTEM }.map { it.role })
                    assertTrue(request.messages.any { it.role == Role.SYSTEM && it.content.contains("插件工具") })
                    assertEquals("UTC", request.messages.last().content)
                    assertEquals(call.id, request.messages.last().toolCallId)
                    flowOf(ModelChunk.Text("现在是 UTC"), ModelChunk.Done)
                }
            }
        }
        val chunks = ToolAgentRuntime(provider, registry { "UTC" }).run(
            ModelRequest(ModelConfig(), listOf(ModelMessage(Role.USER, "时间")), authorization = allow),
        ).toList()
        assertEquals(2, requests)
        assertEquals(1, chunks.count { it == ModelChunk.Done })
        assertEquals(listOf(ToolStage.CONFIRMING, ToolStage.RUNNING, ToolStage.SUCCEEDED), chunks.filterIsInstance<ModelChunk.ToolActivity>().map { it.stage })
        assertEquals(2, chunks.filterIsInstance<ModelChunk.Transcript>().size)
    }
    @Test fun deniedToolNeverExecutesButModelReceivesDenial() = runTest {
        var round = 0
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> = if (round++ == 0)
                flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
            else {
                assertTrue(request.messages.last().content.contains("拒绝"))
                flowOf(ModelChunk.Text("取消了"), ModelChunk.Done)
            }
        }
        val result = ToolAgentRuntime(provider, registry { fail("Denied tool must not execute") }).run(ModelRequest(ModelConfig(), emptyList())).toList()
        assertTrue(result.filterIsInstance<ModelChunk.ToolActivity>().any { it.stage == ToolStage.DENIED })
    }
    @Test fun cancellationWhileWaitingForApprovalNeverExecutesOrContinuesModel() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest) = flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
        }
        val gate = object : ToolAuthorization {
            override suspend fun authorize(descriptor: ToolDescriptor): Boolean { waiting.complete(Unit); awaitCancellation() }
        }
        val job = launch {
            ToolAgentRuntime(provider, registry { fail("Cancelled tool must not execute") }).run(
                ModelRequest(ModelConfig(), emptyList(), authorization = gate),
            ).collect()
        }
        waiting.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }
    @Test fun unknownToolsAndRoundLimitNeverExecute() = runTest {
        for (name in listOf("unknown", "clock")) {
            val provider = object : ModelProvider {
                override val id = "test"
                override fun stream(request: ModelRequest) = flowOf(ModelChunk.ToolCalls(listOf(call.copy(name = name))), ModelChunk.Done)
            }
            assertFailsWith<ModelException> {
                ToolAgentRuntime(provider, registry { fail("Must not execute") }, maxRounds = if (name == "clock") 1 else 8)
                    .run(ModelRequest(ModelConfig(), emptyList(), authorization = allow)).toList()
            }
        }
    }
    @Test fun orphanedToolGroupsAreExcludedFromFutureContext() {
        val user = ChatMessage("u", "s", Role.USER, "hello")
        val assistant = ChatMessage("a", "s", Role.ASSISTANT, "", toolCalls = listOf(call))
        val tool = ChatMessage("t", "s", Role.TOOL, "UTC", toolCallId = call.id)
        assertEquals(listOf(ModelMessage(Role.USER, "hello")), completeContext(listOf(user, assistant)))
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), completeContext(listOf(user, assistant, tool)).map { it.role })
    }

    @Test fun roleLimitsAdvertisedToolsAndContextAndRejectsUnboundCalls() = runTest {
        val hooks = ContextHookRegistry().apply {
            register("test.skills", ContextHook { "selected skill directory" })
            register("test.private", ContextHook { fail("Unbound hook must not run") })
        }
        val role = AgentRole("review", "Review", pluginIds = setOf("test.skills"))
        val provider = object : ModelProvider {
            override val id = "test"
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                assertTrue(request.tools.isEmpty())
                assertTrue(request.messages.first().content.contains("selected skill directory"))
                return flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
            }
        }
        val tools = registry { fail("Unbound tool must not execute") }
        val gate = object : ToolAuthorization {
            override suspend fun authorize(descriptor: ToolDescriptor): Boolean = fail("Role check must precede approval")
        }
        assertFailsWith<ModelException> {
            ToolAgentRuntime(provider, tools, hooks = hooks).run(ModelRequest(ModelConfig(), emptyList(), authorization = gate, agentRole = role)).toList()
        }
        assertFailsWith<ToolDeniedException> { tools.executeCall(call, gate, role) }
    }
}
