package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebSearchChatIntegrationTest {
    private fun browserPlugin(service: WebPageService) = object : SolvitPlugin {
        override val id = "fixture.web"
        override val provides = setOf(WebPageServiceKey)
        override suspend fun enable(context: PluginContext) { context.register(WebPageServiceKey, service) }
    }
    private fun blockedBrowser() = object : WebPageService {
        override suspend fun visit(url: String): WebPage = error("Browser unavailable in HTTP fixture")
    }

    @Test fun bundledSearchUpgradePreservesActivationAndWorksWithoutProviderConfiguration() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val path = java.io.File(context.filesDir, "extensions/dev.solvit.web-search.solvit.json")
        path.parentFile!!.mkdirs()
        path.writeBytes(javaClass.classLoader!!.getResourceAsStream("web-search-1.0.0.solvit.json")!!.use { it.readBytes() })
        context.getSharedPreferences("extension_permissions",0).edit()
            .putBoolean("seeded.dev.solvit.web-search.solvit.json", true)
            .putBoolean("enabled.dev.solvit.web-search", true)
            .putStringSet("grants.dev.solvit.web-search", setOf("network")).commit()
        var searched = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val outgoing = chain.request()
            assertEquals("cn.bing.com", outgoing.url.host)
            assertEquals("Solvit", outgoing.url.queryParameter("q"))
            assertNull(outgoing.header("Authorization")); searched = true
            okhttp3.Response.Builder().request(outgoing).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("<rss><channel><item><title>Solvit source</title><link>https://github.com/veryy/Solvit</link><description>Actual source link</description></item></channel></rss>".toResponseBody()).build()
        }.build()
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val manager = PluginManager(scope=scope)
        try {
            manager.load(ToolsPlugin()); manager.load(browserPlugin(blockedBrowser())); manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            val config = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32), "AES") }
            val market = AndroidMarketplace(context, manager, client, tools, config, webPages = manager.registry.require(WebPageServiceKey))
            market.restore()
            val upgraded = market.installed.value.single { it.id == "dev.solvit.web-search" }
            assertEquals("1.2.2", upgraded.version); assertTrue(upgraded.enabled)
            assertTrue(market.configuration(upgraded.id).isEmpty())
            val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            val result = tools.executeCall(ToolCall("search-default", "dev_solvit_web_search_search", """{"query":"Solvit"}"""), allow)
            assertTrue(searched); assertTrue(result.contains("https://github.com/veryy/Solvit"))
            assertFalse(result.contains("API Key"))
        } finally {
            manager.shutdown(); scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    @Test fun actualJsSearchUsesEncryptedConfigAndFeedsHttpsSourcesBackWithoutLeakingKeyToPages() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(),false); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(),clientTls.trustManager).followRedirects(false).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope=scope)
        try {
            manager.load(ToolsPlugin()); manager.load(browserPlugin(blockedBrowser())); manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            val configuration = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { it.toByte() },"AES") }
            val market = AndroidMarketplace(context,manager,client,tools,configuration, webPages = manager.registry.require(WebPageServiceKey))
            market.restore()
            market.configure("dev.solvit.web-search",mapOf("provider" to "tavily","endpoint" to server.url("/search").toString(),"apiKey" to "search-fixture-key"))
            market.enable("dev.solvit.web-search",setOf("network"))
            val page = server.url("/article").toString()
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(buildJsonObject {
                put("results",buildJsonArray { add(buildJsonObject { put("title","Verified source"); put("url",page); put("content","Source summary") }) })
            }.toString()))
            server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("<html><script>ignored-script</script><p>Verified page text</p></html>"))
            var round = 0
            val provider = object : ModelProvider {
                override val id = "fixture"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    assertEquals(setOf("dev_solvit_web_search_search","dev_solvit_web_search_read_page"),request.tools.map { it.name }.toSet())
                    assertEquals(1,request.messages.count { it.role == Role.SYSTEM })
                    if (round++ == 0) return flowOf(ModelChunk.ToolCalls(listOf(
                        ToolCall("search-call","dev_solvit_web_search_search","""{"query":"Solvit"}"""),
                        ToolCall("page-call","dev_solvit_web_search_read_page",buildJsonObject { put("url",page) }.toString()))),ModelChunk.Done)
                    val results = request.messages.filter { it.role == Role.TOOL }
                    assertEquals(2,results.size)
                    assertTrue(results[0].content.contains(page)); assertTrue(results[1].content.contains("Verified page text"))
                    assertFalse(results.any { "search-fixture-key" in it.content || "ignored-script" in it.content })
                    return flowOf(ModelChunk.Text("Answer with source: $page"),ModelChunk.Done)
                }
            }
            val role = AgentRole("researcher","Researcher",pluginIds=setOf("dev.solvit.web-search"))
            val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            val chunks = ToolAgentRuntime(provider,tools).run(ModelRequest(ModelConfig(),listOf(ModelMessage(Role.USER,"search Solvit")),authorization=allow,agentRole=role)).toList()
            assertTrue(chunks.filterIsInstance<ModelChunk.Text>().single().value.contains(page))
            val search = assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))
            assertEquals("Bearer search-fixture-key",search.getHeader("Authorization"))
            assertEquals("Solvit",Json.parseToJsonElement(search.body.readUtf8()).jsonObject.getValue("query").jsonPrimitive.content)
            assertNull(assertNotNull(server.takeRequest(5,TimeUnit.SECONDS)).getHeader("Authorization"))
            assertFailsWith<ToolDeniedException> { tools.executeCall(ToolCall("blocked","dev_solvit_web_search_search","""{"query":"blocked"}"""),allow,AgentRole("restricted","Restricted")) }
            assertEquals(2,server.requestCount)
        } finally {
            manager.shutdown(); scope.cancel(); server.shutdown()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
    @Test fun actualJsBrowserSearchAndPageTextReachModelAndHonorAuthorization() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val visits = mutableListOf<String>()
        val source = "https://example.com/solvit"
        val browser = object : WebPageService {
            override suspend fun visit(url: String): WebPage {
                visits += url
                return if (url.startsWith("https://www.bing.com/search?"))
                    WebPage(url, "Search", "", listOf(WebPageLink(source, "Solvit source", "Relevant source")))
                else { assertEquals(source, url); WebPage(source, "Solvit article", "Observed dynamic page evidence") }
            }
        }
        try {
            manager.load(ToolsPlugin()); manager.load(browserPlugin(browser)); manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            val market = AndroidMarketplace(context, manager, client, tools, webPages = browser)
            market.restore(); market.enable("dev.solvit.web-search", setOf("network"))
            val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            val role = AgentRole("research", "Research", pluginIds = setOf("dev.solvit.web-search"))
            var round = 0
            val provider = object : ModelProvider {
                override val id = "fixture"
                override fun stream(request: ModelRequest): Flow<ModelChunk> = when (round++) {
                    0 -> flowOf(ModelChunk.ToolCalls(listOf(ToolCall("search", "dev_solvit_web_search_search", """{"query":"Solvit"}"""))), ModelChunk.Done)
                    1 -> {
                        assertTrue(request.messages.last().content.contains(source))
                        flowOf(ModelChunk.ToolCalls(listOf(ToolCall("page", "dev_solvit_web_search_read_page", """{"url":"$source"}"""))), ModelChunk.Done)
                    }
                    else -> {
                        assertTrue(request.messages.last().content.contains("Observed dynamic page evidence"))
                        flowOf(ModelChunk.Text("Verified source: $source"), ModelChunk.Done)
                    }
                }
            }
            val chunks = ToolAgentRuntime(provider, tools).run(ModelRequest(ModelConfig(), listOf(ModelMessage(Role.USER, "Search Solvit and read its source")), authorization = allow, agentRole = role)).toList()
            assertEquals(3, round); assertEquals(2, visits.size)
            assertTrue(chunks.filterIsInstance<ModelChunk.Text>().single().value.contains(source))
            val deny = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = false }
            assertFailsWith<ToolDeniedException> { tools.executeCall(ToolCall("denied", "dev_solvit_web_search_read_page", """{"url":"$source"}"""), deny, role) }
            assertFailsWith<ToolDeniedException> { tools.executeCall(ToolCall("role-denied", "dev_solvit_web_search_read_page", """{"url":"$source"}"""), allow, AgentRole("restricted", "Restricted")) }
            assertEquals(2, visits.size)
            market.disable("dev.solvit.web-search")
            assertFalse(tools.descriptors().any { it.pluginId == "dev.solvit.web-search" })
        } finally {
            manager.shutdown(); scope.cancel(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

}
