package dev.solvit.provider

import dev.solvit.core.*
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
            putJsonArray("messages") {
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.name.lowercase())
                    put("content", message.content)
                }) }
            }
        }
        val http = Request.Builder()
            .url(request.config.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (request.config.apiKey.isNotBlank()) http.header("Authorization", "Bearer ${request.config.apiKey}")
        val call = client.newCall(http.build())
        coroutineScope {
            // Cancels the blocking SSE read when the collecting coroutine is cancelled.
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.await().use { response ->
                    if (!response.isSuccessful) throw ModelException(when (response.code) {
                        401, 403 -> "认证失败，请检查 API Key 和模型权限"
                        429 -> "请求过于频繁或额度不足，请稍后重试"
                        else -> "模型服务返回 HTTP ${response.code}"
                    })
                    val body = response.body ?: throw ModelException("模型服务返回空响应")
                    if (response.header("Content-Type")?.contains("text/event-stream") != true) {
                        val result = json.parseToJsonElement(body.string()).jsonObject
                        if (result["error"] != null) throw ModelException("模型服务返回错误")
                        val choice = result["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                            ?: throw ModelException("响应中没有 choices")
                        if (choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length")
                            throw ModelException("回复超过模型输出限制，请缩短上下文")
                        val text = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                            ?: throw ModelException("模型没有返回文本回复")
                        emit(ModelChunk.Text(text))
                        emit(ModelChunk.Done)
                    } else {
                        val source = body.source()
                        val data = StringBuilder()
                        var done = false
                        var finished = false
                        suspend fun dispatch() {
                            if (data.isEmpty()) return
                            val event = data.toString().trim()
                            data.setLength(0)
                            if (event == "[DONE]") { done = true; return }
                            val result = json.parseToJsonElement(event).jsonObject
                            if (result["error"] != null) throw ModelException("模型流返回错误")
                            val choice = result["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return
                            val reason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                            if (reason == "length") throw ModelException("回复超过模型输出限制，请缩短上下文")
                            if (reason == "tool_calls") throw ModelException("第一版暂不执行模型工具调用")
                            if (reason != null) finished = true
                            val content = choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                            if (!content.isNullOrEmpty()) emit(ModelChunk.Text(content))
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
                        emit(ModelChunk.Done)
                    }
                }
            } finally { cancellation.cancelAndJoin(); call.cancel() }
        }
    }.flowOn(Dispatchers.IO)

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
    override val provides = setOf(Services.Model)
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
        context.register(Services.Model, OpenAiProvider(client))
    }
}
