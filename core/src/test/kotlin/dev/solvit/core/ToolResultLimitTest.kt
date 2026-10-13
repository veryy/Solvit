package dev.solvit.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ToolResultLimitTest {
    private val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
    private fun registry(value: String, cancelled: MutableList<String>) = ToolRegistry(EventBus()).apply {
        register(object : SolvitTool {
            override val descriptor = ToolDescriptor("large", "Large", "{}")
            override suspend fun execute(argumentsJson: String): String {
                val invocation = currentCoroutineContext()[ToolInvocationContext]!!
                invocation.own("first") { cancelled += "first" }
                invocation.own("second") { cancelled += "second" }
                return value
            }
        })
    }
    @Test fun oversizedResultIsRejectedBeforeInvocationResourcesAreReleased() = runTest {
        val cancelled = mutableListOf<String>()
        val failure = assertFailsWith<ToolExecutionException> {
            registry("PRIVATE_RESULT_SENTINEL" + "x".repeat(256 * 1024), cancelled).execute("large", "{}", allow)
        }
        assertEquals(ToolFailureCode.RESOURCE_LIMIT, failure.code)
        assertEquals(listOf("second", "first"), cancelled)
        assertFalse(failure.toString().contains("PRIVATE_RESULT_SENTINEL"))
    }
    @Test fun limitUsesUtf8BytesAndAcceptsExactBoundaryWithoutCancellingSuccess() = runTest {
        val cancelled = mutableListOf<String>()
        val boundary = "文".repeat(87_381) + "x"
        assertEquals(256 * 1024, boundary.toByteArray(Charsets.UTF_8).size)
        assertEquals(boundary, registry(boundary, cancelled).execute("large", "{}", allow))
        assertTrue(cancelled.isEmpty())
        assertFailsWith<ToolExecutionException> {
            registry(boundary + "x", cancelled).execute("large", "{}", allow)
        }
        assertEquals(listOf("second", "first"), cancelled)
    }
}
