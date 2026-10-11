package dev.solvit.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PluginManagerTest {
    interface Greeting { fun greet(): String }
    private val greeting = ServiceKey("example.greeting", Greeting::class)

    @Test fun dependenciesResolveAndUnloadRemovesRegistrations() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        val calls = mutableListOf<String>()
        val provider = object : SolvitPlugin {
            override val id = "example.provider"
            override val provides = setOf(greeting)
            override suspend fun enable(context: PluginContext) {
                context.own { calls += "released" }
                context.register(greeting, object : Greeting { override fun greet() = "hello" })
            }
        }
        val consumer = object : SolvitPlugin {
            override val id = "example.consumer"
            override val requires = setOf(greeting)
            override suspend fun enable(context: PluginContext) { calls += context.require(greeting).greet() }
        }
        manager.load(consumer)
        manager.load(provider)
        manager.enableAll()
        assertEquals(listOf("hello"), calls)
        assertFailsWith<IllegalStateException> { manager.disable(provider.id) }
        manager.unload(consumer.id)
        manager.unload(provider.id)
        assertFalse(manager.registry.contains(greeting))
        assertEquals(listOf("hello", "released"), calls)
        assertEquals(PluginState.UNLOADED, manager.states.value[provider.id])
    }

    @Test fun failingPluginReleasesEffectsAndRollsBackBatch() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        var releases = 0
        manager.load(object : SolvitPlugin {
            override val id = "good"
            override val provides = setOf(greeting)
            override suspend fun enable(context: PluginContext) {
                context.register(greeting, object : Greeting { override fun greet() = "hi" })
                context.own { releases++ }
            }
        })
        manager.load(object : SolvitPlugin {
            override val id = "broken"
            override val requires = setOf(greeting)
            override suspend fun enable(context: PluginContext) {
                context.own { releases++ }
                error("failed initialization")
            }
        })
        assertFailsWith<IllegalStateException> { manager.enableAll() }
        assertEquals(2, releases)
        assertFalse(manager.registry.contains(greeting))
        manager.shutdown()
    }

    @Test fun missingDependencyFailsBeforeActivation() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        manager.load(object : SolvitPlugin {
            override val id = "missing"
            override val requires = setOf(greeting)
            override suspend fun enable(context: PluginContext) { fail("Must not activate") }
        })
        assertFailsWith<IllegalStateException> { manager.enableAll() }
    }

    @Test fun pluginIdDependenciesOrderActivationAndProtectUnloading() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        val calls = mutableListOf<String>()
        fun plugin(name: String, dependencies: Set<String>) = object : SolvitPlugin {
            override val id = name
            override val dependencies = dependencies
            override suspend fun enable(context: PluginContext) {
                calls += "enable:$id"
                context.own { calls += "release:$id" }
            }
        }
        manager.load(plugin("consumer", setOf("provider")))
        manager.load(plugin("provider", emptySet()))
        manager.enableAll()
        assertEquals(listOf("enable:provider", "enable:consumer"), calls)
        assertFailsWith<IllegalStateException> { manager.disable("provider") }
        assertFailsWith<IllegalStateException> { manager.unload("provider") }
        manager.shutdown()
        assertEquals(listOf("enable:provider", "enable:consumer", "release:consumer", "release:provider"), calls)
    }

    @Test fun cyclicPluginIdsNeverActivateAndRollbackIndependentPlugins() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        var released = false
        manager.load(object : SolvitPlugin {
            override val id = "independent"
            override suspend fun enable(context: PluginContext) {
                context.own { released = true }
            }
        })
        for ((id, dependency) in listOf("a" to "b", "b" to "a")) {
            manager.load(object : SolvitPlugin {
                override val id = id
                override val dependencies = setOf(dependency)
                override suspend fun enable(context: PluginContext) { fail("Cyclic plugins must not activate") }
            })
        }
        assertFailsWith<IllegalStateException> { manager.enableAll() }
        assertTrue(released)
        assertEquals(PluginState.DISABLED, manager.states.value["independent"])
        assertEquals(PluginState.LOADED, manager.states.value["a"])
        manager.shutdown()
    }

    @Test fun reEnableCreatesFreshRegistrationAndCancelsOwnedWork() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        var cancelled = 0
        manager.load(object : SolvitPlugin {
            override val id = "background"
            override val provides = setOf(greeting)
            override suspend fun enable(context: PluginContext) {
                context.register(greeting, object : Greeting { override fun greet() = "ok" })
                context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { cancelled++ }
                }
            }
        })
        manager.enableAll()
        manager.disable("background")
        assertEquals(1, cancelled)
        assertFalse(manager.registry.contains(greeting))
        manager.enableAll()
        assertEquals("ok", manager.registry.require(greeting).greet())
        manager.shutdown()
        assertEquals(2, cancelled)
    }

    @Test fun authorizationIsCheckedBeforeEveryToolExecution() = runTest {
        val registry = ToolRegistry(EventBus())
        var executions = 0
        val dispose = registry.register(object : SolvitTool {
            override val descriptor = ToolDescriptor("example", "Example", "{}", setOf("android.permission.CAMERA"), true)
            override suspend fun execute(argumentsJson: String): String { executions++; return "ok" }
        })
        val deny = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = false }
        assertFailsWith<IllegalStateException> { registry.execute("example", "{}", deny) }
        assertEquals(0, executions)
        val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
        assertEquals("ok", registry.execute("example", "{}", allow))
        dispose()
        assertTrue(registry.descriptors().isEmpty())
        assertFailsWith<IllegalStateException> { registry.execute("example", "{}", allow) }
    }

    @Test fun configRejectsUnsafeUrlsAndRedactsSecrets() {
        assertNotNull(ModelConfig("http://example.com/v1").validate())
        assertNotNull(ModelConfig("https://user:pass@example.com/v1").validate())
        assertNull(ModelConfig("http://127.0.0.1:8080/v1").validate())
        assertFalse(ModelConfig(apiKey = "secret-key").toString().contains("secret-key"))
    }
}
