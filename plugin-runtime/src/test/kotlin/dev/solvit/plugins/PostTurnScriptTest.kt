package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class PostTurnScriptTest {
    private fun pkg(source: String, api: Int = 8, default: String = "false"): ScriptPackage {
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", "test.background"); put("name", "Background"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", api)
                put("permissions", JsonArray(listOf("storage", "model").map(::JsonPrimitive)))
                put("dependencies", JsonArray(emptyList()))
                put("hooks", JsonArray(listOf("beforeModel", "afterTurn").map(::JsonPrimitive)))
                put("afterTurnEnabledSetting", "autoSave")
                putJsonArray("settings") {
                    add(buildJsonObject { put("key", "autoSave"); put("label", "Save"); put("kind", "boolean"); put("defaultValue", default) })
                    add(buildJsonObject { put("key", "token"); put("label", "Secret"); put("kind", "secret") })
                }
                putJsonArray("tools") { add(buildJsonObject {
                    put("name", "echo"); put("description", "Echo"); putJsonObject("inputSchema") { put("type", "object") }
                }) }
            }
            put("source", source)
        }
        return ScriptPackage.parse(payload.toString().toByteArray())
    }
    private fun input(target: PostTurnTarget) = PostTurnInput(CompletedChatTurn(
        "answer", "session", "question", "answer", "role", "Role", setOf(target.pluginId),
        "profile", "openai", "model", 1, listOf(target)), "user text", "assistant text")

    @Test fun backgroundManifestRequiresApiEightAndDefaultOff() {
        val source = "globalThis.solvitPlugin={tools:{echo:args=>args},beforeModel:()=>'',afterTurn:()=>{}};"
        assertFailsWith<IllegalArgumentException> { pkg(source, api = 7) }
        assertFailsWith<IllegalArgumentException> { pkg(source, default = "true") }
        assertEquals("autoSave", pkg(source).manifest.afterTurnEnabledSetting)
    }

    @Test fun actualBackgroundEngineLeavesForegroundResponsiveAndSkipsLifecycle() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val model = PostTurnModel { instructions, text ->
            assertEquals("extract", instructions); assertEquals("user text", text)
            entered.complete(Unit); release.await(); "fact"
        }
        val host = ScriptHost { _, operation, args ->
            calls += operation
            when (operation) {
                "settings.get" -> { assertEquals("autoSave", args.getValue("key").jsonPrimitive.content); JsonPrimitive("true") }
                "model.complete" -> JsonPrimitive(currentCoroutineContext()[PostTurnExecutionContext]!!.complete(
                    args.getValue("instructions").jsonPrimitive.content, args.getValue("input").jsonPrimitive.content))
                "storage.set" -> JsonNull
                else -> error("Unexpected host operation")
            }
        }
        val source = """let activated=false;globalThis.solvitPlugin={
          activate:async(ctx)=>{activated=true;await ctx.storage.set('activated',true)},
          deactivate:async(ctx)=>await ctx.storage.set('deactivated',true),
          tools:{echo:args=>args},beforeModel:()=>activated?'foreground':'cold',
          afterTurn:async(input,ctx)=>{
            if(activated)throw new Error('Shared lifecycle');
            if(input.settings.token!==undefined||Object.keys(input.source).length!==6)throw new Error('Unsafe payload');
            if(input.source.roleId!=='role'||input.assistantText!=='assistant text')throw new Error('Wrong source');
            await ctx.model.complete({instructions:'extract',input:input.userText});
            await ctx.storage.set('fact',true);
          }};""".trimIndent()
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin())
            manager.load(ScriptToolPlugin(pkg(source), host)); manager.enableAll()
            val registry = manager.registry.require(PostTurnHookServiceKey)
            val target = registry.admit(null).single(); val hook = registry.resolve(target)!!
            val execution = PostTurnExecutionContext(target, model) {}
            val task = async(execution) { hook.process(input(target), model) }
            withTimeout(5_000) { entered.await() }
            withTimeout(2_000) {
                assertEquals("插件上下文（test.background）：\nforeground", manager.registry.require(ContextHookServiceKey).prepare(ContextInput(null, "hello")).single())
                val authorization = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
                assertEquals("{}", manager.registry.require(Services.Tools).execute("test_background_echo", "{}", authorization))
            }
            release.complete(Unit); withTimeout(5_000) { task.await() }
            assertEquals(2, calls.count { it == "storage.set" })
            manager.unload(target.pluginId)
            assertEquals(3, calls.count { it == "storage.set" })
            assertNull(registry.resolve(target))
        } finally { release.complete(Unit); manager.shutdown(); scope.cancel() }
    }

    @Test fun backgroundDisallowsSecretsNetworkAndForegroundModelRequests() = runBlocking<Unit> {
        val source = """globalThis.solvitPlugin={tools:{echo:async(args,ctx)=>await ctx.model.complete({instructions:'x',input:'x'})},
          beforeModel:()=>'',afterTurn:async(input,ctx)=>{
            if(input.userText==='secret')await ctx.settings.get('token');
            else await ctx.http.request({url:'https://example.org'});
          }};"""
        val packageValue = pkg(source)
        val target = PostTurnTarget(packageValue.manifest.id, "1.0.0", "0".repeat(64))
        var calls = 0
        val host = ScriptHost { _, operation, _ ->
            calls++; assertEquals("settings.get", operation); JsonPrimitive("true")
        }
        val warm = ScriptExecutor(packageValue, host)
        warm.start()
        try { assertFailsWith<IllegalStateException> { warm.execute("echo", "{}") }; assertEquals(0, calls) }
        finally { warm.stop() }
        withContext(PostTurnExecutionContext(target, PostTurnModel { _, _ -> error("No model call") }) {}) {
            val cold = ScriptExecutor(packageValue, host, background = true)
            cold.start()
            try {
                for (text in listOf("secret", "network")) assertFailsWith<IllegalStateException> {
                    cold.afterTurn(input(target).copy(userText = text))
                }
                assertEquals(2, calls) // Only the schema-declared public setting.
            } finally { cold.stop() }
        }
    }
}
