package dev.solvit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll

/** Settings are declared by each Provider; UI never selects a concrete HTTP implementation. */
data class ProviderOption(val key: String, val label: String, val defaultValue: String = "", val choices: List<Pair<String,String>> = emptyList())
data class ModelProviderInfo(val id: String, val name: String, val defaultBaseUrl: String, val endpointHint: String,
    val options: List<ProviderOption> = emptyList())
class ModelProviderRegistry {
    private data class Entry(val info: ModelProviderInfo, val provider: ModelProvider, val catalog: ModelCatalogService?)
    private val entries = linkedMapOf<String,Entry>()
    private val mutable = MutableStateFlow<List<ModelProviderInfo>>(emptyList())
    val available = mutable.asStateFlow()
    @Synchronized fun register(info: ModelProviderInfo, provider: ModelProvider, catalog: ModelCatalogService? = null): () -> Unit {
        require(info.id == provider.id && info.id.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        check(info.id !in entries) { "模型协议已经注册" }
        val entry = Entry(info,provider,catalog);entries[info.id]=entry;mutable.value=entries.values.map { it.info }
        return { synchronized(this) { if(entries[info.id] === entry) { entries.remove(info.id);mutable.value=entries.values.map { it.info } } } }
    }
    @Synchronized fun provider(id: String): ModelProvider = entries[id]?.provider ?: throw ModelException("模型协议插件未启用，请选择已加载的协议")
    @Synchronized fun catalog(id: String): ModelCatalogService = entries[id]?.catalog ?: throw ModelException("此协议插件没有提供模型列表，请手动填写模型名称")
    fun validate(config: ModelConfig): String? = runCatching { provider(config.providerId).validate(config) }.getOrElse { it.message ?: "模型协议不可用" }
}
val ModelProvidersServiceKey = ServiceKey("model.providers",ModelProviderRegistry::class)
class ModelProvidersPlugin : SolvitPlugin {
    override val id = "builtin.model.providers"
    override val provides = setOf(ModelProvidersServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(ModelProvidersServiceKey,ModelProviderRegistry()) }
}
class ModelRoutingPlugin : SolvitPlugin {
    override val id = "builtin.model.router"
    override val requires = setOf(ModelProvidersServiceKey)
    override val provides = setOf(Services.Model,ModelCatalogServiceKey)
    override suspend fun enable(context: PluginContext) {
        val registry=context.require(ModelProvidersServiceKey)
        context.register(Services.Model,object:ModelProvider {
            override val id="model-router"
            override fun reservedOutputTokens(request: ModelRequest) = registry.provider(request.config.providerId).reservedOutputTokens(request)
            override fun stream(request:ModelRequest)=flow { emitAll(registry.provider(request.config.providerId).stream(request)) }
        })
        context.register(ModelCatalogServiceKey,object:ModelCatalogService {
            override suspend fun models(config:ModelConfig)=registry.catalog(config.providerId).models(config)
        })
    }
}
/** Opaque provider content is never displayed as JSON or reinterpreted as instructions. */
data class ProviderTranscript(val providerId: String, val model: String, val blocksJson: String, val options: Map<String,String> = emptyMap(), val prefixHash: String = "") {
    init {
        require(providerId.matches(Regex("[A-Za-z0-9_.-]{1,80}")))
        require(model.isNotBlank() && model.length <= 256)
        require(blocksJson.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "模型原始转录超过限制" }
        require(prefixHash.isEmpty() || prefixHash.matches(Regex("[a-f0-9]{64}")))
        require(options.size <= 32 && options.all { it.key.length <= 80 && it.value.length <= 1024 })
    }
    override fun toString()="ProviderTranscript(providerId=$providerId, model=$model, bytes=${blocksJson.toByteArray(Charsets.UTF_8).size})"
    companion object { const val MAX_BYTES=2*1024*1024 }
}
