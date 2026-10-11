package dev.solvit.plugins

import com.dokar.quickjs.QuickJsInterruptedException
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ScriptRuntimeTest {
    private fun pkg(source: String, permissions: List<String> = emptyList()): ScriptPackage {
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", "test.plugin"); put("name", "Test"); put("description", "Runtime test"); put("version", "1.0.0"); put("apiVersion", 1)
                put("permissions", JsonArray(permissions.map(::JsonPrimitive)))
                put("dependencies", JsonArray(emptyList()))
                putJsonArray("tools") { add(buildJsonObject {
                    put("name", "echo"); put("description", "Echo input"); putJsonObject("inputSchema") { put("type", "object") }
                }) }
            }
            put("source", source)
        }
        return ScriptPackage.parse(payload.toString().toByteArray())
    }

    private val deniedHost = ScriptHost { _, _, _ -> error("Host should not be called") }

    @Test fun executesAsyncJavaScriptAndPreservesArgumentsAsData() = runBlocking<Unit> {
        val executor = ScriptExecutor(pkg("globalThis.solvitPlugin={tools:{echo: async args => args}};"), deniedHost)
        executor.start()
        try {
            val input = buildJsonObject { put("text", "中文 ' \" ); throw new Error('injection'); //") }
            assertEquals(input, Json.parseToJsonElement(executor.execute("echo", input.toString())))
        } finally { executor.stop() }
        assertFailsWith<IllegalStateException> { executor.execute("echo", "{}") }
    }

    @Test fun undeclaredHostPermissionCannotBeBypassedFromJavaScript() = runBlocking<Unit> {
        var calls = 0
        val executor = ScriptExecutor(pkg("globalThis.solvitPlugin={tools:{echo: async args => await __solvitHostCall('storage.get','{}')}};"),
            ScriptHost { _, _, _ -> calls++; JsonNull })
        executor.start()
        try {
            val failure = assertFailsWith<IllegalStateException> { executor.execute("echo", "{}") }
            assertEquals("插件未声明 storage 权限", failure.message)
            assertEquals(0, calls)
        }
        finally { executor.stop() }
    }

    @Test fun declaredStorageBridgeAwaitsNativeResult() = runBlocking<Unit> {
        val executor = ScriptExecutor(pkg("globalThis.solvitPlugin={tools:{echo: async args => await ctx.storage.get(args.key)}};", listOf("storage")),
            ScriptHost { plugin, operation, arguments ->
                assertEquals("test.plugin", plugin.id); assertEquals("storage.get", operation)
                assertEquals("name", arguments["key"]?.jsonPrimitive?.content)
                JsonPrimitive("Solvit")
            })
        executor.start()
        try { assertEquals("\"Solvit\"", executor.execute("echo", """{"key":"name"}""")) }
        finally { executor.stop() }
    }

    @Test fun infiniteLoopIsInterruptedByEngineDeadline() = runBlocking<Unit> {
        val executor = ScriptExecutor(pkg("globalThis.solvitPlugin={tools:{echo: args => {while(true){}}}};"), deniedHost, timeoutMs = 100)
        executor.start()
        try { withTimeout(5_000) { assertFailsWith<QuickJsInterruptedException> { executor.execute("echo", "{}") } } }
        finally { executor.stop() }
    }

    @Test fun cancellingEvaluationCancelsPendingNativeHostWorkBeforeReturning() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val executor = ScriptExecutor(pkg("globalThis.solvitPlugin={tools:{echo:async args=>await ctx.storage.get('wait')}};", listOf("storage")),
            ScriptHost { _, _, _ ->
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            })
        executor.start()
        try {
            val job = launch { executor.execute("echo", "{}") }
            withTimeout(5_000) { started.await(); job.cancelAndJoin() }
            assertTrue(cancelled.isCompleted)
        } finally { executor.stop() }
    }

    @Test fun toolRegistrationsDisappearWhenPluginUnloads() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        try {
            manager.load(ToolsPlugin())
            manager.load(ScriptToolPlugin(pkg("globalThis.solvitPlugin={tools:{echo: async args => args}};"), deniedHost))
            manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            assertEquals(listOf("test_plugin_echo"), tools.descriptors().map { it.name })
            val authorization = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            assertEquals("{}", tools.execute("test_plugin_echo", "{}", authorization))
            manager.unload("test.plugin")
            assertTrue(tools.descriptors().isEmpty())
        } finally { manager.shutdown(); scope.cancel() }
    }

    @Test fun tamperedPackageFailsBeforeCodeExecutes() {
        val bytes = pkg("globalThis.solvitPlugin={tools:{echo: args => args}};").payload
        assertFailsWith<IllegalArgumentException> { ScriptPackage.parse(bytes, "0".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { ScriptPackage.parse(ByteArray(ScriptPackage.MAX_BYTES + 1)) }
    }
}
