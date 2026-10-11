package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ContextHooksTest {
    @Test fun roleFiltersHooksAndUnloadingRemovesContext() = runTest {
        val hooks = ContextHookRegistry()
        val unregister = hooks.register("test.skills", ContextHook { input -> "skills for ${input.userText}" })
        hooks.register("test.memory", ContextHook { "memory" })
        val role = AgentRole("coding", "Coding", pluginIds = setOf("test.skills"))
        assertEquals(listOf("插件上下文（test.skills）：\nskills for Kotlin"), hooks.prepare(ContextInput(role, "Kotlin")))
        unregister()
        assertTrue(hooks.prepare(ContextInput(role, "Kotlin")).isEmpty())
        assertEquals(1, hooks.prepare(ContextInput(null, "Kotlin")).size)
    }
    @Test fun cancellationPropagatesAndOversizedContextFails() = runTest {
        val hooks = ContextHookRegistry()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val unregister = hooks.register("test.wait", ContextHook {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        })
        val job = launch { hooks.prepare(ContextInput(null, "")) }
        started.await(); job.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
        unregister()
        hooks.register("test.large", ContextHook { "中".repeat(12_000) })
        assertFailsWith<IllegalStateException> { hooks.prepare(ContextInput(null, "")) }
    }
}
