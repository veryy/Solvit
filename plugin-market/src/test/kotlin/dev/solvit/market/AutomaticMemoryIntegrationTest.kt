package dev.solvit.market

import android.content.Context
import dev.solvit.chat.PostTurnWorker
import dev.solvit.core.*
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AutomaticMemoryIntegrationTest {
    @Test fun actualCompletedTurnConsumerExtractsThroughJsAndPreservesConcurrentManualMemory() = runBlocking<Unit> {
        val context: Context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val store = SqliteChatStore(context)
        val client = OkHttpClient()
        val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32), "AES") }
        val role = AgentRole("memory-role", "Memory role", pluginIds = setOf("dev.solvit.memory"))
        var selected: AgentRole? = role
        val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
        val entered = ChannelSignals()
        var modelCalls = 0
        val modelConfig = ModelConfig(providerId = "fixture", model = "fixture-model", apiKey = "fixture-key")
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest): Flow<ModelChunk> = flow {
                modelCalls++
                assertTrue(request.tools.isEmpty()); assertNull(request.agentRole)
                assertEquals("1024", request.config.providerOptions["max_tokens"])
                assertTrue(request.contextMeasurement!!.fits)
                val input = Json.parseToJsonElement(request.messages.single { it.role == Role.USER }.content).jsonObject
                assertEquals("我喜欢 Kotlin 编程。", input.getValue("USER").jsonPrimitive.content)
                entered.started.complete(Unit); entered.release.await()
                emit(ModelChunk.Text("""{"facts":[{"text":"用户喜欢 Kotlin 编程","category":"preference","tags":["编程"],"quote":"我喜欢 Kotlin 编程。"}]}"""))
                emit(ModelChunk.Done)
            }
        }
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.enableAll()
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), config)
            market.restore(); market.enable("dev.solvit.memory", setOf("storage", "model"))
            val hooks = manager.registry.require(PostTurnHookServiceKey)
            assertTrue(hooks.admit(role).isEmpty())
            market.disable("dev.solvit.memory"); market.configure("dev.solvit.memory", mapOf("autoSave" to "true"))
            market.enable("dev.solvit.memory", setOf("storage", "model"))
            val target = hooks.admit(role).single()
            suspend fun appendSource(): CompletedChatTurn {
                val session = store.createSession()
                val user = ChatMessage("user-${session.id}", session.id, Role.USER, "我喜欢 Kotlin 编程。")
                val reply = ChatMessage("reply-${session.id}", session.id, Role.ASSISTANT, "好的。")
                store.append(user)
                val turn = CompletedChatTurn(reply.id, session.id, user.id, reply.id, role.id, role.name, role.pluginIds,
                    "profile", provider.id, modelConfig.model, 1, listOf(target))
                store.appendCompletedTurn(listOf(reply), turn); return turn
            }
            val turn = appendSource()
            val worker = PostTurnWorker(store, hooks, provider, { modelConfig }, { selected }, scope)
            val task = async { worker.runNext() }
            withTimeout(10_000) { entered.started.await() }
            withTimeout(5_000) {
                market.runTool("dev_solvit_memory_memory_put", """{"text":"手动保存的旅行偏好"}""", allow)
                manager.registry.require(ContextHookServiceKey).prepare(ContextInput(role, "旅行"))
            }
            entered.release.complete(Unit); assertTrue(withTimeout(10_000) { task.await() })
            val job = store.postTurnJobs(setOf(PostTurnJobStatus.SUCCEEDED), 100).single()
            assertEquals(turn.id, job.turn.id); assertEquals(1, modelCalls); assertFalse(worker.runNext())
            val memory = Json.parseToJsonElement(market.runTool("dev_solvit_memory_memory_export", "{}", allow)).jsonObject
            assertEquals(2, memory.getValue("facts").jsonArray.size)
            assertEquals(turn.id, memory.getValue("completedSources").jsonArray.single().jsonObject.getValue("turnId").jsonPrimitive.content)
            val recalled = manager.registry.require(ContextHookServiceKey).prepare(ContextInput(role, "Kotlin")).single()
            assertTrue(recalled.contains(turn.userMessageId)); assertTrue(recalled.contains("Kotlin"))
            assertTrue(manager.registry.require(ContextHookServiceKey).prepare(ContextInput(AgentRole("other", "Other", pluginIds = role.pluginIds), "Kotlin")).isEmpty())
            store.createSession() // Recall is independent of the originating chat.
            assertTrue(manager.registry.require(ContextHookServiceKey).prepare(ContextInput(role, "Kotlin")).single().contains(turn.id))
            // A changed role after inference starts must revoke the write before CAS.
            val saved = context.getSharedPreferences("extension_data.dev.solvit.memory", 0).getString("memory.v1", null)
            entered.reset(); val second = appendSource(); val revoked = async { worker.runNext() }
            withTimeout(10_000) { entered.started.await() }; selected = null; entered.release.complete(Unit)
            assertTrue(withTimeout(10_000) { revoked.await() })
            assertEquals(second.id, store.postTurnJobs(setOf(PostTurnJobStatus.SKIPPED), 100).single().turn.id)
            assertEquals(saved, context.getSharedPreferences("extension_data.dev.solvit.memory", 0).getString("memory.v1", null))
        } finally {
            entered.release.complete(Unit); manager.shutdown(); scope.cancel(); store.close()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    private class ChannelSignals {
        var started = CompletableDeferred<Unit>()
        var release = CompletableDeferred<Unit>()
        fun reset() { started = CompletableDeferred(); release = CompletableDeferred() }
    }
}
