package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClockChatIntegrationTest {
    @Test fun enabledClockReachesModelWithoutToolCallsAndHonorsRoleAndDisable() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = PluginManager(scope = scope)
        val client = OkHttpClient()
        val oldZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        try {
            manager.load(ToolsPlugin()); manager.load(ContextHooksPlugin()); manager.enableAll()
            val tools = manager.registry.require(Services.Tools)
            val hooks = manager.registry.require(ContextHookServiceKey)
            val market = AndroidMarketplace(context, manager, client, tools)
            market.restore(); market.enable("dev.solvit.clock", emptySet())
            var expectedClock = true
            val provider = object : ModelProvider {
                override val id = "test"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    assertEquals(expectedClock, request.messages.any { it.content.contains("设备当前时间") && it.content.contains("Asia/Shanghai") })
                    assertEquals(expectedClock, request.tools.any { it.name == "dev_solvit_clock_current_time" })
                    return flowOf(ModelChunk.Text("读取上下文后回答"), ModelChunk.Done)
                }
            }
            val agent = ToolAgentRuntime(provider, tools, hooks = hooks)
            fun request(role: AgentRole? = null) = ModelRequest(ModelConfig(), listOf(ModelMessage(Role.USER, "现在几点？")), agentRole = role)
            agent.run(request()).toList()
            val gate = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            val now = Json.parseToJsonElement(tools.executeCall(ToolCall("time", "dev_solvit_clock_current_time", "{}"), gate)).jsonObject
            assertEquals("Asia/Shanghai", now.getValue("timezone").jsonPrimitive.content)
            assertEquals(-480, now.getValue("timezoneOffsetMinutes").jsonPrimitive.int)
            assertTrue(kotlin.math.abs(Instant.parse(now.getValue("utc").jsonPrimitive.content).epochSecond - Instant.now().epochSecond) < 5)
            expectedClock = false
            agent.run(request(AgentRole("restricted", "Restricted"))).toList()
            market.disable("dev.solvit.clock")
            agent.run(request()).toList()
            assertTrue(hooks.prepare(ContextInput(null, "time")).isEmpty())
        } finally {
            manager.shutdown(); scope.cancel(); TimeZone.setDefault(oldZone)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
