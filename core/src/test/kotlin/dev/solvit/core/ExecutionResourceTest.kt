package dev.solvit.core

import kotlinx.coroutines.*
import kotlin.test.*

class ExecutionResourceTest {
    @Test fun failedToolCancelsItsStartedResourcesButSuccessfulToolLeavesThemOwnedByAgent() = runBlocking<Unit> {
        var cancellations = 0
        val registry = ToolRegistry(EventBus())
        registry.register(object : SolvitTool {
            override val descriptor = ToolDescriptor("run", "Run", "{}")
            override suspend fun execute(argumentsJson: String): String {
                val invocation = currentCoroutineContext()[ToolInvocationContext]!!
                invocation.own("job") { cancellations++ }
                if (argumentsJson == "fail") error("Plugin failed after launching command")
                return "started"
            }
        })
        val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
        assertFailsWith<IllegalStateException> { registry.execute("run", "fail", allow) }
        assertEquals(1, cancellations)
        assertEquals("started", registry.execute("run", "success", allow))
        assertEquals(1, cancellations)
    }
    @Test fun stoppingAgentCleansAllResourcesEvenIfOneCleanupFails() = runBlocking<Unit> {
        val context = AgentExecutionContext()
        val cancelled = mutableListOf<String>()
        context.own("first") { cancelled += "first" }
        context.own("second") { cancelled += "second"; error("gone") }
        context.own("third") { cancelled += "third" }
        context.cancel(); context.cancel()
        assertEquals(listOf("third", "second", "first"), cancelled)
        assertFailsWith<IllegalStateException> { context.own("late") {} }
    }
    @Test fun sourceAndExecutionModeChangesInvalidateRememberedAuthorization() {
        val base = ToolDescriptor("run", "Run", "{}", pluginDigest = "original", executionMode = ExecutionMode.APP)
        assertNotEquals(toolPolicyKey(base), toolPolicyKey(base.copy(pluginDigest = "modified")))
        assertNotEquals(toolPolicyKey(base), toolPolicyKey(base.copy(executionMode = ExecutionMode.ROOT)))
    }
}
