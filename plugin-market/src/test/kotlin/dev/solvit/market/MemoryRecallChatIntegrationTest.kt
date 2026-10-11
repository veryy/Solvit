package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.*
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoryRecallChatIntegrationTest {
    @Test fun populatedCorpusRunsInActualQuickJsWithinMemoryAndHookLimits() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val text = (0 until 100).map { (0x4e00 + it).toChar() }.joinToString("")
        val corpus = buildJsonObject {
            put("format", 1); put("sequence", 256)
            putJsonArray("facts") { repeat(256) { index -> add(buildJsonObject {
                put("id", "m$index"); put("text", "记录 $index ${text.take(50)}"); put("category", "fact")
                put("tags", JsonArray(emptyList())); put("createdAt", "2026-10-12"); put("updatedAt", "2026-10-12")
            }) } }
            put("edges", JsonArray(emptyList()))
            putJsonArray("chunks") { repeat(256) { index -> add(buildJsonObject {
                put("id", "doc:$index"); put("document", "doc"); put("title", "资料"); put("index", index); put("text", text.drop(50))
            }) } }
            put("profile", buildJsonObject {})
        }.toString()
        assertTrue(corpus.toByteArray().size < 220 * 1024)
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.enableAll()
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools))
            market.restore()
            context.getSharedPreferences("extension_data.dev.solvit.memory", 0).edit().putString("memory.v1", corpus).commit()
            market.enable("dev.solvit.memory", setOf("storage", "model"))
            val started = System.nanoTime()
            val result = manager.registry.require(ContextHookServiceKey).prepare(ContextInput(null, text)).single()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(result.contains("dev.solvit.memory/")); assertTrue(result.length < 6500)
            assertTrue(elapsedMs < 8000, "Native QuickJS recall consumed the Hook deadline: $elapsedMs ms")
        } finally {
            manager.shutdown(); scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun savedPreferenceReachesNextStepAndNewSessionAfterRestartThenHonorsDeleteSwitchAndRole() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient()
        val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32), "AES") }
        var manager = PluginManager(scope = scope)
        var store = SqliteChatStore(context)
        suspend fun setup(): AndroidMarketplace {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.load(RolesPlugin(context)); manager.enableAll()
            return AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), config).also { it.restore() }
        }
        val role = AgentRole("memory-role", "Memory role", "Use explicit user preferences", setOf("dev.solvit.memory"))
        val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
        try {
            var market = setup()
            assertEquals("true", market.configuration("dev.solvit.memory")["autoRecall"])
            market.enable("dev.solvit.memory", setOf("storage", "model"))
            var roles = manager.registry.require(RoleServiceKey)
            roles.save(role)
            val first = store.createSession(); roles.select(first.id, role.id)
            var round = 0
            val provider = object : ModelProvider {
                override val id = "fixture"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    val system = request.messages.single { it.role == Role.SYSTEM }.content
                    if (round++ == 0) {
                        assertFalse(system.contains("dev.solvit.memory/memory:"))
                        return flowOf(ModelChunk.ToolCalls(listOf(ToolCall("save", "dev_solvit_memory_memory_put",
                            """{"text":"Preferred programming language is Kotlin","category":"preference"}"""))), ModelChunk.Done)
                    }
                    assertTrue(system.contains("Kotlin")); assertTrue(system.contains("dev.solvit.memory/memory:m1"))
                    return flowOf(ModelChunk.Text("Saved your Kotlin preference"), ModelChunk.Done)
                }
            }
            DefaultChatService(store, ToolAgentRuntime(provider, manager.registry.require(Services.Tools), hooks = manager.registry.require(ContextHookServiceKey)), EventBus(), roles)
                .send(first.id, "Remember my preferred programming language is Kotlin", ModelConfig(), allow).toList()
            assertEquals(2, round)
            assertEquals(MessageStatus.COMPLETE, store.history(first.id).last().status)
            manager.shutdown(); store.close()
            manager = PluginManager(scope = scope); store = SqliteChatStore(context); market = setup()
            assertTrue(market.installed.value.single { it.id == "dev.solvit.memory" }.enabled)
            roles = manager.registry.require(RoleServiceKey)
            val tools = manager.registry.require(Services.Tools)
            val hooks = manager.registry.require(ContextHookServiceKey)
            suspend fun probe(expected: Boolean, selected: AgentRole = role) {
                roles.save(selected)
                val session = store.createSession(); roles.select(session.id, selected.id)
                var requests = 0
                val inspector = object : ModelProvider {
                    override val id = "probe"
                    override fun stream(request: ModelRequest): Flow<ModelChunk> {
                        requests++
                        assertEquals(1, request.messages.count { it.role == Role.USER })
                        val system = request.messages.single { it.role == Role.SYSTEM }.content
                        assertEquals(expected, system.contains("Preferred programming language is Kotlin"))
                        if (selected.pluginIds.isEmpty()) assertTrue(request.tools.isEmpty())
                        return flowOf(ModelChunk.Text(if (expected) "You prefer Kotlin" else "No recalled preference"), ModelChunk.Done)
                    }
                }
                DefaultChatService(store, ToolAgentRuntime(inspector, tools, hooks = hooks), EventBus(), roles)
                    .send(session.id, "What is my preferred programming language?", ModelConfig(), allow).toList()
                assertEquals(1, requests) // Recall did not require a model tool call.
            }
            probe(true)
            probe(false, AgentRole("restricted", "Restricted"))
            market.disable("dev.solvit.memory")
            market.configure("dev.solvit.memory", mapOf("autoRecall" to "false"))
            assertFailsWith<IllegalArgumentException> { market.configure("dev.solvit.memory", mapOf("autoRecall" to "invalid")) }
            market.enable("dev.solvit.memory", setOf("storage", "model")); probe(false)
            market.disable("dev.solvit.memory")
            market.configure("dev.solvit.memory", mapOf("autoRecall" to "true"))
            market.enable("dev.solvit.memory", setOf("storage", "model")); probe(true)
            tools.executeCall(ToolCall("delete", "dev_solvit_memory_memory_delete", """{"id":"m1"}"""), allow, role)
            probe(false)
            assertTrue(hooks.prepare(ContextInput(role, "Kotlin")).isEmpty())
            market.disable("dev.solvit.memory")
            assertFalse(tools.descriptors().any { it.pluginId == "dev.solvit.memory" })
            assertTrue(hooks.prepare(ContextInput(role, "Kotlin")).isEmpty())
        } finally {
            manager.shutdown(); store.close(); scope.cancel()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
