package dev.solvit.provider

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Discovery is independent of chatting and never requires a model name first. */
class OpenAiModelCatalog(client: OkHttpClient) : ModelCatalogService {
    private val client = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    override suspend fun models(config: ModelConfig): List<AvailableModel> = withContext(Dispatchers.IO) {
        config.validateConnection()?.let { throw ModelException(it) }
        val base = config.baseUrl.trimEnd('/').toHttpUrl()
        val url = if (config.modelListUrl.isBlank()) (base.toString().trimEnd('/') + "/models").toHttpUrl() else config.modelListUrl.toHttpUrl()
        val sameOrigin = base.scheme == url.scheme && base.host == url.host && base.port == url.port
        val request = Request.Builder().url(url).header("Accept", "application/json")
        // An independent subscription URL may have its own query-token. Never
        // forward the model API key to another origin without explicit opt-in.
        if (config.apiKey.isNotBlank() && (sameOrigin || config.modelListUsesApiKey)) request.header("Authorization", "Bearer ${config.apiKey}")
        val call = client.newCall(request.build())
        coroutineScope {
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.await().use { response ->
                    if (!response.isSuccessful) throw ModelException(when (response.code) {
                        401, 403 -> "获取模型认证失败，请检查密钥；独立列表地址如需同一密钥，请勾选对应选项"
                        404 -> "此服务没有默认模型列表接口，请填写独立模型列表地址或手动输入模型"
                        429 -> "获取模型过于频繁或服务限流，请稍后重试"
                        in 300..399 -> "模型列表地址发生重定向，请填写最终地址"
                        else -> "获取模型失败（HTTP ${response.code}）"
                    })
                    val body = response.body ?: throw ModelException("模型列表为空")
                    if (body.contentLength() > MAX_BYTES) throw ModelException("模型列表响应过大")
                    val data = ByteArrayOutputStream()
                    body.byteStream().use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = stream.read(buffer); if (n == -1) break
                            if (data.size() + n > MAX_BYTES) throw ModelException("模型列表响应过大")
                            data.write(buffer, 0, n)
                        }
                    }
                    parse(data.toByteArray().toString(Charsets.UTF_8))
                }
            } finally { cancellation.cancelAndJoin(); call.cancel() }
        }
    }
    private fun parse(text: String): List<AvailableModel> {
        val root = try { Json.parseToJsonElement(text) } catch (e: Exception) { throw ModelException("模型列表不是有效 JSON，支持 data/models 数组或模型名称数组") }
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] ?: root["models"]) as? JsonArray
            else -> null
        } ?: throw ModelException("响应没有 data 或 models 模型数组")
        if (entries.size > 2000) throw ModelException("模型列表数量过多")
        val models = entries.mapNotNull { value ->
            val object_ = value as? JsonObject
            val id = (object_?.get("id") ?: object_?.get("name") ?: value as? JsonPrimitive) as? JsonPrimitive
            val identifier = id?.takeIf { it.isString }?.content?.trim()
            if (identifier.isNullOrEmpty() || identifier.length > 256 || identifier.any { it.isISOControl() }) null
            else AvailableModel(identifier,
                (object_?.get("name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 256 && it.none(Char::isISOControl) } ?: identifier,
                (object_?.get("owned_by") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 256 && it.none(Char::isISOControl) })
        }.distinctBy { it.id }.sortedBy { it.id.lowercase(Locale.ROOT) }
        if (models.isEmpty()) throw ModelException("此地址没有返回可用模型名称，可手动填写模型")
        return models
    }
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
