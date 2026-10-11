package dev.solvit.provider

import dev.solvit.core.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class OpenAiProviderTest {
    private suspend fun withServer(body: String, status: Int = 200, contentType: String = "text/event-stream", block: suspend (OpenAiProvider, ModelRequest, MockWebServer) -> Unit) {
        val server = MockWebServer()
        val client = OkHttpClient()
        server.start()
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", contentType).setBody(body))
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

    @Test fun compatibleGatewayEmptyFinishReasonDoesNotEndOrRejectTheStream() = runTest {
        val frames = listOf(
            """{"choices":[{"delta":{"content":"","extra_fields":null,"function_call":null,"refusal":"","role":"assistant","tool_calls":[]},"finish_reason":"","index":0,"logprobs":null}],"object":"chat.completion.chunk","usage":null}""",
            """{"choices":[{"delta":{"content":"你好","tool_calls":[]},"finish_reason":"","index":0}]}""",
            """{"choices":[{"delta":{"content":"！"},"finish_reason":"stop","index":0}]}""",
        ).joinToString("\n\n") { "data: $it" } + "\n\ndata: [DONE]\n\n"
        withServer(frames) { provider, request, _ ->
            assertEquals(listOf(ModelChunk.Text("你好"), ModelChunk.Text("！"), ModelChunk.Done), provider.stream(request).toList())
        }
    }
    @Test fun emptyFinishReasonWithoutRealCompletionStillFails() = runTest {
        withServer("""data: {"choices":[{"delta":{"content":"partial"},"finish_reason":""}]}""" + "\n\n") { provider, request, _ ->
            assertFailsWith<ModelException> { provider.stream(request).toList() }
        }
    }

    @Test fun httpErrorsPreserveRemoteExplanationWhileRedactingCredentials() = runTest {
        withServer("private-key server debug details", 401) { provider, request, _ ->
            val failure = assertFailsWith<ModelException> { provider.stream(request).toList() }
            assertFalse(failure.message.orEmpty().contains("private-key"))
            val details = assertNotNull(failure.details)
            assertEquals(401, details.httpStatus)
            assertTrue(details.response.contains("server debug details"))
            assertFalse(details.display().contains("private-key"))
        }
    }

    @Test fun streamErrorIncludesOriginalProviderCodeAndMessage() = runTest {
        withServer("data: {\"error\":{\"code\":\"invalid_model\",\"message\":\"Model xyz is unavailable\"}}\n\n") { provider, request, _ ->
            val failure = assertFailsWith<ModelException> { provider.stream(request).toList() }
            val details = assertNotNull(failure.details)
            assertTrue(details.response.contains("invalid_model"))
            assertTrue(details.response.contains("Model xyz is unavailable"))
        }
    }
    @Test fun malformedAndEmptyJsonResponsesRemainAvailableInDetails() = runTest {
        for (body in listOf("upstream gateway returned HTML", """{"choices":[{"message":{"content":""},"finish_reason":"stop"}],"provider_debug":"empty-result"}""")) {
            withServer(body, contentType = "application/json") { provider, request, _ ->
                val failure = assertFailsWith<ModelException> { provider.stream(request).toList() }
                assertEquals(body, assertNotNull(failure.details).response)
            }
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
