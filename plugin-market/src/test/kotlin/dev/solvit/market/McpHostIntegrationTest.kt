package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.*
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
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class McpHostIntegrationTest {
    @Test fun encryptedConfigurationDrivesRealHttpsMcpAndRevocationStopsRequests() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        try {
            manager.load(ToolsPlugin()); manager.enableAll()
            val configuration = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), configuration)
            market.restore()
            assertFailsWith<IllegalStateException> { market.enable("dev.solvit.mcp", setOf("network")) }
            market.configure("dev.solvit.mcp", mapOf("url" to server.url("/mcp").toString(), "token" to "private-token"))
            market.enable("dev.solvit.mcp", setOf("network"))
            assertFailsWith<IllegalStateException> { market.configure("dev.solvit.mcp", mapOf("url" to server.url("/other").toString())) }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setHeader("Mcp-Session-Id", "session-id").setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n"))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"真实 HTTPS 回传"}]}}"""))
            val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            val result = withTimeout(15_000) { market.runTool("dev_solvit_mcp_mcp_call", """{"name":"echo","arguments":{"text":"你好"}}""", allow) }
            assertEquals("真实 HTTPS 回传", Json.parseToJsonElement(result).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            val initialize = server.takeRequest()
            assertEquals("POST", initialize.method); assertEquals("/mcp", initialize.path)
            assertEquals("Bearer private-token", initialize.getHeader("Authorization"))
            assertTrue(initialize.getHeader("Accept")!!.contains("text/event-stream"))
            val notified = server.takeRequest()
            assertEquals("session-id", notified.getHeader("Mcp-Session-Id"))
            assertTrue(notified.body.readUtf8().contains("notifications/initialized"))
            server.takeRequest() // tools/list
            val called = server.takeRequest()
            assertEquals("2025-11-25", called.getHeader("MCP-Protocol-Version"))
            assertEquals("你好", Json.parseToJsonElement(called.body.readUtf8()).jsonObject.getValue("params").jsonObject.getValue("arguments").jsonObject.getValue("text").jsonPrimitive.content)
            context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().putStringSet("grants.dev.solvit.mcp", emptySet()).commit()
            assertFailsWith<Exception> { market.runTool("dev_solvit_mcp_mcp_call", """{"name":"echo","arguments":{}}""", allow) }
            assertEquals(4, server.requestCount)
            context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().putStringSet("grants.dev.solvit.mcp", setOf("network")).commit()
            server.enqueue(MockResponse().setResponseCode(405))
            market.disable("dev.solvit.mcp")
            assertEquals("DELETE", server.takeRequest().method)
            market.uninstall("dev.solvit.mcp")
            assertTrue(configuration.read("dev.solvit.mcp").isEmpty())
        } finally {
            manager.shutdown(); scope.cancel(); server.shutdown()
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
