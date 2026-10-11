package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
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
            manager.load(ToolsPlugin()); manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            val configuration = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { it.toByte() },"AES") }
            val market = AndroidMarketplace(context,manager,client,tools,configuration)
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
}
