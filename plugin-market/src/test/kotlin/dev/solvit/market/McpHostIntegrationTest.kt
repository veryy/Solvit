package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import dev.solvit.chat.ToolAgentRuntime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class McpHostIntegrationTest {
    private suspend fun infrastructure(manager: PluginManager, scope: CoroutineScope) {
        manager.load(ToolsPlugin())
        val workspaces = dev.solvit.workspace.AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope)
        manager.load(object : SolvitPlugin {
            override val id = "test.mcp-native-contracts"
            override val provides = setOf(WorkspaceServiceKey, LinuxEnvironmentServiceKey, StdioProcessServiceKey)
            override suspend fun enable(context: PluginContext) {
                context.register(WorkspaceServiceKey, workspaces); context.own { workspaces.close() }
                context.register(LinuxEnvironmentServiceKey, object : LinuxEnvironmentService {
                    override val state = kotlinx.coroutines.flow.MutableStateFlow(LinuxEnvironmentState(false, false, "unavailable"))
                    override suspend fun install() = error("HTTPS must not install Linux")
                    override suspend fun installSoftware(software: LinuxSoftware) = error("HTTPS must not install software")
                    override suspend fun cancel() = error("HTTPS must not cancel Linux")
                    override fun command(workspaceDirectory: String, command: String, cwd: String): HostProcessPlan = error("HTTPS must not run Shell")
                    override fun terminal(workspaceDirectory: String): HostProcessPlan = error("HTTPS must not open a terminal")
                    override fun process(workspaceDirectory: String, executable: String, arguments: List<String>, environment: Map<String, String>, cwd: String): HostProcessPlan = error("HTTPS must not start Linux")
                })
                context.register(StdioProcessServiceKey, object : StdioProcessService {
                    override suspend fun open(owner: StdioOwner, plan: HostProcessPlan, directory: String, authorize: suspend () -> Unit): String = error("HTTPS must not start stdio")
                    override suspend fun send(owner: StdioOwner, id: String, frame: String, authorize: suspend () -> Unit) = error("HTTPS must not send stdio")
                    override suspend fun receive(owner: StdioOwner, id: String, authorize: suspend () -> Unit): String = error("HTTPS must not receive stdio")
                    override suspend fun close(owner: StdioOwner, id: String) = error("HTTPS must not close stdio")
                    override suspend fun cancelOwner(pluginId: String) { }
                })
            }
        })
        manager.load(object : SolvitPlugin {
            override val id = "test.mcp-browser"
            override val provides = setOf(WebPageServiceKey)
            override suspend fun enable(context: PluginContext) { context.register(WebPageServiceKey, object : WebPageService {
                override suspend fun visit(url: String): WebPage = error("MCP uses HTTPS RPC, not browser observation")
            }) }
        })
        manager.enableAll()
    }
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
            infrastructure(manager, scope)
            val configuration = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
            val market = AndroidMarketplace(context, manager, client, manager.registry.require(Services.Tools), configuration)
            market.restore()
            assertFailsWith<IllegalStateException> { market.enable("dev.solvit.mcp", emptySet()) }
            market.configure("dev.solvit.mcp", mapOf("url" to server.url("/mcp").toString(), "token" to "private-token"))
            assertFailsWith<IllegalStateException> { market.enable("dev.solvit.mcp", setOf("network")) }
            market.enable("dev.solvit.mcp", setOf("network", "workspace", "shell"))
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
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending = launch(Dispatchers.Default) {
                market.runTool("dev_solvit_mcp_mcp_call", """{"name":"echo","arguments":{"text":"wait"}}""", allow)
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            server.enqueue(MockResponse().setResponseCode(202))
            withTimeout(5_000) { pending.cancelAndJoin() }
            val cancelled = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val notification = Json.parseToJsonElement(cancelled.body.readUtf8()).jsonObject
            assertEquals("notifications/cancelled", notification.getValue("method").jsonPrimitive.content)
            assertEquals(4, notification.getValue("params").jsonObject.getValue("requestId").jsonPrimitive.int)
            context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().putStringSet("grants.dev.solvit.mcp", emptySet()).commit()
            assertFailsWith<Exception> { market.runTool("dev_solvit_mcp_mcp_call", """{"name":"echo","arguments":{}}""", allow) }
            assertEquals(6, server.requestCount)
            context.getSharedPreferences("extension_permissions", Context.MODE_PRIVATE).edit().putStringSet("grants.dev.solvit.mcp", setOf("network", "workspace", "shell")).commit()
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

    @Test fun twoActualHttpsServersWithTheSameToolReturnToTheCorrectAgentFollowUp() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        fun endpoint(name: String) = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer private-$name", request.getHeader("Authorization"))
                    if (request.method == "DELETE") return MockResponse().setResponseCode(200)
                    val rpc = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val method = rpc.getValue("method").jsonPrimitive.content
                    if (method == "notifications/initialized") return MockResponse().setResponseCode(202)
                    val result = when (method) {
                        "initialize" -> buildJsonObject {
                            put("protocolVersion", "2025-11-25"); putJsonObject("capabilities") { putJsonObject("tools") {} }
                            putJsonObject("serverInfo") { put("name", name); put("version", "1") }
                        }
                        "tools/list" -> buildJsonObject { putJsonArray("tools") { add(buildJsonObject { put("name", "echo"); putJsonObject("inputSchema") { put("type", "object") } }) } }
                        "tools/call" -> {
                            assertEquals("session-$name", request.getHeader("Mcp-Session-Id"))
                            assertEquals("echo", rpc.getValue("params").jsonObject.getValue("name").jsonPrimitive.content)
                            buildJsonObject { putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "$name-reply") }) } }
                        }
                        else -> error("Unexpected MCP method")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").apply {
                        if (method == "initialize") setHeader("Mcp-Session-Id", "session-$name")
                        setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", rpc.getValue("id")); put("result", result) }.toString())
                    }
                }
            }
            start()
        }
        val docs = endpoint("docs"); val code = endpoint("code")
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .followRedirects(false).followSslRedirects(false).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val manager = PluginManager(scope = scope)
        val configuration = EncryptedPluginConfiguration(context) { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
        try {
            infrastructure(manager, scope)
            val tools = manager.registry.require(Services.Tools)
            val market = AndroidMarketplace(context, manager, client, tools, configuration)
            market.restore()
            market.configure("dev.solvit.mcp", mapOf("connections" to buildJsonArray {
                for ((name, server) in listOf("docs" to docs, "code" to code)) add(buildJsonObject {
                    put("name", name); put("url", server.url("/mcp").toString()); put("token", "private-$name")
                })
            }.toString()))
            market.enable("dev.solvit.mcp", setOf("network", "workspace", "shell"))
            var requests = 0; var approvals = 0
            val provider = object : ModelProvider {
                override val id = "dual-mcp-fixture"
                override fun stream(request: ModelRequest) = flow<ModelChunk> {
                    requests++
                    if (requests == 1) {
                        val schema = Json.parseToJsonElement(request.tools.single { it.name == "dev_solvit_mcp_mcp_call" }.inputSchema).jsonObject
                        assertTrue(schema.getValue("properties").jsonObject.containsKey("server"))
                        emit(ModelChunk.ToolCalls(listOf("docs", "code").map { name -> ToolCall("call-$name", "dev_solvit_mcp_mcp_call",
                            """{"server":"$name","name":"echo","arguments":{}}""") }))
                    } else {
                        assertEquals(2, requests)
                        val returned = request.messages.filter { it.role == Role.TOOL }
                        assertEquals(2, returned.size)
                        for (name in listOf("docs", "code")) {
                            val message = returned.single { it.toolCallId == "call-$name" }
                            assertTrue(message.content.contains("$name-reply")); assertTrue(message.content.contains("\"connection\":\"$name\""))
                            assertFalse(message.content.contains("private-$name"))
                        }
                        emit(ModelChunk.Text("Both servers returned their own result."))
                    }
                    emit(ModelChunk.Done)
                }
            }
            val authorization = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor): Boolean { approvals++; return true } }
            val chunks = withTimeout(20_000) { ToolAgentRuntime(provider, tools).run(ModelRequest(ModelConfig(),
                listOf(ModelMessage(Role.USER, "Call echo on both configured servers")), authorization = authorization)).toList() }
            assertEquals(2, approvals); assertEquals(2, requests)
            assertTrue(chunks.any { it == ModelChunk.Text("Both servers returned their own result.") })
            assertEquals(4, docs.requestCount); assertEquals(4, code.requestCount)
            market.disable("dev.solvit.mcp")
            assertEquals(5, docs.requestCount); assertEquals(5, code.requestCount)
            market.uninstall("dev.solvit.mcp")
        } finally { manager.shutdown(); scope.cancel(); docs.shutdown(); code.shutdown(); configuration.remove("dev.solvit.mcp"); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
