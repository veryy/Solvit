package dev.solvit.anthropic

import dev.solvit.core.*
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** A replay contains only model-visible content, never endpoints or credentials. */
internal data class NativeReplay(val start: Int, val messages: List<JsonObject>, val context: List<String>)

internal fun replayPrefix(request: ModelRequest, system: String, tools: JsonArray): NativeReplay? {
    if (request.messages.any { it.images.isNotEmpty() }) return null
    val index = request.messages.indexOfLast {
        it.role == Role.ASSISTANT && it.providerTranscript?.providerId == ANTHROPIC_ID &&
            it.providerTranscript?.requestPrefixJson?.isNotEmpty() == true
    }
    if (index < 0) return null
    val transcript = request.messages[index].providerTranscript!!
    val prefix = Json.parseToJsonElement(transcript.requestPrefixJson).jsonObject
    val savedMessages = prefix.getValue("messages").jsonArray.map { it.jsonObject }
    val savedSystem = prefix.getValue("system").jsonPrimitive.content
    val savedTools = prefix.getValue("tools").jsonArray
    if (prefixHash(savedSystem, savedTools, savedMessages) != transcript.prefixHash)
        throw ModelException("模型请求投影与签名前缀不一致")
    // Never replay a prefix over edited canonical history, changed rules, or changed tools.
    if (savedSystem != system || savedTools != tools ||
        prefix.getValue("canonicalHash").jsonPrimitive.content != canonicalInputHash(request.messages.take(index))) return null
    return NativeReplay(index, savedMessages, prefix.getValue("runtimeContext").jsonArray.map { it.jsonPrimitive.content })
}

internal fun savedPrefix(request: ModelRequest, system: String, tools: JsonArray, messages: List<JsonObject>): String {
    // Rebuild image prefixes from immutable private attachments each step. Persist only
    // prefixHash and native assistant blocks, never an extra base64 copy in SQLite.
    if (request.messages.any { it.images.isNotEmpty() }) return ""
    return buildJsonObject {
        put("system", system); put("tools", tools); put("messages", JsonArray(messages))
        put("canonicalHash", canonicalInputHash(request.messages))
        put("runtimeContext", JsonArray(request.runtimeContext.map(::JsonPrimitive)))
    }.toString().also {
        if (it.toByteArray(Charsets.UTF_8).size > ProviderTranscript.MAX_BYTES)
            throw ModelException("模型请求投影超过保存限制，请缩小上下文")
    }

}

private fun canonicalInputHash(messages: List<ModelMessage>): String {
    val value = JsonArray(messages.filter { it.role != Role.SYSTEM }.map { message -> buildJsonObject {
        put("role", message.role.name); put("content", message.content)
        put("callId", message.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
        put("stage", message.toolStage?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("calls", JsonArray(message.toolCalls.map { call -> buildJsonObject {
            put("id", call.id); put("name", call.name); put("arguments", Json.parseToJsonElement(call.argumentsJson))
        } }))
        put("native", message.providerTranscript?.blocksJson?.let(::JsonPrimitive) ?: JsonNull)
    } })
    return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
