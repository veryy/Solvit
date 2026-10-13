package dev.solvit.core

import kotlinx.coroutines.*
import kotlin.test.*

class AgentDriversTest {
    @Test fun optInIsRequiredAndConflictingStrategiesNeverExecute() = runBlocking {
        val ran = mutableListOf<String>()
        val registry = AgentDriverRegistry(AgentDriver { _, _ -> ran += "default" })
        var selected = false
        registry.register("first", AgentDriver { _, _ -> ran += "first" }) { selected }
        val host = AgentStepHost { _, _ -> 0 }
        registry.run(8, host)
        selected = true
        registry.run(8, host)
        val unregister = registry.register("second", AgentDriver { _, _ -> ran += "second" }) { true }
        assertFailsWith<ModelException> { registry.run(8, host) }
        assertEquals(listOf("default", "first"), ran)
        unregister()
        registry.run(8, host)
        assertEquals("first", ran.last())
    }

    @Test fun revocationCancelsAndJoinsOnlyOwnedTurnsBeforeReplacement() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        var defaults = 0
        val registry = AgentDriverRegistry(AgentDriver { _, _ -> defaults++ })
        val unregister = registry.register("strategy", AgentDriver { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() }
            finally { cleaned.complete(Unit) }
        }) { true }
        val turn = launch { registry.run(8, AgentStepHost { _, _ -> 0 }) }
        withTimeout(5_000) { entered.await(); unregister() }
        assertTrue(turn.isCancelled)
        assertTrue(cleaned.isCompleted)
        assertTrue(isActive)
        registry.register("strategy", AgentDriver { _, _ -> }) { true }
        unregister() // A stale disposer cannot remove the new registration.
        registry.run(8, AgentStepHost { _, _ -> 0 })
        assertEquals(0, defaults)
    }
}
