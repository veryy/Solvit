package dev.solvit.provider

import dev.solvit.core.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class OpenAiProviderTest {
    private suspend fun withServer(body: String, status: Int = 200, block: suspend (OpenAiProvider, ModelRequest, MockWebServer) -> Unit) {
        val server = MockWebServer()
        val client = OkHttpClient()
        server.start()
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "text/event-stream").setBody(body))
        try {
            block(OpenAiProvider(client), ModelRequest(ModelConfig(server.url("/v1").toString(), "test-model", "private-key"), listOf(ModelMessage(Role.USER, "hello"))), server)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun streamsUnicodeIgnoresHeartbeatsAndSendsCorrectRequest() = runTest {
        withServer(": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"！\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") { provider, request, server ->
            assertEquals(listOf(ModelChunk.Text("你好"), ModelChunk.Text("！"), ModelChunk.Done), provider.stream(request).toList())
            val sent = server.takeRequest()
            assertEquals("/v1/chat/completions", sent.path)
            assertEquals("Bearer private-key", sent.getHeader("Authorization"))
            assertTrue(sent.body.readUtf8().contains("test-model"))
        }
    }

    @Test fun prematureDisconnectIsNotSuccessfulCompletion() = runTest {
        withServer("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n") { provider, request, _ ->
            assertFailsWith<ModelException> { provider.stream(request).toList() }
        }
    }

    @Test fun httpErrorsDoNotExposeRemoteBodyOrCredentials() = runTest {
        withServer("private-key server debug details", 401) { provider, request, _ ->
            val failure = assertFailsWith<ModelException> { provider.stream(request).toList() }
            assertFalse(failure.message.orEmpty().contains("private-key"))
        }
    }

    @Test fun dispatchesFinalEventWithoutTrailingBlankLine() = runTest {
        withServer("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}") { provider, request, _ ->
            assertEquals(listOf(ModelChunk.Text("ok"), ModelChunk.Done), provider.stream(request).toList())
        }
    }

    @Test fun outputLimitFailsRatherThanSavingATruncatedReplyAsComplete() = runTest {
        withServer("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n") { provider, request, _ ->
            assertFailsWith<ModelException> { provider.stream(request).toList() }
        }
    }

    @Test fun assemblesInterleavedToolFragmentsAndSerializesResults() = runTest {
        val events = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"clock","arguments":"{\"tz\":"}},{"index":1,"id":"call_b","type":"function","function":{"name":"echo","arguments":"{"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"}"}},{"index":0,"function":{"arguments":"\"UTC\"}"}}]},"finish_reason":"tool_calls"}]}""",
        ).joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"
        withServer(events) { provider, request, server ->
            val call = ToolCall("previous", "clock", "{}")
            val next = request.copy(
                tools = listOf(ToolDescriptor("clock", "Clock", """{"type":"object"}""")),
                messages = request.messages + ModelMessage(Role.ASSISTANT, "", listOf(call)) +
                    ModelMessage(Role.TOOL, "result", toolCallId = call.id),
            )
            assertEquals(listOf(
                ModelChunk.ToolCalls(listOf(ToolCall("call_a", "clock", """{"tz":"UTC"}"""), ToolCall("call_b", "echo", "{}"))),
                ModelChunk.Done,
            ), provider.stream(next).toList())
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8()).toString()
            assertTrue(payload.contains("\"tool_call_id\":\"previous\""))
            assertTrue(payload.contains("\"tool_choice\":\"auto\""))
        }
    }

    @Test fun partialOrMalformedToolArgumentsNeverReachExecution() = runTest {
        val call = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call","type":"function","function":{"name":"clock","arguments":"{"}}]}}]}"""
        for (ending in listOf("", "\n\ndata: [DONE]\n\n", "\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n")) {
            withServer(call + ending) { provider, request, _ ->
                assertFailsWith<ModelException> { provider.stream(request).toList() }
            }
        }
    }
}
