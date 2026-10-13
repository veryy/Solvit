package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolDeadlineTest {
    private val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
    private fun registry(execute: suspend () -> String) = ToolRegistry(EventBus()).apply {
        register(object : SolvitTool {
            override val descriptor = ToolDescriptor("slow", "Slow", "{}")
            override suspend fun execute(argumentsJson: String) = execute()
        })
    }
    @Test fun ownDeadlineReturnsSafeFailureAndLeavesCallerUsable() = runTest {
        var cleaned = false
        val tools = registry { try { awaitCancellation() } finally { cleaned = true } }
        val failure = assertFailsWith<ToolExecutionException> { tools.execute("slow", "{}", allow) }
        // Assert the public error contract without depending on a new enum in the red commit.
        assertEquals("TIMEOUT", failure.code.name)
        assertTrue(cleaned)
        currentCoroutineContext().ensureActive()
        tools.register(object : SolvitTool {
            override val descriptor = ToolDescriptor("fast", "Fast", "{}")
            override suspend fun execute(argumentsJson: String) = "usable"
        })
        assertEquals("usable", tools.execute("fast", "{}", allow))
    }
    @Test fun enclosingDeadlineRemainsCancellationAndCleansTool() = runTest {
        var cleaned = false
        val tools = registry { try { awaitCancellation() } finally { cleaned = true } }
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000) { tools.execute("slow", "{}", allow) }
        }
        assertTrue(cleaned)
    }
}
