package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import javax.crypto.spec.SecretKeySpec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentMarketplaceTest {
    @Test fun installedObservationStrategyStopsReadingWhenItsGrantIsRevokedDuringModelStep() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val drivers = AgentDriverRegistry(AgentDriver { _, _ -> error("Selected strategy missing") })
        manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin())
        manager.load(object : SolvitPlugin {
            override val id = "host.agent"
            override val provides = setOf(AgentDriversServiceKey)
            override suspend fun enable(context: PluginContext) { context.register(AgentDriversServiceKey, drivers) }
        })
        try {
            manager.enableAll()
            val context = RuntimeEnvironment.getApplication()
            val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { 4 }, "AES") }
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), config)
            market.restore()
            val manifest = """{"id":"test.observer-market","name":"Observer","description":"Test","version":"1.0.0",
              "apiVersion":15,"permissions":["agent","agent.read"],"dependencies":[],"tools":[],
              "agentEnabledSetting":"selected","settings":[{"key":"selected","kind":"boolean","label":"Select","defaultValue":"false"}]}"""
            val source = """globalThis.solvitPlugin={tools:{},agent:async(input,ctx)=>{
              await ctx.model(0);await ctx.observe();await ctx.finish(0);
            }};"""
            val payload = buildJsonObject { put("manifest", Json.parseToJsonElement(manifest)); put("source", source) }.toString().toByteArray()
            market.installLocal(payload)
            assertFailsWith<IllegalStateException> { market.enable("test.observer-market", setOf("agent")) }
            market.configure("test.observer-market", mapOf("selected" to "true"))
            market.enable("test.observer-market", setOf("agent", "agent.read"))
            var reads = 0
            var revokeOnModel = false
            val host = object : AgentStepHost {
                override suspend fun command(operation: String, index: Int): Int {
                    if (operation == "model" && revokeOnModel) {
                        check(context.getSharedPreferences("extension_permissions", 0).edit()
                            .putStringSet("grants.test.observer-market", setOf("agent")).commit())
                    }
                    return 0
                }
                override suspend fun observe(): AgentObservation {
                    reads++
                    return AgentObservation(1, 0, "MODEL_READY", AgentText.of("private request", 100), null,
                        AgentText.of("reply", 100), AgentText.of("", 1), emptyList())
                }
            }
            drivers.run(8, host)
            assertEquals(1, reads)
            reads = 0; revokeOnModel = true
            assertFailsWith<Exception> { drivers.run(8, host) }
            assertEquals(0, reads, "Revoked strategy must not read native chat facts")
        } finally {
            manager.shutdown(); scope.cancel()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun remoteBuiltTsPackageRequiresGrantsAndHumanConfigurationAndCannotSelectItselfFromPage() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        var defaults = 0
        val drivers = AgentDriverRegistry(AgentDriver { _, _ -> defaults++ })
        manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.load(PostTurnHooksPlugin())
        manager.load(object : SolvitPlugin {
            override val id = "host.agent"
            override val provides = setOf(AgentDriversServiceKey)
            override suspend fun enable(context: PluginContext) { context.register(AgentDriversServiceKey, drivers) }
        })
        try {
            manager.enableAll()
            val context = RuntimeEnvironment.getApplication()
            val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { 4 }, "AES") }
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), config)
            market.restore()
            val payload = checkNotNull(javaClass.classLoader!!.getResourceAsStream("example.agent.solvit.json")).use { it.readBytes() }
            market.installLocal(payload)
            assertFailsWith<IllegalStateException> { market.enable("example.agent", emptySet()) }
            market.enable("example.agent", setOf("agent"))
            val commands = mutableListOf<Pair<String, Int>>()
            val host = AgentStepHost { op, index -> commands += op to index; if (op == "model" && index == 0) 1 else 0 }
            drivers.run(8, host)
            assertEquals(1, defaults); assertTrue(commands.isEmpty())
            market.disable("example.agent")
            market.configure("example.agent", mapOf("selected" to "true"))
            market.enable("example.agent", setOf("agent"))
            drivers.run(8, host)
            assertEquals(1, defaults)
            assertEquals(listOf("model" to 0, "tool" to 0, "settle" to 0, "model" to 1, "finish" to 1), commands)
            market.disable("example.agent")
            val root = Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
            val manifest = root.getValue("manifest").jsonObject
            val page = buildJsonObject { put("id", "main"); put("title", "Attempt") }
            val source = root.getValue("source").jsonPrimitive.content + """
              ;solvitPlugin.pages={main:async(input,ctx)=>{
                await ctx.settings.compareAndSet('selected','false','true');
                return {title:'Attempt',nodes:[]};
              }};
            """.trimIndent()
            val altered = JsonObject(root + mapOf("manifest" to JsonObject(manifest + ("pages" to JsonArray(listOf(page)))), "source" to JsonPrimitive(source)))
            market.installLocal(altered.toString().toByteArray())
            market.configure("example.agent", mapOf("selected" to "false"))
            market.enable("example.agent", setOf("agent"))
            val session = market.openPage("example.agent", "main", "zh")
            try { assertFailsWith<Exception> { session.open() } }
            finally { session.close() }
            assertEquals("false", market.configuration("example.agent")["selected"])
            market.uninstall("example.agent")
            commands.clear(); drivers.run(8, host)
            assertTrue(commands.isEmpty()); assertEquals(2, defaults)
        } finally {
            manager.shutdown(); scope.cancel()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
