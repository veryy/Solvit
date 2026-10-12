package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarketplacePagesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun payload(title: String = "Notes", network: Boolean = false): ByteArray = buildJsonObject {
        putJsonObject("manifest") {
            put("id", "test.notes"); put("name", "Notes"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", 9)
            put("permissions", JsonArray((if (network) listOf("storage", "network") else listOf("storage")).map(::JsonPrimitive)))
            put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
            putJsonArray("pages") { add(buildJsonObject { put("id", "main"); put("title", "Notes") }) }
        }
        put("source", """
            globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
              if(input.event){
                ${if (network) "await ctx.http.request({url:'https://example.com/page',timeoutMs:30000});" else ""}
                await ctx.storage.set('note',input.event.fields.note);
              }
              return {title:${JsonPrimitive(title)},nodes:[{id:'note',kind:'field',label:'Note',value:await ctx.storage.get('note')??''}],
                footer:[{id:'save',kind:'button',text:'Save',action:'save'}]};
            }}};
        """.trimIndent())
    }.toString().toByteArray()

    private suspend fun fixture(client: OkHttpClient = OkHttpClient(), block: suspend (AndroidMarketplace, PluginManager) -> Unit) {
        context.getSharedPreferences("extension_data.test.notes", 0).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val market = AndroidMarketplace(context, manager, client, run {
            manager.load(ToolsPlugin())
            manager.load(object : SolvitPlugin {
                override val id = "test.browser"
                override val provides = setOf(WebPageServiceKey)
                override suspend fun enable(context: PluginContext) { context.register(WebPageServiceKey, object : WebPageService {
                    override suspend fun visit(url: String): WebPage = error("This test must use HTTP, not browser observation")
                }) }
            })
            manager.enableAll(); manager.registry.require(Services.Tools)
        })
        try { block(market, manager) }
        finally {
            runCatching { market.uninstall("test.notes") }; manager.shutdown(); scope.cancel()
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun pagesRequireEnableAndPersistThroughServiceWithStaleRevisionRejected() = runBlocking<Unit> {
        fixture { market, _ ->
            market.installLocal(payload())
            assertEquals(listOf(ExtensionPage("main", "Notes")), market.installed.value.single().pages)
            assertFails { market.openPage("test.notes", "main", "en") }
            market.enable("test.notes", setOf("storage"))
            val session = market.openPage("test.notes", "main", "en")
            try {
                val first = session.open()
                val updated = session.dispatch(first.revision, "save", fields = mapOf("note" to "stored note"))
                assertEquals(first.revision + 1, updated.revision)
                assertFails { session.dispatch(first.revision, "save", fields = mapOf("note" to "stale overwrite")) }
            } finally { session.close() }
            val reopened = market.openPage("test.notes", "main", "ja")
            try {
                val json = Json.parseToJsonElement(reopened.open().json).jsonObject
                assertEquals(JsonPrimitive("stored note"), json.getValue("nodes").jsonArray[0].jsonObject["value"])
            } finally { reopened.close() }
        }
    }

    @Test fun replacingSameVersionClosesOldPageAndDoesNotCarryGrants() = runBlocking<Unit> {
        fixture { market, _ ->
            market.installLocal(payload()); market.enable("test.notes", setOf("storage"))
            val old = market.openPage("test.notes", "main", "en"); val initial = old.open()
            market.installLocal(payload("Changed package"))
            assertTrue(old.closed.value)
            assertFails { old.dispatch(initial.revision, "save", fields = mapOf("note" to "forbidden")) }
            assertFalse(market.installed.value.single().enabled)
            assertTrue(context.getSharedPreferences("extension_permissions", 0).getStringSet("grants.test.notes", emptySet()).orEmpty().isEmpty())
            market.enable("test.notes", setOf("storage"))
            val replacement = market.openPage("test.notes", "main", "en")
            try { assertEquals(JsonPrimitive("Changed package"), Json.parseToJsonElement(replacement.open().json).jsonObject["title"]) }
            finally { replacement.close(); old.close() }
        }
    }

    @Test fun disablingCancelsRealPendingPageHttpAndPreventsSubsequentWrite() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(
            chain.request().newBuilder().url(server.url("/page")).build()) }.build()
        try {
            fixture(client) { market, _ ->
                market.installLocal(payload(network = true)); market.enable("test.notes", setOf("storage", "network"))
                val session = market.openPage("test.notes", "main", "en"); val initial = session.open()
                try {
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val pending = async { runCatching { session.dispatch(initial.revision, "save", fields = mapOf("note" to "not saved")) } }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(10, TimeUnit.SECONDS) })
                    withTimeout(5000) { market.disable("test.notes"); assertTrue(pending.await().isFailure) }
                    assertTrue(session.closed.value)
                    assertNull(context.getSharedPreferences("extension_data.test.notes", 0).getString("note", null))
                } finally { session.close() }
            }
        } finally { server.shutdown() }
    }
}
