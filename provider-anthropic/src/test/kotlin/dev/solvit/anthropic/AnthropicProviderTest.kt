package dev.solvit.anthropic

import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import kotlin.test.*

private fun event(type:String,fields:String="") = "event: $type\ndata: {\"type\":\"$type\"$fields}\n\n"
private fun start() = event("message_start",",\"message\":{\"role\":\"assistant\",\"content\":[]}")
private fun block(index:Int,json:String) = event("content_block_start",",\"index\":$index,\"content_block\":$json")
private fun delta(index:Int,json:String) = event("content_block_delta",",\"index\":$index,\"delta\":$json")
private fun stop(index:Int) = event("content_block_stop",",\"index\":$index")
private fun end(reason:String="end_turn") = event("message_delta",",\"delta\":{\"stop_reason\":\"$reason\"}")+event("message_stop")
private fun answer(text:String="answer") = start()+block(0,"""{"type":"text","text":""}""")+delta(0,buildJsonObject { put("type","text_delta");put("text",text) }.toString())+stop(0)+end()
private fun thinkingAndTool() = start()+
    block(0,"""{"type":"thinking","thinking":"","signature":""}""")+
    delta(0,"""{"type":"thinking_delta","thinking":"check first"}""")+
    delta(0,"""{"type":"signature_delta","signature":"opaque+/"}""")+
    delta(0,"""{"type":"signature_delta","signature":"=="}""")+stop(0)+
    block(1,"""{"type":"redacted_thinking","data":"hidden+/=="}""")+stop(1)+
    block(2,"""{"type":"tool_use","id":"call1","name":"clock","input":{}}""")+
    delta(2,buildJsonObject { put("type","input_json_delta");put("partial_json","{\"zone\":") }.toString())+
    delta(2,buildJsonObject { put("type","input_json_delta");put("partial_json","\"UTC\"}") }.toString())+stop(2)+end("tool_use")

