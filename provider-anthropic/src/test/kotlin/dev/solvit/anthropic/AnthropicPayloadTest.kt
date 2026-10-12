package dev.solvit.anthropic

import dev.solvit.core.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AnthropicPayloadTest {
    @Test fun runtimeDataRefreshIsAppendOnlyAcrossTwoSignedToolStepsAndRejectsEditedPrefixes() {
        val callA = ToolCall("a", "clock", "{}")
        val callB = ToolCall("b", "clock", "{}")
        val request = ModelRequest(ModelConfig(model = "test", providerId = "anthropic"),
            listOf(ModelMessage(Role.SYSTEM, "immutable identity"), ModelMessage(Role.USER, "check")),
            listOf(ToolDescriptor("clock", "Clock", "{}")), runtimeContext = listOf("before"))
        fun response(call: ToolCall, sent: NativeRequest) = ModelMessage(Role.ASSISTANT, "", listOf(call),
            providerTranscript = ProviderTranscript("anthropic", "test",
                """[{"type":"thinking","thinking":"reason","signature":"opaque-${call.id}"},{"type":"tool_use","id":"${call.id}","name":"clock","input":{}}]""",
                sent.options, sent.prefixHash, sent.requestPrefixJson))
        val first = nativeRequest(request)
        val a = response(callA, first)
        val afterA = request.copy(messages = request.messages + a + ModelMessage(Role.TOOL, "result A", toolCallId = "a"),
            runtimeContext = listOf("after A"))
        val second = nativeRequest(afterA)
        assertEquals(first.payload["system"], second.payload["system"])
        assertEquals(first.payload["messages"]!!.jsonArray.first(), second.payload["messages"]!!.jsonArray.first())
        val b = response(callB, second)
        val afterB = afterA.copy(messages = afterA.messages + b + ModelMessage(Role.TOOL, "result B", toolCallId = "b"),
            runtimeContext = listOf("after B"))
        val third = nativeRequest(afterB)
        val previous = second.payload["messages"]!!.jsonArray
        val continued = third.payload["messages"]!!.jsonArray
        assertEquals(previous, JsonArray(continued.take(previous.size)))
        val latest = continued.last().jsonObject["content"]!!.jsonArray
        assertEquals("tool_result", latest.first().jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(latest.last().jsonObject["text"]!!.jsonPrimitive.content.contains("after B"))
        assertTrue(third.payload.toString().contains("opaque-a")); assertTrue(third.payload.toString().contains("opaque-b"))
        assertEquals(third.payload, nativeRequest(afterB).payload, "Rebuilding must replay the saved projection deterministically")
        val edited = afterB.copy(messages = afterB.messages.map { if (it.toolCallId == "a") it.copy(content = "edited") else it })
        assertFailsWith<ModelException> { nativeRequest(edited) }
        assertFailsWith<ModelException> { nativeRequest(afterB.copy(messages = afterB.messages.map {
            if (it.role == Role.SYSTEM) it.copy(content = "changed identity") else it
        })) }
        val withoutContext = nativeRequest(afterB.copy(runtimeContext = emptyList()))
        assertTrue(withoutContext.payload["messages"]!!.jsonArray.last().toString().contains("当前没有启用"))
    }

    @Test fun pendingToolKeepsThinkingOptionsButChangedPrefixFailsAndCompletedHistoryCanContinue() {
        val config=ModelConfig(model="test",providerId="anthropic",providerOptions=mapOf("thinking_mode" to "enabled","max_tokens" to "4096","budget_tokens" to "1024"))
        val user=ModelMessage(Role.USER,"time")
        val request=ModelRequest(config,listOf(ModelMessage(Role.SYSTEM,"original system"),user),listOf(ToolDescriptor("clock","Clock","{}")))
        val first=nativeRequest(request)
        val call=ToolCall("c","clock","{}")
        val blocks="""[{"type":"thinking","thinking":"reason","signature":"opaque+/=="},{"type":"tool_use","id":"c","name":"clock","input":{}}]"""
        val native=ProviderTranscript("anthropic","test",blocks,first.options,first.prefixHash)
        val assistant=ModelMessage(Role.ASSISTANT,"",listOf(call),providerTranscript=native)
        val result=ModelMessage(Role.TOOL,"UTC",toolCallId="c")
        val pending=request.copy(config=ModelConfig(model="other-native-model",providerId="anthropic"),messages=request.messages+assistant+result)
        val follow=nativeRequest(pending)
        assertEquals(first.payload["thinking"],follow.payload["thinking"])
        assertEquals(4096,follow.payload["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(Json.parseToJsonElement(blocks),follow.payload["messages"]!!.jsonArray[1].jsonObject["content"])
        val changed=pending.copy(messages=listOf(ModelMessage(Role.SYSTEM,"changed system"),user,assistant,result))
        assertFailsWith<ModelException> { nativeRequest(changed) }
        // A new user turn completes the earlier tool turn; old thinking is removed when its prefix changed.
        val next=nativeRequest(changed.copy(messages=changed.messages+ModelMessage(Role.ASSISTANT,"answer")+ModelMessage(Role.USER,"next")))
        val retained=next.payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
        assertEquals("tool_use",retained.single().jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(next.payload["thinking"])
    }
    @Test fun foreignProviderUsesPortableTextAndCallsAndNativeMismatchIsRejected() {
        val native=ProviderTranscript("another","model","[]")
        val request=ModelRequest(ModelConfig(providerId="anthropic"),listOf(ModelMessage(Role.USER,"hi"),ModelMessage(Role.ASSISTANT,"hello",providerTranscript=native)))
        assertEquals("hello",nativeRequest(request).payload["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        val incorrect=ProviderTranscript("anthropic","model","""[{"type":"text","text":"different"}]""")
        assertFailsWith<ModelException> { nativeRequest(request.copy(messages=listOf(request.messages.first(),request.messages.last().copy(providerTranscript=incorrect)))) }
    }
    @Test fun consecutiveToolResultsAreGroupedAndManualThinkingIsValidated() {
        val request=ModelRequest(ModelConfig(providerId="anthropic"),listOf(ModelMessage(Role.USER,"question"),
            ModelMessage(Role.ASSISTANT,"",listOf(ToolCall("a","first","{}"),ToolCall("b","second","{}"))),
            ModelMessage(Role.TOOL,"ok",toolCallId="a",toolStage=ToolStage.SUCCEEDED),ModelMessage(Role.TOOL,"denied",toolCallId="b",toolStage=ToolStage.DENIED)))
        val results=nativeRequest(request).payload["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(2,results.size);assertFalse(results[0].jsonObject["is_error"]!!.jsonPrimitive.boolean);assertTrue(results[1].jsonObject["is_error"]!!.jsonPrimitive.boolean)
        assertNotNull(optionError(mapOf("thinking_mode" to "enabled","max_tokens" to "1024","budget_tokens" to "1024")))
        assertNull(optionError(mapOf("thinking_mode" to "adaptive")))
        assertNotNull(optionError(mapOf("unsupported" to "x")))
    }
}
