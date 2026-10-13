package dev.solvit.plugins

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ScriptAgentPluginTest {
    private fun pkg(api: Int = 14, grant: Boolean = true, default: String = "false", source: String = strategy): ScriptPackage {
        val manifest = """{"id":"test.strategy","name":"Strategy","description":"Test","version":"1.0.0",
          "apiVersion":$api,"permissions":${if (grant) "[\"agent\"]" else "[]"},"dependencies":[],"tools":[],
          "agentEnabledSetting":"selected","settings":[{"key":"selected","kind":"boolean","label":"Select","defaultValue":"$default"}]}"""
        return ScriptPackage.parse(buildJsonObject { put("manifest", Json.parseToJsonElement(manifest)); put("source", source) }.toString().toByteArray())
    }
    @Test fun manifestRequiresNewApiPermissionAndDefaultOffOptIn() {
        assertFailsWith<IllegalArgumentException> { pkg(api = 13) }
        assertFailsWith<IllegalArgumentException> { pkg(grant = false) }
        assertFailsWith<IllegalArgumentException> { pkg(default = "true") }
        val managed = Json.parseToJsonElement(pkg().payload.toString(Charsets.UTF_8)).jsonObject
        val manifest = managed.getValue("manifest").jsonObject
        val field = manifest.getValue("settings").jsonArray.single().jsonObject
        val invalid = JsonObject(managed + ("manifest" to JsonObject(manifest + ("settings" to JsonArray(listOf(JsonObject(field + ("pageManaged" to JsonPrimitive(true)))))))))
        assertFailsWith<IllegalArgumentException> { ScriptPackage.parse(invalid.toString().toByteArray()) }
        assertEquals("selected", pkg().manifest.agentEnabledSetting)
    }
    @Test fun managedPluginRegistersAnIsolatedQuickJsStrategyAndUnloadsIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val defaults = mutableListOf<String>()
        val drivers = AgentDriverRegistry(AgentDriver { _, _ -> defaults += "default" })
        manager.load(object : SolvitPlugin {
            override val id = "host.services"
            override val provides = setOf(Services.Tools, AgentDriversServiceKey)
            override suspend fun enable(context: PluginContext) {
                context.register(Services.Tools, ToolRegistry(context.events))
                context.register(AgentDriversServiceKey, drivers)
            }
        })
        var selected = false
        manager.load(ScriptToolPlugin(pkg(), ScriptHost { _, op, args ->
            assertEquals("settings.get", op)
            assertEquals("selected", args.getValue("key").jsonPrimitive.content)
            JsonPrimitive(selected.toString())
        }))
        try {
            manager.enableAll()
            val commands = mutableListOf<Pair<String, Int>>()
            val host = AgentStepHost { op, index -> commands += op to index; if (op == "model" && index == 0) 1 else 0 }
            drivers.run(8, host)
            assertTrue(commands.isEmpty())
            selected = true
            drivers.run(8, host)
            assertEquals(listOf("model" to 0, "tool" to 0, "settle" to 0, "model" to 1, "finish" to 1), commands)
            val entered = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val turn = launch { drivers.run(8, AgentStepHost { _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned.complete(Unit) }
            }) }
            withTimeout(10_000) { entered.await() }
            manager.disable("test.strategy")
            assertTrue(turn.isCancelled)
            assertTrue(cleaned.isCompleted)
            commands.clear(); drivers.run(8, host)
            assertTrue(commands.isEmpty())
            assertEquals(2, defaults.size)
        } finally { manager.shutdown(); scope.cancel() }
    }
    companion object {
        private val strategy = """globalThis.solvitPlugin={tools:{},agent:async({maxRounds},ctx)=>{
          if(typeof __solvitHostCall!=='undefined')throw new Error('Tool bridge leaked');
          for(let r=0;r<maxRounds;r++){const n=await ctx.model(r);if(!n){await ctx.finish(r);return;}
            for(let i=0;i<n;i++)await ctx.tool(i);await ctx.settle(r);}
        }};"""
    }
}
