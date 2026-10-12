package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuiltinMemoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
    private fun result(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun api10CatalogInstallsOfflineHtmlWithoutEnablingOrGrantingIt() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val server = MockWebServer(); server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request().url
            chain.proceed(chain.request().newBuilder().url(server.url(original.encodedPath +
                (original.encodedQuery?.let { "?$it" } ?: ""))).build())
        }.build()
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.enableAll()
            val payload = context.assets.open("builtin-plugins/dev.solvit.memory.solvit.json").use { it.readBytes() }
            val manifest = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject.getValue("manifest").jsonObject
            val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            val path = "/api/v1/packages/dev.solvit.memory/1.4.0"
            val catalog = buildJsonObject {
                put("apiVersion", 1)
                put("plugins", JsonArray(listOf(JsonObject(manifest + mapOf("sha256" to JsonPrimitive(sha),
                    "bytes" to JsonPrimitive(payload.size), "downloadPath" to JsonPrimitive(path))))))
            }
            server.enqueue(MockResponse().setBody(catalog.toString()))
            server.enqueue(MockResponse().setBody(payload.toString(Charsets.UTF_8)))
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools))
            market.restore()
            val entry = market.catalog().single()
            assertEquals(setOf("storage", "model"), entry.permissions.toSet())
            market.install(entry)
            assertEquals("/api/v1/plugins?hostApi=11", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertEquals(path, server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            val installed = market.installed.value.single { it.id == "dev.solvit.memory" }
            assertEquals("1.4.0", installed.version); assertFalse(installed.enabled)
            assertEquals(listOf(ExtensionPage("main", "记忆"), ExtensionPage("tasks", "自动记忆任务")), installed.pages)
            assertTrue(context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE)
                .getStringSet("grants.dev.solvit.memory", emptySet()).orEmpty().isEmpty())
        } finally {
            manager.shutdown(); scope.cancel(); server.shutdown()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun offlineSeedNeedsPermissionAndPersistsMemoryThroughApplicationRestart() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = OkHttpClient()
        suspend fun setup(): Pair<PluginManager, AndroidMarketplace> {
            val manager = PluginManager(scope = scope)
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin()); manager.enableAll()
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools))
            market.restore()
            return manager to market
        }
        var manager: PluginManager? = null
        try {
            val initial = setup(); manager = initial.first
            val market = initial.second
            assertEquals(setOf("dev.solvit.memory", "dev.solvit.clock", "dev.solvit.mcp", "dev.solvit.skills", "dev.solvit.workspace", "dev.solvit.authoring", "dev.solvit.terminal", "dev.solvit.web-search"), market.installed.value.map { it.id }.toSet())
            assertTrue(market.installed.value.none { it.enabled })
            // Installed, never-loaded packages have no PluginManager row to disable.
            market.disable("dev.solvit.memory")
            market.disable("dev.solvit.memory")
            assertTrue(market.installed.value.none { it.enabled })
            assertTrue(context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE)
                .getStringSet("grants.dev.solvit.memory", emptySet()).orEmpty().isEmpty())
            assertFailsWith<IllegalStateException> { market.enable("dev.solvit.memory", emptySet()) }
            market.enable("dev.solvit.memory", setOf("storage", "model"))
            val saved = result(market.runTool("dev_solvit_memory_memory_put", """{"text":"喜欢星河旅行","category":"preference"}""", allow))
            val id = saved.getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
            market.runTool("dev_solvit_memory_memory_profile", """{"key":"城市","value":"上海"}""", allow)
            market.runTool("dev_solvit_memory_memory_document", """{"id":"guide","title":"旅行指南","text":"星河旅行需要提前订票"}""", allow)
            manager.shutdown(); manager = null
            val restarted = setup(); manager = restarted.first
            val restored = restarted.second
            assertTrue(restored.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
            val search = result(restored.runTool("dev_solvit_memory_memory_search", """{"query":"星河旅行"}""", allow))
            assertEquals(setOf("memory", "document"), search.getValue("results").jsonArray.map { it.jsonObject.getValue("kind").jsonPrimitive.content }.toSet())
            val db = result(restored.runTool("dev_solvit_memory_memory_export", "{}", allow))
            assertEquals("上海", db.getValue("profile").jsonObject.getValue("城市").jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals(id, db.getValue("facts").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            // Revocation is enforced by the native bridge even for an existing engine.
            context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().putStringSet("grants.dev.solvit.memory", emptySet()).commit()
            assertFailsWith<Exception> { restored.runTool("dev_solvit_memory_memory_export", "{}", allow) }
            restored.disable("dev.solvit.memory")
            assertFailsWith<IllegalStateException> { restored.runTool("dev_solvit_memory_memory_export", "{}", allow) }
            restored.uninstall("dev.solvit.memory")
            manager.shutdown(); manager = null
            val final = setup(); manager = final.first
            assertFalse(final.second.installed.value.any { it.id == "dev.solvit.memory" })
            assertNotNull(context.getSharedPreferences("extension_data.dev.solvit.memory", Context.MODE_PRIVATE).getString("memory.v1", null))
        } finally {
            manager?.shutdown(); scope.cancel()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
