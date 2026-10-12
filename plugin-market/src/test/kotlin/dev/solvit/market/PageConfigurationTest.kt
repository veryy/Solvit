package dev.solvit.market

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PageConfigurationTest {
    @Test fun pageWritesAreEncryptedAtomicPluginScopedAndUnavailableToModelTools() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        val id = "test.page-config"
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", id); put("name", "Configuration"); put("description", "Fixture"); put("version", "1.0.0"); put("apiVersion", 10)
                put("permissions", JsonArray(emptyList())); put("dependencies", JsonArray(emptyList()))
                putJsonArray("settings") { add(buildJsonObject { put("key", "connections"); put("label", "Connections"); put("kind", "secret"); put("pageManaged", true) }) }
                putJsonArray("pages") { add(buildJsonObject { put("id", "main"); put("title", "Connections") }) }
                putJsonArray("tools") { add(buildJsonObject { put("name", "write"); put("description", "Must be rejected"); putJsonObject("inputSchema") { put("type", "object") } }) }
            }
            put("source", """
              let expected;
              globalThis.solvitPlugin={tools:{write:async(args,ctx)=>ctx.settings.compareAndSet('connections',null,'forbidden')},
                pages:{main:async(input,ctx)=>{
                  let result='ready';
                  if(!input.event)expected=await ctx.settings.get('connections');
                  else result=String(await ctx.settings.compareAndSet(input.event.fields.key,expected,input.event.fields.draft));
                  return {title:'Connections',nodes:[{id:'key',kind:'field',label:'Key',value:'connections'},
                    {id:'draft',kind:'field',label:'Draft',value:''},{id:'result',kind:'text',text:result}],
                    footer:[{id:'save',kind:'button',text:'Save',action:'save'}]};
                }}};
            """.trimIndent())
        }.toString().toByteArray()
        try {
            manager.load(ToolsPlugin()); manager.enableAll()
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), config)
            market.installLocal(payload); market.enable(id, emptySet())
            val first = market.openPage(id, "main", "en"); val stale = market.openPage(id, "main", "en")
            try {
                val a = first.open(); val b = stale.open()
                first.dispatch(a.revision, "save", fields = mapOf("key" to "connections", "draft" to "private-page-token"))
                assertEquals("private-page-token", config.read(id)["connections"])
                assertFalse(context.getSharedPreferences("extension_configuration", 0).getString(id, "")!!.contains("private-page-token"))
                val rejected = stale.dispatch(b.revision, "save", fields = mapOf("key" to "connections", "draft" to "stale token"))
                assertTrue(rejected.json.contains("false")); assertEquals("private-page-token", config.read(id)["connections"])
                assertFails { stale.dispatch(rejected.revision, "save", fields = mapOf("key" to "otherPlugin", "draft" to "forbidden")) }
                val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
                assertFails { market.runTool("test_page_config_write", "{}", allow) }
                assertEquals("private-page-token", config.read(id)["connections"])
                market.disable(id)
                assertFails { first.dispatch(a.revision + 1, "save", fields = mapOf("key" to "connections", "draft" to "revoked")) }
            } finally { first.close(); stale.close() }
            market.uninstall(id)
        } finally { manager.shutdown(); scope.cancel(); config.remove(id); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
