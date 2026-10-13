package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PluginReferenceTest {
    @Test fun currentReferenceIsBudgetedAndCannotBeSummarizedAsOldHistory() {
        val canonical = listOf(
            ModelMessage(Role.SYSTEM, "identity"),
            ModelMessage(Role.USER, "old question".repeat(50), sourceMessageId = "old-user"),
            ModelMessage(Role.ASSISTANT, "old answer".repeat(50), sourceMessageId = "old-answer"),
            ModelMessage(Role.USER, "current", sourceMessageId = "current-user"),
        )
        val projected = attachPluginReference(canonical, listOf("reference".repeat(100)))
        val config = ModelConfig(contextBudget = ContextBudgetPolicy(2048, 256, 128))
        val plan = BalancedContextBudgetPlanner().plan(ModelRequest(config, projected), config.contextBudget)
        assertFalse(plan.measurement.fits)
        val region = assertNotNull(plan.region)
        assertEquals(3, region.endExclusive)
        assertEquals("current-user", projected.last().sourceMessageId)
        assertTrue(projected.last().content.endsWith("current"))
        assertEquals("current", canonical.last().content)
        assertEquals(canonical, attachPluginReference(canonical, emptyList()))
    }

    @Test fun referenceWithoutAUserTurnStillUsesDataRoleAndPreservesSystem() {
        val instructions = ModelMessage(Role.SYSTEM, "trusted")
        val projected = attachPluginReference(listOf(instructions), listOf("data"))
        assertEquals(listOf(Role.USER, Role.SYSTEM), projected.map { it.role })
        assertEquals(instructions, projected.last())
    }

    @Test fun nativeProviderKeepsCanonicalPrefixAndReceivesSeparateContextPerStep() = runTest {
        var context = "before"
        val hooks = ContextHookRegistry().apply { register("test.memory", ContextHook { context }) }
        val call = ToolCall("c", "save", "{}")
        val tools = ToolRegistry(EventBus()).apply {
            register(object : SolvitTool {
                override val descriptor = ToolDescriptor("save", "Save", "{}")
                override suspend fun execute(argumentsJson: String): String { context = "after"; return "saved" }
            })
        }
        val requests = mutableListOf<ModelRequest>()
        val native = object : ModelProvider {
            override val id = "native-fixture"
            override fun separatesRuntimeContext(config: ModelConfig) = true
            override fun stream(request: ModelRequest): Flow<ModelChunk> {
                requests += request
                return if (requests.size == 1) flowOf(ModelChunk.ToolCalls(listOf(call)), ModelChunk.Done)
                    else flowOf(ModelChunk.Text("answer"), ModelChunk.Done)
            }
        }
        val canonical = listOf(ModelMessage(Role.SYSTEM, "role"), ModelMessage(Role.USER, "save this"))
        ToolAgentRuntime(native, tools, hooks = hooks).run(ModelRequest(ModelConfig(), canonical,
            authorization = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true })).toList()
        assertEquals(2, requests.size)
        for (request in requests) assertEquals(canonical.last(), request.messages.single { it.role == Role.USER })
        assertTrue(requests[0].runtimeContext.single().endsWith("before"))
        assertTrue(requests[1].runtimeContext.single().endsWith("after"))
        assertEquals(listOf(Role.ASSISTANT, Role.TOOL), requests.last().messages.takeLast(2).map { it.role })
    }
}
