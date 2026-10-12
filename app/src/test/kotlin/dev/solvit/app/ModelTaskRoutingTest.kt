package dev.solvit.app

import android.app.Application
import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.provider.OpenAiProvider
import dev.solvit.anthropic.AnthropicProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ModelTaskRoutingTest {
    @Test fun nativeSignedToolContinuationKeepsItsPrefixAndReceivesRefreshedPluginContext() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        var contextText = "workspace state before tool"
        val hooks = ContextHookRegistry().apply {
            register("fixture.workspace", ContextHook { contextText })
        }
        val tools = ToolRegistry(EventBus()).apply { register(object : SolvitTool {
            override val descriptor = ToolDescriptor("clock", "Clock", "{\"type\":\"object\",\"properties\":{}}")
            override suspend fun execute(argumentsJson: String): String {
                contextText = "workspace state after actual tool"
                return "actual UTC tool result"
            }
        }) }
        val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"role":"assistant","content":[{"type":"thinking","thinking":"fixture reasoning","signature":"opaque-prefix-signature"},{"type":"tool_use","id":"c1","name":"clock","input":{}}],"stop_reason":"tool_use"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"role":"assistant","content":[{"type":"text","text":"Verified tool result"}],"stop_reason":"end_turn"}"""))
            val config = ModelConfig(server.url("/v1").toString(), "native-model", "fixture", providerId = "anthropic")
            val output = ToolAgentRuntime(AnthropicProvider(client), tools, hooks = hooks).run(ModelRequest(
                config, listOf(ModelMessage(Role.SYSTEM, "fixture identity"), ModelMessage(Role.USER, "Check current workspace")),
                authorization = allow,
            )).toList()
            assertEquals("Verified tool result", output.filterIsInstance<ModelChunk.Text>().joinToString("") { it.value })
            val first = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val next = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals(first.get("system"), next.get("system"), "Signed request header must stay unchanged")
            assertEquals(first.getJSONArray("tools").toString(), next.getJSONArray("tools").toString())
            val previousMessages = first.getJSONArray("messages")
            val continuedMessages = next.getJSONArray("messages")
            for (i in 0 until previousMessages.length())
                assertEquals(previousMessages.getJSONObject(i).toString(), continuedMessages.getJSONObject(i).toString())
            assertTrue(continuedMessages.toString().contains("opaque-prefix-signature"))
            assertTrue(continuedMessages.toString().contains("actual UTC tool result"))
            assertTrue(continuedMessages.toString().contains(contextText), "Refresh must reach the model without changing the signed prefix")
            assertEquals(2, server.requestCount)
            // Persist the actual provider projection and rebuild canonical history after closing SQLite.
            val context = RuntimeEnvironment.getApplication()
            context.deleteDatabase("solvit.db")
            lateinit var session: ChatSession
            val finalNative = output.filterIsInstance<ModelChunk.NativeTranscript>().single().value
            dev.solvit.storage.SqliteChatStore(context).use { store ->
                session = store.createSession()
                store.append(ChatMessage("u", session.id, Role.USER, "Check current workspace"))
                output.filterIsInstance<ModelChunk.Transcript>().forEachIndexed { i, chunk ->
                    val message = chunk.message
                    store.append(ChatMessage("step-$i", session.id, message.role, message.content,
                        toolCalls = message.toolCalls, toolCallId = message.toolCallId,
                        toolStage = message.toolStage, providerTranscript = message.providerTranscript))
                }
                store.append(ChatMessage("answer", session.id, Role.ASSISTANT, "Verified tool result", providerTranscript = finalNative))
            }
            contextText = "workspace state after reopen"
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"role":"assistant","content":[{"type":"text","text":"Reopened successfully"}],"stop_reason":"end_turn"}"""))
            dev.solvit.storage.SqliteChatStore(context).use { store ->
                val rows = store.history(session.id).map { message -> ModelMessage(message.role, message.content,
                    message.toolCalls, message.toolCallId, toolStage = message.toolStage, providerTranscript = message.providerTranscript) }
                val resumed = ToolAgentRuntime(AnthropicProvider(client), tools, hooks = hooks).run(ModelRequest(config,
                    listOf(ModelMessage(Role.SYSTEM, "fixture identity")) + rows + ModelMessage(Role.USER, "Continue checking"),
                    authorization = allow)).toList()
                assertEquals("Reopened successfully", resumed.filterIsInstance<ModelChunk.Text>().single().value)
            }
            val reopened = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            for (i in 0 until continuedMessages.length())
                assertEquals(continuedMessages.getJSONObject(i).toString(), reopened.getJSONObject(i).toString())
            assertTrue(reopened.toString().contains("opaque-prefix-signature"))
            assertTrue(reopened.getJSONObject(reopened.length() - 1).toString().contains(contextText))
            assertEquals(3, server.requestCount)
            context.deleteDatabase("solvit.db")
        } finally {
            server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun persistedRoleReachesActualHttpRequestOnEveryTurnIncludingDefaultIdentity() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val roles = dev.solvit.market.AndroidRoleService(context)
        val selectedRole = AgentRole("identity-http", "星辰", "使用简洁中文回答")
        roles.save(selectedRole)
        val saved = mutableListOf<ChatMessage>()
        val store = object : ChatStore {
            override fun sessions() = kotlinx.coroutines.flow.flowOf(emptyList<ChatSession>())
            override fun messages(sessionId: String) = kotlinx.coroutines.flow.flowOf(saved.toList())
            override suspend fun history(sessionId: String) = saved.toList()
            override suspend fun createSession() = ChatSession("identity-http", "Identity", 0)
            override suspend fun append(message: ChatMessage) { saved += message }
            override suspend fun appendBatch(messages: List<ChatMessage>) { saved += messages }
            override suspend fun deleteSession(sessionId: String) = Unit
        }
        val server = MockWebServer(); server.start()
        val client = OkHttpClient()
        try {
            val config = ModelConfig(server.url("/v1").toString(), "fixture", "fixture", systemPrompt="通用助手风格")
            repeat(2) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"fixture answer"},"finish_reason":"stop"}]}""")) }
            val chat = dev.solvit.chat.DefaultChatService(store, ToolAgentRuntime(OpenAiProvider(client), ToolRegistry(EventBus())), EventBus(), roles)
            roles.select("identity-http", null)
            chat.send("identity-http", "你是什么角色？", config).toList()
            roles.select("identity-http", selectedRole.id)
            chat.send("identity-http", "你现在是谁？", config).toList()
            val first = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            val next = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages")
            assertTrue(first.getJSONObject(0).getString("content").contains("角色名称是“Solvit”"))
            assertTrue(next.getJSONObject(0).getString("content").contains("角色名称是“星辰”"))
            assertTrue(next.getJSONObject(0).getString("content").contains("使用简洁中文回答"))
            assertEquals(1, (0 until next.length()).count { next.getJSONObject(it).getString("role") == "system" })
            assertEquals("你现在是谁？", next.getJSONObject(next.length()-1).getString("content"))
        } finally {
            roles.select("identity-http", null); roles.delete(selectedRole.id)
            server.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun taskRoutingCanSwitchNativeProtocolToOpenAiAndKeepPortableToolSemantics()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("model",0).edit().clear().commit()
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val profiles=SettingsModelProfiles(EncryptedSettingsStore(context) { key });profiles.initialize()
        val native=MockWebServer();val compatible=MockWebServer();native.start();compatible.start()
        val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val manager=PluginManager(scope=scope)
        try {
            val first=ModelConfig(native.url("/v1").toString(),"native-model","NATIVE_KEY",providerId="anthropic")
            profiles.save(ModelProfile("chat","Native chat",first))
            profiles.save(ModelProfile("tools","Compatible tools",ModelConfig(compatible.url("/v1").toString(),"compatible-model","COMPATIBLE_KEY")))
            profiles.assign(ModelTask.TOOL_FOLLOW_UP,"tools")
            manager.load(ModelProvidersPlugin());manager.load(ModelRoutingPlugin());manager.enableAll()
            val providers=manager.registry.require(ModelProvidersServiceKey)
            providers.register(ModelProviderInfo("anthropic","Native","",""),AnthropicProvider(client))
            providers.register(ModelProviderInfo("openai-compatible","Compatible","",""),OpenAiProvider(client))
            native.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"role":"assistant","content":[{"type":"thinking","thinking":"reason","signature":"opaque-native-signature"},{"type":"tool_use","id":"c1","name":"clock","input":{}}],"stop_reason":"tool_use"}"""))
            compatible.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"choices":[{"message":{"content":"Checked actual tool result"},"finish_reason":"stop"}]}"""))
            val tools=ToolRegistry(EventBus()).apply { register(object:SolvitTool {
                override val descriptor=ToolDescriptor("clock","Clock","{}")
                override suspend fun execute(argumentsJson:String)="actual UTC"
            }) }
            val allow=object:ToolAuthorization { override suspend fun authorize(descriptor:ToolDescriptor)=true }
            val output=ToolAgentRuntime(manager.registry.require(Services.Model),tools,modelProfiles=profiles).run(
                ModelRequest(first,listOf(ModelMessage(Role.USER,"check time")),authorization=allow)).toList()
            assertEquals("Checked actual tool result",output.filterIsInstance<ModelChunk.Text>().single().value)
            val firstHttp=native.takeRequest(2,TimeUnit.SECONDS)!!;val secondHttp=compatible.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("/v1/messages",firstHttp.path);assertEquals("NATIVE_KEY",firstHttp.getHeader("x-api-key"))
            assertEquals("/v1/chat/completions",secondHttp.path);assertEquals("Bearer COMPATIBLE_KEY",secondHttp.getHeader("Authorization"))
            assertNull(secondHttp.getHeader("x-api-key"))
            val body=JSONObject(secondHttp.body.readUtf8())
            assertEquals("compatible-model",body.getString("model"))
            assertTrue(body.toString().contains("actual UTC"));assertFalse(body.toString().contains("opaque-native-signature"))
            val messages=body.getJSONArray("messages")
            assertEquals("c1",messages.getJSONObject(messages.length()-1).getString("tool_call_id"))
        } finally { manager.shutdown();scope.cancel();native.shutdown();compatible.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    }
    @Test fun actualHttpToolFollowUpUsesItsOwnEndpointModelAndCredentialAndKeepsTheTranscript()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("model",0).edit().clear().commit()
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val profiles=SettingsModelProfiles(EncryptedSettingsStore(context) { key });profiles.initialize()
        val chat=MockWebServer();val tools=MockWebServer();chat.start();tools.start()
        val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
        try {
            val chatConfig=ModelConfig(chat.url("/v1").toString(),"chat-model","CHAT_KEY")
            profiles.save(ModelProfile("chat","Chat",chatConfig))
            profiles.save(ModelProfile("tools","Tools",ModelConfig(tools.url("/v1").toString(),"tool-model","TOOLS_KEY")))
            profiles.assign(ModelTask.TOOL_FOLLOW_UP,"tools")
            chat.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"clock","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()+"\n\n"))
            tools.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("""
                data: {"choices":[{"delta":{"content":"Checked time"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

            """.trimIndent()+"\n\n"))
            val registry=ToolRegistry(EventBus()).apply { register(object: SolvitTool {
                override val descriptor=ToolDescriptor("clock","Clock","{\"type\":\"object\",\"properties\":{}}")
                override suspend fun execute(argumentsJson:String)="UTC clock result"
            }) }
            val allow=object:ToolAuthorization { override suspend fun authorize(descriptor:ToolDescriptor)=true }
            val output=ToolAgentRuntime(OpenAiProvider(client),registry,modelProfiles=profiles).run(
                ModelRequest(chatConfig,listOf(ModelMessage(Role.USER,"Check the time")),authorization=allow)).toList()
            assertEquals("Checked time",output.filterIsInstance<ModelChunk.Text>().joinToString(""){it.value})
            val first=chat.takeRequest(2,TimeUnit.SECONDS)!!;val second=tools.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("Bearer CHAT_KEY",first.getHeader("Authorization"))
            assertEquals("Bearer TOOLS_KEY",second.getHeader("Authorization"))
            val body=JSONObject(second.body.readUtf8())
            assertEquals("tool-model",body.getString("model"))
            assertTrue(body.getJSONArray("messages").toString().contains("UTC clock result"))
            assertFalse(body.toString().contains("CHAT_KEY"))
            assertEquals(1,chat.requestCount);assertEquals(1,tools.requestCount)
        } finally { chat.shutdown();tools.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    }
}
