package dev.solvit.anthropic

import dev.solvit.core.*
import dev.solvit.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AnthropicProvider(private val client: OkHttpClient) : ModelProvider {
    override val id = ANTHROPIC_ID
    override fun validate(config:ModelConfig):String? {
        config.validate()?.let { return it }
        optionError(config.providerOptions)?.let { return it }
        return runCatching { config.contextBudget.copy(outputTokens = maxOf(config.contextBudget.outputTokens,
            config.providerOptions["max_tokens"]?.toInt() ?: 8192)).validate() }.exceptionOrNull()?.message
    }
    override fun reservedOutputTokens(request: ModelRequest): Int {
        val lastUser = request.messages.indexOfLast { it.role == Role.USER }
        val pending = request.messages.drop(lastUser + 1).lastOrNull { it.toolCalls.isNotEmpty() && it.providerTranscript?.providerId == "anthropic" }
        val value = if (pending != null) pending.providerTranscript!!.options["max_tokens"] ?: "8192"
            else request.config.providerOptions["max_tokens"] ?: "8192"
        return value.toInt()
    }
    override fun separatesRuntimeContext(config: ModelConfig) = true
    override fun stream(request:ModelRequest):Flow<ModelChunk> = flow {
        validate(request.config)?.let { throw ModelException(it) }
        val native = nativeRequest(request)
        val builder = Request.Builder().url(request.config.baseUrl.trimEnd('/') + "/messages")
            .header("Accept","text/event-stream").header("anthropic-version","2023-06-01")
            .post(native.payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if(request.config.apiKey.isNotBlank()) builder.header("x-api-key",request.config.apiKey)
        val http = builder.build();val diagnostics = ResponseDiagnostics(request.config,http.url)
        var last = diagnostics.details()
        try {
            client.newCall(http).readResponse { response ->
                last=diagnostics.details(response)
                if(!response.isSuccessful) throw diagnostics.httpFailure(response,"Claude 请求失败（HTTP ${response.code}）")
                val source=response.body?.source() ?: throw ModelException("模型服务返回空响应")
                val blocks = NativeBlocks { emit(it) }
                var stopped=false;var started=false;var stopReason:String?=null
                if(response.header("Content-Type")?.contains("text/event-stream",true)==true) {
                    readSse(source) { event,data ->
                        last=diagnostics.details(response,data)
                        val root=Json.parseToJsonElement(data).jsonObject
                        val type=root["type"]?.jsonPrimitive?.content ?: event
                        when(type) {
                            "error" -> throw ModelException("Claude 流式响应返回错误，请查看详细信息")
                            "message_start" -> {
                                if(started) throw ModelException("模型重复开始响应")
                                val message=root.getValue("message").jsonObject
                                if(message["role"]?.jsonPrimitive?.content != "assistant" || message.getValue("content").jsonArray.isNotEmpty())
                                    throw ModelException("模型流开始事件无效")
                                started=true
                            }
                            "content_block_start" -> { requireStarted(started);blocks.start(root.getValue("index").jsonPrimitive.int,root.getValue("content_block").jsonObject) }
                            "content_block_delta" -> { requireStarted(started);blocks.delta(root.getValue("index").jsonPrimitive.int,root.getValue("delta").jsonObject) }
                            "content_block_stop" -> { requireStarted(started);blocks.stop(root.getValue("index").jsonPrimitive.int) }
                            "message_delta" -> { requireStarted(started);root.getValue("delta").jsonObject["stop_reason"]?.jsonPrimitive?.contentOrNull?.let { stopReason=it } }
                            "message_stop" -> { requireStarted(started);stopped=true }
                            // Ping and future transport events carry no executable content.
                        }
                        !stopped
                    }
                    if(!stopped) throw ModelException("Claude 连接提前中断，请重试")
                } else {
                    if(source.request(ProviderTranscript.MAX_BYTES.toLong()+1)) throw ModelException("模型响应超过限制")
                    val body=source.readUtf8();last=diagnostics.details(response,body)
                    val root=Json.parseToJsonElement(body).jsonObject
                    if(root["error"]!=null) throw ModelException("Claude 返回错误，请查看详细信息")
                    if(root["role"]?.jsonPrimitive?.content != "assistant") throw ModelException("模型响应缺少助手消息")
                    root.getValue("content").jsonArray.forEachIndexed { i,value -> blocks.start(i,value.jsonObject);blocks.stop(i) }
                    stopReason=root["stop_reason"]?.jsonPrimitive?.contentOrNull
                }
                val completed=blocks.complete(stopReason)
                emit(ModelChunk.NativeTranscript(ProviderTranscript(id,request.config.model,completed.first.toString(),native.options,native.prefixHash,native.requestPrefixJson)))
                if(completed.second.isNotEmpty()) emit(ModelChunk.ToolCalls(completed.second))
                emit(ModelChunk.Done)
            }
        } catch(e:CancellationException) { throw e }
        catch(e:ModelException) { throw if(e.details!=null) e else ModelException(e.message ?: "Claude 请求失败",last) }
        catch(e:IOException) { currentCoroutineContext().ensureActive();throw ModelException("Claude 连接失败，请查看详细信息",diagnostics.details(body="${e.javaClass.simpleName}: ${e.message.orEmpty()}")) }
        catch(e:Exception) { throw ModelException("Claude 响应格式异常，请查看详细信息",last) }
    }.flowOn(Dispatchers.IO)
    private fun requireStarted(value:Boolean) { if(!value) throw ModelException("模型流缺少开始事件") }
}

internal class NativeBlocks(private val emit:suspend (ModelChunk)->Unit) {
    private class Block(val fields:MutableMap<String,JsonElement>,val type:String) {
        var closed=false
        val input=StringBuilder()
        var inputDelta=false
    }
    private val blocks=sortedMapOf<Int,Block>()
    private var bytes=0
    private fun bound(text:String) { bytes+=text.toByteArray(Charsets.UTF_8).size;if(bytes>ProviderTranscript.MAX_BYTES) throw ModelException("模型原始转录超过限制") }
    suspend fun start(index:Int,root:JsonObject) {
        if(index !in 0..255 || index in blocks) throw ModelException("模型内容块索引无效")
        bound(root.toString())
        val type=root.getValue("type").jsonPrimitive.content
        if(type !in setOf("text","thinking","redacted_thinking","tool_use")) throw ModelException("Claude 返回尚未支持的内容类型，请查看详细信息")
        blocks[index]=Block(root.toMutableMap(),type)
        when(type) {
            "text" -> root.getValue("text").jsonPrimitive.content.takeIf { it.isNotEmpty() }?.let { emit(ModelChunk.Text(it)) }
            "thinking" -> root.getValue("thinking").jsonPrimitive.content.takeIf { it.isNotEmpty() }?.let { emit(ModelChunk.Reasoning(it)) }
        }
    }
    suspend fun delta(index:Int,delta:JsonObject) {
        val block=blocks[index]?.takeIf { !it.closed } ?: throw ModelException("模型内容块尚未开始或已经结束")
        bound(delta.toString())
        fun append(field:String,value:String) { block.fields[field]=JsonPrimitive((block.fields[field] as? JsonPrimitive)?.content.orEmpty()+value) }
        when(delta.getValue("type").jsonPrimitive.content) {
            "text_delta" -> { checkType(block,"text");val text=delta.getValue("text").jsonPrimitive.content;append("text",text);if(text.isNotEmpty()) emit(ModelChunk.Text(text)) }
            "thinking_delta" -> { checkType(block,"thinking");val text=delta.getValue("thinking").jsonPrimitive.content;append("thinking",text);if(text.isNotEmpty()) emit(ModelChunk.Reasoning(text)) }
            "signature_delta" -> { checkType(block,"thinking");append("signature",delta.getValue("signature").jsonPrimitive.content) }
            "input_json_delta" -> {
                checkType(block,"tool_use");block.inputDelta=true;block.input.append(delta.getValue("partial_json").jsonPrimitive.content)
                if(block.input.length>65_536) throw ModelException("工具参数超过限制")
            }
            "citations_delta" -> { checkType(block,"text");val previous=(block.fields["citations"] as? JsonArray).orEmpty();block.fields["citations"]=JsonArray(previous+delta.getValue("citation")) }
            else -> throw ModelException("Claude 返回尚未支持的内容增量，请查看详细信息")
        }
    }
    fun stop(index:Int) {
        val block=blocks[index]?.takeIf { !it.closed } ?: throw ModelException("模型内容块结束事件无效")
        if(block.inputDelta) block.fields["input"]=Json.parseToJsonElement(block.input.toString()).jsonObject
        block.closed=true
    }
    fun complete(reason:String?):Pair<JsonArray,List<ToolCall>> {
        if(blocks.values.any { !it.closed } || blocks.keys.toList() != blocks.keys.indices.toList()) throw ModelException("模型内容块未完整结束")
        val content=JsonArray(blocks.values.map { JsonObject(it.fields) })
        if(content.toString().toByteArray(Charsets.UTF_8).size>ProviderTranscript.MAX_BYTES) throw ModelException("模型原始转录超过限制")
        val calls=content.filter { it.jsonObject["type"]?.jsonPrimitive?.content=="tool_use" }.map { value -> val block=value.jsonObject
            ToolCall(block.getValue("id").jsonPrimitive.content,block.getValue("name").jsonPrimitive.content,block.getValue("input").jsonObject.toString()) }
        content.filter { it.jsonObject["type"]?.jsonPrimitive?.content=="thinking" }.forEach {
            if(it.jsonObject["signature"]?.jsonPrimitive?.content.isNullOrEmpty()) throw ModelException("模型思考缺少签名，无法安全回传")
        }
        content.filter { it.jsonObject["type"]?.jsonPrimitive?.content=="redacted_thinking" }.forEach {
            if(it.jsonObject["data"]?.jsonPrimitive?.content.isNullOrEmpty()) throw ModelException("模型隐去思考的数据不完整")
        }
        if(reason=="max_tokens") throw ModelException("回复超过模型输出限制，请调整最大输出令牌")
        if(reason !in setOf("end_turn","stop_sequence","tool_use")) throw ModelException("Claude 未正常完成回复，请查看结束原因")
        if((reason=="tool_use") != calls.isNotEmpty() || calls.size>16 || calls.map { it.id }.toSet().size!=calls.size) throw ModelException("模型工具调用未完整结束")
        calls.forEach {
            if(it.id.length !in 1..128 || !it.name.matches(Regex("[A-Za-z0-9_-]{1,64}")) || it.argumentsJson.length>65_536) throw ModelException("工具调用格式无效")
        }
        if(calls.isEmpty() && content.none { it.jsonObject["type"]?.jsonPrimitive?.content=="text" && it.jsonObject["text"]?.jsonPrimitive?.content?.isNotEmpty()==true })
            throw ModelException("Claude 返回空内容，请查看详细信息")
        return content to calls
    }
    private fun checkType(block:Block,type:String) { if(block.type!=type) throw ModelException("模型内容增量类型不匹配") }
}

class AnthropicPlugin : SolvitPlugin {
    override val id="builtin.provider.anthropic"
    override val requires=setOf(ModelProvidersServiceKey)
    override suspend fun enable(context:PluginContext) {
        val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS)
            .callTimeout(5,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
        context.own { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
        context.own(context.require(ModelProvidersServiceKey).register(ModelProviderInfo(ANTHROPIC_ID,"Claude / Anthropic",
            "https://api.anthropic.com/v1","例如 https://api.anthropic.com/v1；无需填写 /messages",listOf(
                ProviderOption("max_tokens","最大输出令牌","8192"),
                ProviderOption("thinking_mode","思考模式","default",listOf("default" to "模型默认","adaptive" to "自适应思考","enabled" to "手动思考预算","disabled" to "明确关闭思考")),
                ProviderOption("budget_tokens","思考预算令牌","2048")
            )),AnthropicProvider(client),AnthropicModelCatalog(client)))
    }
}