class AnthropicProviderTest {
    @Test fun outputReservationIncludesNativeDefaultAndPendingToolTurnOptions() {
        val provider = AnthropicProvider(OkHttpClient())
        val config = ModelConfig(providerId = "anthropic", providerOptions = mapOf("max_tokens" to "2048"))
        assertEquals(8_192, provider.reservedOutputTokens(ModelRequest(ModelConfig(providerId = "anthropic"), emptyList())))
        val native = ProviderTranscript("anthropic", "test", "[]", mapOf("max_tokens" to "16384"))
        val request = ModelRequest(config, listOf(ModelMessage(Role.USER, "task"),
            ModelMessage(Role.ASSISTANT, "", listOf(ToolCall("c", "tool", "{}")), providerTranscript = native)))
        assertEquals(16_384, provider.reservedOutputTokens(request))
    }
    private suspend fun server(block:suspend (MockWebServer,OkHttpClient,ModelConfig)->Unit) {
        val server=MockWebServer();server.start()
        val client=OkHttpClient.Builder().followRedirects(false).readTimeout(5,TimeUnit.SECONDS).build()
        try { block(server,client,ModelConfig(server.url("/v1").toString(),"test-model","private-native-key",providerId="anthropic")) }
        finally { client.dispatcher.cancelAll();server.shutdown();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    }
    private fun response(body:String)=MockResponse().setHeader("Content-Type","text/event-stream").setBody(body)

    @Test fun streamsThinkingAndPartialToolArgumentsWithNativeAuthentication() = runBlocking {
        server { server,client,config ->
            server.enqueue(response(thinkingAndTool()))
            val chunks=AnthropicProvider(client).stream(ModelRequest(config,listOf(ModelMessage(Role.SYSTEM,"system"),ModelMessage(Role.USER,"time")),
                tools=listOf(ToolDescriptor("clock","Clock","{}")))).toList()
            assertEquals("check first",chunks.filterIsInstance<ModelChunk.Reasoning>().single().value)
            assertEquals(ToolCall("call1","clock","""{"zone":"UTC"}"""),chunks.filterIsInstance<ModelChunk.ToolCalls>().single().calls.single())
            val native=chunks.filterIsInstance<ModelChunk.NativeTranscript>().single().value
            val blocks=Json.parseToJsonElement(native.blocksJson).jsonArray
            assertEquals("opaque+/==",blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
            assertEquals("hidden+/==",blocks[1].jsonObject["data"]!!.jsonPrimitive.content)
            assertEquals(64,native.prefixHash.length)
            assertEquals(ModelChunk.Done,chunks.last())
            val sent=server.takeRequest()
            assertEquals("/v1/messages",sent.path)
            assertEquals("private-native-key",sent.getHeader("x-api-key"))
            assertEquals("2023-06-01",sent.getHeader("anthropic-version"))
            assertNull(sent.getHeader("Authorization"))
            val body=Json.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("system",body["system"]!!.jsonPrimitive.content)
            assertEquals(1,body["messages"]!!.jsonArray.size)
            assertNotNull(body["tools"]!!.jsonArray[0].jsonObject["input_schema"])
            assertNull(body["thinking"])
        }
    }
    @Test fun realToolLoopReturnsOpaqueThinkingAndAuthorizedResultToSecondNativeRequest() = runBlocking {
        server { server,client,config ->
            server.enqueue(response(thinkingAndTool()));server.enqueue(response(answer()))
            var executions=0
            val tools=ToolRegistry(EventBus()).apply { register(object:SolvitTool {
                override val descriptor=ToolDescriptor("clock","Clock","{}",requiresConfirmation=true)
                override suspend fun execute(argumentsJson:String):String { executions++;assertEquals("UTC",Json.parseToJsonElement(argumentsJson).jsonObject["zone"]!!.jsonPrimitive.content);return "actual UTC" }
            }) }
            val authorization=object:ToolAuthorization { override suspend fun authorize(descriptor:ToolDescriptor)=true }
            val chunks=ToolAgentRuntime(AnthropicProvider(client),tools).run(ModelRequest(config,listOf(ModelMessage(Role.USER,"time")),authorization=authorization)).toList()
            assertEquals(1,executions);assertEquals(1,chunks.count { it==ModelChunk.Done })
            assertEquals("answer",chunks.filterIsInstance<ModelChunk.Text>().single().value)
            val transcript=chunks.filterIsInstance<ModelChunk.Transcript>().first().message.providerTranscript!!
            server.takeRequest()
            val body=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val messages=body["messages"]!!.jsonArray
            assertEquals(Json.parseToJsonElement(transcript.blocksJson),messages[1].jsonObject["content"])
            val result=messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
            assertEquals("tool_result",result["type"]!!.jsonPrimitive.content)
            assertEquals("call1",result["tool_use_id"]!!.jsonPrimitive.content)
            assertEquals("actual UTC",result["content"]!!.jsonPrimitive.content)
            assertFalse(result["is_error"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun denialNeverExecutesAndIsReturnedAsNativeToolError() = runBlocking {
        server { server,client,config ->
            server.enqueue(response(thinkingAndTool()));server.enqueue(response(answer("denied")))
            val tools=ToolRegistry(EventBus()).apply { register(object:SolvitTool {
                override val descriptor=ToolDescriptor("clock","Clock","{}",requiresConfirmation=true)
                override suspend fun execute(argumentsJson:String):String = error("must not execute")
            }) }
            ToolAgentRuntime(AnthropicProvider(client),tools).run(ModelRequest(config,listOf(ModelMessage(Role.USER,"time")))).toList()
            server.takeRequest()
            val body=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.single().jsonObject["is_error"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun continuousNativeToolsKeepEverySignedRoundBeforeFinalReply() = runBlocking {
        server { server,client,config ->
            server.enqueue(response(thinkingAndTool()))
            server.enqueue(response(thinkingAndTool().replace("call1","call2")))
            server.enqueue(response(answer("two results checked")))
            var executions=0
            val tools=ToolRegistry(EventBus()).apply { register(object:SolvitTool {
                override val descriptor=ToolDescriptor("clock","Clock","{}")
                override suspend fun execute(argumentsJson:String):String { executions++;return "result-$executions" }
            }) }
            val allow=object:ToolAuthorization { override suspend fun authorize(descriptor:ToolDescriptor)=true }
            val chunks=ToolAgentRuntime(AnthropicProvider(client),tools).run(ModelRequest(config,listOf(ModelMessage(Role.USER,"verify twice")),authorization=allow)).toList()
            assertEquals(2,executions);assertEquals(1,chunks.count { it==ModelChunk.Done })
            server.takeRequest();server.takeRequest()
            val messages=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
            assertEquals(5,messages.size)
            for(index in listOf(1,3)) {
                assertEquals("opaque+/==",messages[index].jsonObject["content"]!!.jsonArray[0].jsonObject["signature"]!!.jsonPrimitive.content)
                assertEquals("hidden+/==",messages[index].jsonObject["content"]!!.jsonArray[1].jsonObject["data"]!!.jsonPrimitive.content)
            }
            assertEquals("call2",messages.last().jsonObject["content"]!!.jsonArray.single().jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        }
    }
    @Test fun http200StreamErrorsAndHttpFailuresRetainRedactedDetailsAndNeverComplete() = runBlocking {
        for(status in listOf(200,401)) server { server,client,config ->
            val body=if(status==200) start()+event("error",",\"error\":{\"message\":\"private-native-key\"}") else "private-native-key denied"
            server.enqueue(response(body).setResponseCode(status).setHeader("request-id","request-native"))
            val error=assertFailsWith<ModelException> { AnthropicProvider(client).stream(ModelRequest(config,listOf(ModelMessage(Role.USER,"hello")))).toList() }
            assertEquals(status,error.details!!.httpStatus)
            assertEquals("request-native",error.details!!.requestId)
            assertFalse(error.details!!.display().contains("private-native-key"))
        }
    }
    @Test fun truncationAndIncompleteToolJsonAreRejected() = runBlocking {
        val bodies=listOf(start()+block(0,"""{"type":"text","text":"partial"}"""),
            start()+block(0,"""{"type":"tool_use","id":"call1","name":"clock","input":{}}""")+delta(0,"""{"type":"input_json_delta","partial_json":"{"}""")+stop(0)+end("tool_use"),
            answer().replace("end_turn","max_tokens"))
        for(body in bodies) server { server,client,config ->
            server.enqueue(response(body))
            val chunks=mutableListOf<ModelChunk>()
            assertFailsWith<ModelException> { AnthropicProvider(client).stream(ModelRequest(config,listOf(ModelMessage(Role.USER,"hello")))).toList(chunks) }
            assertFalse(chunks.any { it==ModelChunk.Done || it is ModelChunk.NativeTranscript })
        }
    }
    @Test fun firstTokenArrivesBeforeEndAndCancellationReleasesHttpCallForNextRequest() = runBlocking {
        server { server,client,config ->
            val prefix=start()+block(0,"""{"type":"text","text":"first token"}""")
            server.enqueue(response(prefix+stop(0)+end()).throttleBody(prefix.toByteArray().size.toLong(),5,TimeUnit.SECONDS))
            server.enqueue(response(answer("next")))
            val provider=AnthropicProvider(client)
            val request=ModelRequest(config,listOf(ModelMessage(Role.USER,"hello")))
            val arrived=CompletableDeferred<Unit>();var done=false
            val job=launch { provider.stream(request).collect { if(it is ModelChunk.Text) arrived.complete(Unit);if(it==ModelChunk.Done) done=true } }
            withTimeout(3000) { arrived.await() };assertFalse(done)
            withTimeout(3000) { job.cancelAndJoin() }
            assertEquals("next",provider.stream(request).toList().filterIsInstance<ModelChunk.Text>().single().value)
        }
    }
    @Test fun nativeJsonFallbackStillRetainsSignedBlocksAndFinishes() = runBlocking {
        server { server,client,config ->
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("""{"role":"assistant","content":[{"type":"thinking","thinking":"why","signature":"opaque"},{"type":"text","text":"answer"}],"stop_reason":"end_turn"}"""))
            val chunks=AnthropicProvider(client).stream(ModelRequest(config,listOf(ModelMessage(Role.USER,"hello")))).toList()
            assertEquals(ModelChunk.Reasoning("why"),chunks.first())
            assertEquals(ModelChunk.Done,chunks.last())
            assertTrue(chunks.any { it is ModelChunk.NativeTranscript })
        }
    }
}
