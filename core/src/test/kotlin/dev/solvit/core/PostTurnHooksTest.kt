package dev.solvit.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PostTurnHooksTest {
    @Test fun admissionRequiresOptInAndRoleBindingAndExactPackageSurvivesOnlyUntilUnload() = runTest {
        val registry = PostTurnHookRegistry()
        var enabled = false
        val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
        val hook = object : PostTurnHook {
            override val target = target
            override suspend fun enabled() = enabled
            override suspend fun process(input: PostTurnInput, model: PostTurnModel) = Unit
        }
        val unload = registry.register(hook)
        assertTrue(registry.admit(null).isEmpty())
        enabled = true
        assertTrue(registry.admit(AgentRole("empty", "Empty")).isEmpty())
        assertEquals(listOf(target), registry.admit(AgentRole("bound", "Bound", pluginIds = setOf(target.pluginId))))
        assertSame(hook, registry.resolve(target))
        assertNull(registry.resolve(target.copy(digest = "b".repeat(64))))
        unload()
        assertNull(registry.resolve(target))
        assertTrue(registry.admit(null).isEmpty())
    }
}
