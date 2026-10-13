package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ContextHooksTest {
    @Test fun failedOptionalHookDoesNotBlockOtherPluginData() = runTest {
        val hooks = ContextHookRegistry()
        hooks.register("test.failed", ContextHook { error("PRIVATE_SECRET_SENTINEL") })
        hooks.register("test.healthy", ContextHook { "healthy data" })
        assertEquals(listOf("插件上下文（test.healthy）：\nhealthy data"), hooks.prepare(ContextInput(null, "hello")))
    }

    @Test fun slowOptionalHooksShareOneDeadlineAndHealthyPluginStillRuns() = runTest {
        val hooks = ContextHookRegistry()
        repeat(8) { index -> hooks.register("test.slow$index", ContextHook { delay(20_000); "late" }) }
        // Register early enough to fit the bounded worker pool, behind two slow plugins.
        val separate = ContextHookRegistry()
        repeat(2) { index -> separate.register("test.slow$index", ContextHook { delay(20_000); "late" }) }
        separate.register("test.healthy", ContextHook { delay(500); "healthy data" })
        val start = testScheduler.currentTime
        assertEquals(listOf("插件上下文（test.healthy）：\nhealthy data"), separate.prepare(ContextInput(null, "hello")))
        assertTrue(testScheduler.currentTime - start <= 10_000)
        val next = testScheduler.currentTime
        assertTrue(hooks.prepare(ContextInput(null, "hello")).isEmpty())
        assertTrue(testScheduler.currentTime - next <= 10_000)
    }

    @Test fun unloadedHookCannotReturnStaleContextAfterItsSuspendedReadCompletes() = runTest {
        val hooks = ContextHookRegistry()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val unregister = hooks.register("test.removed", ContextHook {
            started.complete(Unit); finish.await(); "stale data"
        })
        val result = async { hooks.prepare(ContextInput(null, "hello")) }
        started.await(); unregister(); finish.complete(Unit)
        assertTrue(result.await().isEmpty())
    }
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
