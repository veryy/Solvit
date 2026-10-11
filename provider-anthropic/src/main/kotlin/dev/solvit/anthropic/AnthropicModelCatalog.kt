package dev.solvit.anthropic

import dev.solvit.core.*
import dev.solvit.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

class AnthropicModelCatalog(client:OkHttpClient):ModelCatalogService {
    private val client=client.newBuilder().callTimeout(30,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    override suspend fun models(config:ModelConfig):List<AvailableModel> = withContext(Dispatchers.IO) {
        config.validateConnection()?.let { throw ModelException(it) }
        val base=config.baseUrl.trimEnd('/').toHttpUrl()
        val endpoint=if(config.modelListUrl.isBlank()) (base.toString().trimEnd('/')+"/models").toHttpUrl() else config.modelListUrl.toHttpUrl()
        val sameOrigin=base.scheme==endpoint.scheme && base.host==endpoint.host && base.port==endpoint.port
        var url=endpoint
        val result=linkedMapOf<String,AvailableModel>();val cursors=mutableSetOf<String>()
        val diagnostics=ResponseDiagnostics(config,endpoint)
        var last=diagnostics.details()
        try {
            repeat(20) {
                currentCoroutineContext().ensureActive()
                val builder=Request.Builder().url(url).header("Accept","application/json").header("anthropic-version","2023-06-01")
                if(config.apiKey.isNotBlank() && (sameOrigin || config.modelListUsesApiKey)) builder.header("x-api-key",config.apiKey)
                val root=client.newCall(builder.build()).readResponse { response ->
                    last=diagnostics.details(response)
                    if(!response.isSuccessful) throw diagnostics.httpFailure(response,"获取 Claude 模型失败（HTTP ${response.code}）")
                    val source=response.body?.source() ?: throw ModelException("模型列表为空")
                    if(source.request(ProviderTranscript.MAX_BYTES.toLong()+1)) throw ModelException("模型列表响应过大")
                    val body=source.readUtf8();last=diagnostics.details(response,body)
                    Json.parseToJsonElement(body)
                }
                val entries=when(root) { is JsonArray -> root;is JsonObject -> (root["data"] ?: root["models"]) as? JsonArray;else -> null }
                    ?: throw ModelException("响应没有 data 或 models 模型数组")
                if(entries.size>2000) throw ModelException("模型列表数量过多")
                entries.forEach { value ->
                    val row=value as? JsonObject
                    val id=((row?.get("id") ?: row?.get("name") ?: value) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    if(!id.isNullOrEmpty() && id.length<=256 && id.none(Char::isISOControl)) {
                        val label=(row?.get("display_name") ?: row?.get("name")) as? JsonPrimitive
                        result[id]=AvailableModel(id,label?.contentOrNull?.takeIf { it.length<=256 && it.none(Char::isISOControl) } ?: id)
                    }
                }
                if(result.size>2000) throw ModelException("模型列表数量过多")
                if((root as? JsonObject)?.get("has_more")?.jsonPrimitive?.booleanOrNull!=true) {
                    if(result.isEmpty()) throw ModelException("响应中未解析出可用模型名称，请查看详细信息")
                    return@withContext result.values.toList()
                }
                val cursor=root.jsonObject["last_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 1..256 && it.none(Char::isISOControl) }
                    ?: throw ModelException("模型列表分页信息不完整")
                if(!cursors.add(cursor)) throw ModelException("模型列表分页重复，请检查服务端")
                url=endpoint.newBuilder().setQueryParameter("after_id",cursor).build()
            }
            throw ModelException("模型列表分页超过限制")
        } catch(e:CancellationException) { throw e }
        catch(e:ModelException) { throw if(e.details!=null) e else ModelException(e.message ?: "获取模型失败",last) }
        catch(e:IOException) { currentCoroutineContext().ensureActive();throw ModelException("获取模型连接失败，请查看详细信息",diagnostics.details(body="${e.javaClass.simpleName}: ${e.message.orEmpty()}")) }
        catch(e:Exception) { throw ModelException("Claude 模型列表格式异常，请查看详细信息",last) }
    }
}
