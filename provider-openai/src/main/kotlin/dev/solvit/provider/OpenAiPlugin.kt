package dev.solvit.provider

import dev.solvit.core.*
import dev.solvit.http.ResponseDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAiProvider(private val client: OkHttpClient) : ModelProvider {
    override val id = "openai-compatible"
    private val json = Json { ignoreUnknownKeys = true }

    override fun stream(request: ModelRequest): Flow<ModelChunk> = flow {
        request.config.validate()?.let { throw ModelException(it) }
        val payload = buildJsonObject {
            put("model", request.config.model)
            put("stream", true)
            put("max_tokens", request.config.providerOptions["max_tokens"]?.toIntOrNull() ?: request.config.contextBudget.outputTokens)
            if (request.tools.isNotEmpty()) {
                put("tool_choice", "auto")
                putJsonArray("tools") {
                    request.tools.forEach { tool -> add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name); put("description", tool.description)
                            put("parameters", json.parseToJsonElement(tool.inputSchema).jsonObject)
                        }
                    }) }
                }
            }
            putJsonArray("messages") {
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.name.lowercase())
                    put("content", if (message.content.isEmpty() && message.toolCalls.isNotEmpty()) JsonNull else JsonPrimitive(message.content))
                    message.toolCallId?.let { put("tool_call_id", it) }
                    if (message.toolCalls.isNotEmpty()) put("tool_calls", JsonArray(message.toolCalls.map(::callJson)))
                }) }
            }
        }
        val http = Request.Builder()
            .url(request.config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (request.config.apiKey.isNotBlank()) http.header("Authorization", "Bearer ${request.config.apiKey}")
        val httpRequest = http.build()
        val diagnostics = ResponseDiagnostics(request.config, httpRequest.url)
        var lastResponse: ModelErrorDetails? = null
        var hasText = false
        val call = client.newCall(httpRequest)
        coroutineScope {
            // Cancels the blocking SSE read when the collecting coroutine is cancelled.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.await().use { response ->
                    lastResponse = diagnostics.details(response)
                    if (!response.isSuccessful) throw diagnostics.httpFailure(response, when (response.code) {
                        401, 403 -> "认证失败，请检查 API Key 和模型权限"
                        429 -> "请求过于频繁或额度不足，请稍后重试"
                        else -> "模型服务返回 HTTP ${response.code}"
                    })
                    val body = response.body ?: throw ModelException("模型服务返回空响应")
                    if (response.header("Content-Type")?.contains("text/event-stream") != true) {
                        val textBody = body.string()
                        lastResponse = diagnostics.details(response, textBody)
                        val result = json.parseToJsonElement(textBody).jsonObject
                        if (result["error"] != null) throw ModelException("模型服务返回错误")
                        val choice = result["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?: throw ModelException("响应中没有 choices")
                        val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        checkFinishReason(reason)
                        val message = choice["message"]?.jsonObject ?: throw ModelException("响应缺少 message")
                        val text = message["content"]?.jsonPrimitive?.contentOrNull
                        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                            ?: message["reasoning"]?.jsonPrimitive?.contentOrNull
                        if (!reasoning.isNullOrEmpty()) emit(ModelChunk.Reasoning(reasoning))
                        if (!text.isNullOrEmpty()) { hasText = true; emit(ModelChunk.Text(text)) }
                        val calls = message["tool_calls"]?.jsonArray?.map { element ->
                            val call = element.jsonObject
                            val function = call.getValue("function").jsonObject
                            ToolCall(call.getValue("id").jsonPrimitive.content, function.getValue("name").jsonPrimitive.content,
                                function.getValue("arguments").jsonPrimitive.content)
                        }.orEmpty()
                        finishCalls(calls, reason)?.let { emit(it) }
                        if (!hasText && calls.isEmpty()) throw ModelException("模型返回空内容，请查看详细信息")
                        emit(ModelChunk.Done)
                    } else {
                        val source = body.source()
                        val data = StringBuilder()
                        var done = false
                        var finished = false
                        var finishReason: String? = null
                        val calls = sortedMapOf<Int, PendingToolCall>()
                        suspend fun dispatch() {
                            if (data.isEmpty()) return
                            val event = data.toString().trim()
                            data.setLength(0)
                            if (event == "[DONE]") { done = true; return }
                            lastResponse = diagnostics.details(response, event)
                            val result = json.parseToJsonElement(event).jsonObject
                            if (result["error"] != null) throw ModelException("模型流返回错误")
                            val choice = result["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
                            // Compatible gateways may use "" in unfinished chunks instead of null.
                            val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            checkFinishReason(reason)
                            val delta = choice["delta"]?.jsonObject
                            if (finished && delta?.isNotEmpty() == true) throw ModelException("模型在结束后继续发送数据")
                            delta?.get("tool_calls")?.jsonArray?.forEach { element ->
                                val part = element.jsonObject
                                val index = part.getValue("index").jsonPrimitive.int
                                if (index !in 0..15) throw ModelException("单轮工具调用过多")
                                val pending = calls.getOrPut(index) { PendingToolCall() }
                                pending.add(part)
                            }
                            val content = delta?.get("content")?.jsonPrimitive?.contentOrNull
                            val reasoning = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                                ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
                            if (!reasoning.isNullOrEmpty()) emit(ModelChunk.Reasoning(reasoning))
                            if (!content.isNullOrEmpty()) { hasText = true; emit(ModelChunk.Text(content)) }
                            if (reason != null) { finished = true; finishReason = reason }
                        }
                        while (!done) {
                            currentCoroutineContext().ensureActive()
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) dispatch()
                            else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").removePrefix(" "))
                            }
                        }
                        dispatch()
                        if (!done && !finished) throw ModelException("连接提前中断，请重试")
                        finishCalls(calls.values.map { it.complete() }, finishReason)?.let { emit(it) }
                        if (!hasText && calls.isEmpty()) throw ModelException("模型流没有返回文本或工具调用，请查看详细信息")
                        emit(ModelChunk.Done)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: ModelException) {
                throw if (e.details != null) e else ModelException(e.message ?: "模型请求失败", lastResponse ?: diagnostics.details())
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ModelException("模型连接失败，请查看详细信息", diagnostics.details(body = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"))
            } catch (e: Exception) {
                throw ModelException("模型响应格式异常，请查看详细信息", lastResponse ?: diagnostics.details())
            } finally { cancellation.cancelAndJoin(); call.cancel() }
        }
    }.flowOn(Dispatchers.IO)

    private fun callJson(call: ToolCall): JsonObject = buildJsonObject {
        put("id", call.id); put("type", "function")
        putJsonObject("function") { put("name", call.name); put("arguments", call.argumentsJson) }
    }

    private fun checkFinishReason(reason: String?) {
        if (reason == "length") throw ModelException("回复超过模型输出限制，请缩短上下文")
        if (reason == "content_filter") throw ModelException("模型服务拒绝生成此回复")
        if (reason != null && reason !in setOf("stop", "tool_calls")) throw ModelException("模型返回了未识别的结束原因，请查看详细信息")
    }

    private fun finishCalls(calls: List<ToolCall>, reason: String?): ModelChunk.ToolCalls? {
        if (calls.isEmpty()) {
            if (reason == "tool_calls") throw ModelException("模型未返回完整工具调用")
            return null
        }
        if (reason != "tool_calls" || calls.size > 16 || calls.map { it.id }.toSet().size != calls.size)
            throw ModelException("模型工具调用未完整结束")
        calls.forEach { call ->
            if (call.id.isBlank() || call.id.length > 128 || call.name.length !in 1..64 ||
                !call.name.matches(Regex("[a-zA-Z0-9_-]+")) || call.argumentsJson.length > 65_536)
                throw ModelException("工具调用格式无效")
            try { json.parseToJsonElement(call.argumentsJson).jsonObject }
            catch (e: Exception) { throw ModelException("工具参数必须为完整 JSON 对象") }
        }
        return ModelChunk.ToolCalls(calls)
    }

    private class PendingToolCall {
        private val id = StringBuilder()
        private val name = StringBuilder()
        private val arguments = StringBuilder()
        fun add(part: JsonObject) {
            val type = part["type"]?.jsonPrimitive?.contentOrNull
            if (type != null && type != "function") throw ModelException("不支持的工具类型")
            part["id"]?.jsonPrimitive?.contentOrNull?.let(id::append)
            val function = part["function"]?.jsonObject
            function?.get("name")?.jsonPrimitive?.contentOrNull?.let(name::append)
            function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let(arguments::append)
            if (id.length > 128 || name.length > 64 || arguments.length > 65_536)
                throw ModelException("工具调用数据超出限制")
        }
        fun complete() = ToolCall(id.toString(), name.toString(), arguments.toString())
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}

class OpenAiPlugin : SolvitPlugin {
    override val id = "builtin.provider.openai"
    override val requires = setOf(ModelProvidersServiceKey)
    override suspend fun enable(context: PluginContext) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        context.own {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        context.own(context.require(ModelProvidersServiceKey).register(ModelProviderInfo("openai-compatible","OpenAI 兼容","https://api.openai.com/v1",
            "例如 https://api.deepseek.com/v1；无需填写 /chat/completions"),OpenAiProvider(client),OpenAiModelCatalog(client)))
    }
}
