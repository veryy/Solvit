package dev.solvit.plugins

import com.dokar.quickjs.QuickJsInterruptedException
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ScriptRuntimeTest {
    private fun pkg(source: String, permissions: List<String> = emptyList(), hook: Boolean = false, apiVersion: Int = if (hook) 3 else 1, settings: List<ExtensionSetting> = emptyList()): ScriptPackage {
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", "test.plugin"); put("name", "Test"); put("description", "Runtime test"); put("version", "1.0.0"); put("apiVersion", apiVersion)
                if (hook) put("hooks", JsonArray(listOf(JsonPrimitive("beforeModel"))))
                if (settings.isNotEmpty()) putJsonArray("settings") { settings.forEach { field -> add(buildJsonObject {
                    put("key", field.key); put("label", field.label); put("kind", field.kind)
                    field.defaultValue?.let { put("defaultValue", it) }
                }) } }
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

    @Test fun hookReceivesOnlyDeclaredPublicSettingsAndCannotReadSecrets() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val source = """globalThis.solvitPlugin={tools:{echo:args=>args},beforeModel:async(input,ctx)=>{
          if(input.userText==='secret') return await ctx.settings.get('token');
          return JSON.stringify(input.settings);
        }};"""
        val executor = ScriptExecutor(pkg(source, hook = true, apiVersion = 7, settings = listOf(
            ExtensionSetting("autoRecall", "Recall", "boolean", defaultValue = "true"),
            ExtensionSetting("token", "Secret", "secret"))), ScriptHost { _, operation, args ->
            assertEquals("settings.get", operation)
            val key = args.getValue("key").jsonPrimitive.content; calls += key
            assertEquals("autoRecall", key); JsonPrimitive("false")
        })
        executor.start()
        try {
            assertEquals("""{"autoRecall":"false"}""", executor.beforeModel(ContextInput(null, "read")))
            assertFailsWith<IllegalStateException> { executor.beforeModel(ContextInput(null, "secret")) }
            assertEquals(listOf("autoRecall", "autoRecall"), calls)
        } finally { executor.stop() }
        assertFailsWith<IllegalArgumentException> { pkg(source, hook = true, apiVersion = 6,
            settings = listOf(ExtensionSetting("autoRecall", "Recall", "boolean"))) }
        assertFailsWith<IllegalArgumentException> { pkg(source, hook = true, apiVersion = 7,
            settings = listOf(ExtensionSetting("token", "Secret", "secret", defaultValue = "true"))) }
    }

    @Test fun browserBridgeRequiresApiSixAndDeclaredNetworkPermission() = runBlocking<Unit> {
        val source = "globalThis.solvitPlugin={tools:{echo:async(args,ctx)=>await ctx.web.visit(args.url)}};"
        for ((api, permissions) in listOf(5 to listOf("network"), 6 to emptyList())) {
            val executor = ScriptExecutor(pkg(source, permissions, apiVersion = api), deniedHost)
            executor.start()
            try { assertFailsWith<IllegalStateException> { executor.execute("echo", """{"url":"https://example.org"}""") } }
            finally { executor.stop() }
        }
        val executor = ScriptExecutor(pkg(source, listOf("network"), apiVersion = 6), ScriptHost { _, operation, args ->
            assertEquals("web.visit", operation)
            assertEquals("https://example.org", args.getValue("url").jsonPrimitive.content)
            buildJsonObject { put("text", "browser content") }
        })
        executor.start()
        try { assertEquals("browser content", Json.parseToJsonElement(executor.execute("echo", """{"url":"https://example.org"}""")).jsonObject.getValue("text").jsonPrimitive.content) }
        finally { executor.stop() }
    }

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
        var calls = 0
        var deactivated = false
        val executor = ScriptExecutor(pkg("globalThis.solvitPlugin={tools:{echo:async args=>await ctx.storage.get('wait')},deactivate:async()=>await ctx.storage.get('stopped')};", listOf("storage")),
            ScriptHost { _, _, arguments ->
                if (arguments["key"]?.jsonPrimitive?.content == "stopped") {
                    deactivated = true
                    JsonNull
                } else if (++calls == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                } else JsonPrimitive("reused")
            })
        executor.start()
        try {
            val job = launch { executor.execute("echo", "{}") }
            withTimeout(5_000) { started.await(); job.cancelAndJoin() }
            assertTrue(cancelled.isCompleted)
            assertEquals("\"reused\"", executor.execute("echo", "{}"))
        } finally { executor.stop() }
        assertTrue(deactivated)
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

    @Test fun hookIsReadOnlyAndRoleContextComesFromHost() = runBlocking<Unit> {
        var reads = 0
        val executor = ScriptExecutor(pkg("""
            globalThis.solvitPlugin={
              tools:{echo:async(args,ctx)=>ctx.role},
              beforeModel:async(input,ctx)=>{
                if(input.userText==='write') await ctx.storage.set('x',1);
                if(input.userText==='secret') await ctx.settings.get('token');
                return (await ctx.storage.get('catalog'))+':'+ctx.role.skillIds.join(',');
              }
            };
        """.trimIndent(), listOf("storage"), hook = true), ScriptHost { _, operation, _ ->
            assertEquals("storage.get", operation); reads++; JsonPrimitive("catalog")
        })
        val role = AgentRole("role", "Role", pluginIds = setOf("test.plugin"), skillIds = setOf("code-review"))
        executor.start()
        try {
            assertEquals("catalog:code-review", executor.beforeModel(ContextInput(role, "read")))
            for (text in listOf("write", "secret")) {
                assertFailsWith<IllegalStateException> { executor.beforeModel(ContextInput(role, text)) }
            }
            assertEquals(1, reads)
            val response = withContext(AgentRoleContext(role)) { executor.execute("echo", """{"role":null}""") }
            assertEquals("role", Json.parseToJsonElement(response).jsonObject.getValue("id").jsonPrimitive.content)
        } finally { executor.stop() }
    }
}
