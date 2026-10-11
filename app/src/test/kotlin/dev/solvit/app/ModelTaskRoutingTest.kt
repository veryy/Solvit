package dev.solvit.app

import android.app.Application
import dev.solvit.core.*
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.provider.OpenAiProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ModelTaskRoutingTest {
    @Test fun actualHttpToolFollowUpUsesItsOwnEndpointModelAndCredentialAndKeepsTheTranscript()=runBlocking {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("model",0).edit().clear().commit()
        val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val profiles=SettingsModelProfiles(EncryptedSettingsStore(context) { key });profiles.initialize()
        val chat=MockWebServer();val tools=MockWebServer();chat.start();tools.start()
        val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
        try {
            val chatConfig=ModelConfig(chat.url("/v1").toString(),"chat-model","CHAT_KEY")
            profiles.save(ModelProfile("chat","Chat",chatConfig))
            profiles.save(ModelProfile("tools","Tools",ModelConfig(tools.url("/v1").toString(),"tool-model","TOOLS_KEY")))
            profiles.assign(ModelTask.TOOL_FOLLOW_UP,"tools")
            chat.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("""
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"clock","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

            """.trimIndent()+"\n\n"))
            tools.enqueue(MockResponse().setHeader("Content-Type","text/event-stream").setBody("""
                data: {"choices":[{"delta":{"content":"Checked time"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

            """.trimIndent()+"\n\n"))
            val registry=ToolRegistry(EventBus()).apply { register(object: SolvitTool {
                override val descriptor=ToolDescriptor("clock","Clock","{\"type\":\"object\",\"properties\":{}}")
                override suspend fun execute(argumentsJson:String)="UTC clock result"
            }) }
            val allow=object:ToolAuthorization { override suspend fun authorize(descriptor:ToolDescriptor)=true }
            val output=ToolAgentRuntime(OpenAiProvider(client),registry,modelProfiles=profiles).run(
                ModelRequest(chatConfig,listOf(ModelMessage(Role.USER,"Check the time")),authorization=allow)).toList()
            assertEquals("Checked time",output.filterIsInstance<ModelChunk.Text>().joinToString(""){it.value})
            val first=chat.takeRequest(2,TimeUnit.SECONDS)!!;val second=tools.takeRequest(2,TimeUnit.SECONDS)!!
            assertEquals("Bearer CHAT_KEY",first.getHeader("Authorization"))
            assertEquals("Bearer TOOLS_KEY",second.getHeader("Authorization"))
            val body=JSONObject(second.body.readUtf8())
            assertEquals("tool-model",body.getString("model"))
            assertTrue(body.getJSONArray("messages").toString().contains("UTC clock result"))
            assertFalse(body.toString().contains("CHAT_KEY"))
            assertEquals(1,chat.requestCount);assertEquals(1,tools.requestCount)
        } finally { chat.shutdown();tools.shutdown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    }
}
