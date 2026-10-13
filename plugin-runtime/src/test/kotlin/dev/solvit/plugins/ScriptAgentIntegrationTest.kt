package dev.solvit.plugins

import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.core.*
import dev.solvit.provider.OpenAiProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

/** Actual HTTP provider -> authorization -> native QuickJS -> HTTP tool result. */
class ScriptAgentIntegrationTest {
    @Test fun brokenJavaScriptHookStillAllowsChatAndReportsOnlySafeMetadata() = runBlocking<Unit> {
        val pkg = ScriptPackage.parse("""{
          "manifest":{"id":"test.broken","name":"Broken Hook","description":"Integration","version":"1.0.0",
          "apiVersion":3,"permissions":[],"dependencies":[],"hooks":["beforeModel"],
          "tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]},
          "source":"globalThis.solvitPlugin={beforeModel:async()=>{throw new Error('PRIVATE_SECRET_SENTINEL')},tools:{echo:async()=>({ok:true})}};"
        }""".toByteArray())
        val server = MockWebServer()
        val client = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        server.start()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin())
            manager.load(ScriptToolPlugin(pkg, ScriptHost { _, _, _ -> fail("No host permissions") }))
            manager.enableAll()
            val hooks = manager.registry.require(ContextHookServiceKey)
            val unregister = hooks.register("test.healthy", ContextHook { "healthy reference" })
            try {
                val inspection = RequestInspectionService()
                val chunks = ToolAgentRuntime(OpenAiProvider(client), manager.registry.require(Services.Tools),
                    hooks = hooks, inspection = inspection).run(ModelRequest(
                    ModelConfig(server.url("/v1").toString(), "fixture"),
                    listOf(ModelMessage(Role.SYSTEM, "trusted role"), ModelMessage(Role.USER, "hello")),
                )).toList()
                assertEquals("hello", chunks.filterIsInstance<ModelChunk.Text>().single().value)
                assertEquals(ModelChunk.Done, chunks.last())
                val wire = server.takeRequest().body.readUtf8()
                assertTrue(wire.contains("healthy reference"))
                assertTrue(wire.contains("test.broken"))
                assertFalse(wire.contains("PRIVATE_SECRET_SENTINEL"))
                assertEquals(listOf(ContextHookProblem("test.broken", ContextHookFailure.FAILED)), inspection.latest.value!!.contextProblems)
                assertFalse(inspection.latest.value.toString().contains("PRIVATE_SECRET_SENTINEL"))
                // A failed optional read does not silently deactivate this plugin's tools.
                val tools = manager.registry.require(Services.Tools)
                assertTrue(tools.executeCall(ToolCall("still-active", "test_broken_echo", "{}"),
                    object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }).contains("true"))
                manager.disable("test.broken")
                val report = hooks.prepareReport(ContextInput(null, "hello"))
                assertTrue(report.problems.isEmpty()); assertEquals(1, report.context.size)
            } finally { unregister() }
        } finally {
            manager.shutdown(); scope.cancel(); server.shutdown()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun modelFunctionCallExecutesInstalledJavaScriptAndReturnsItsResult() = runBlocking<Unit> {
        val pkg = ScriptPackage.parse("""{
          "manifest":{"id":"test.clock","name":"Clock","description":"Integration","version":"1.0.0",
          "apiVersion":1,"permissions":[],"dependencies":[],"tools":[{"name":"echo","description":"Echo",
          "inputSchema":{"type":"object","properties":{"text":{"type":"string"}}}}]},
          "source":"globalThis.solvitPlugin={tools:{echo:async args=>({echo:args.text})}};"
        }""".toByteArray())
        val server = MockWebServer()
        val client = OkHttpClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        server.start()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{
          "choices":[{"message":{"content":null,"tool_calls":[{"id":"call_1","type":"function",
          "function":{"name":"test_clock_echo","arguments":"{\"text\":\"你好\"}"}}]},"finish_reason":"tool_calls"}]
        }"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"已收到你好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
        ))
        try {
            manager.load(ToolsPlugin())
            manager.load(ScriptToolPlugin(pkg, ScriptHost { _, _, _ -> fail("No host permissions") }))
            manager.enableAll()
            var approvals = 0
            val gate = object : ToolAuthorization {
                override suspend fun authorize(descriptor: ToolDescriptor) = false
                override suspend fun authorizeCall(descriptor: ToolDescriptor, call: ToolCall): Boolean {
                    assertEquals("test_clock_echo", descriptor.name)
                    assertEquals("你好", Json.parseToJsonElement(call.argumentsJson).jsonObject["text"]?.jsonPrimitive?.content)
                    approvals++
                    return true
                }
            }
            val agent = ToolAgentRuntime(OpenAiProvider(client), manager.registry.require(Services.Tools))
            val result = withTimeout(10_000) { agent.run(ModelRequest(
                ModelConfig(server.url("/v1").toString(), "test"), listOf(ModelMessage(Role.USER, "echo 你好")), authorization = gate,
            )).toList() }
            assertEquals(1, approvals)
            assertEquals("已收到你好", result.filterIsInstance<ModelChunk.Text>().single().value)
            assertEquals(ToolStage.SUCCEEDED, result.filterIsInstance<ModelChunk.ToolActivity>().last().stage)
            val initial = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("test_clock_echo", initial["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val followUp = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val tool = followUp["messages"]!!.jsonArray.last().jsonObject
            assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
            assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals("你好", Json.parseToJsonElement(tool["content"]!!.jsonPrimitive.content).jsonObject["echo"]!!.jsonPrimitive.content)
        } finally {
            manager.shutdown(); scope.cancel(); server.shutdown()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
