package dev.solvit.core

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ModelProvidersTest {
    @Test fun pluginsRegisterRouteAndReleaseIndependentProviders() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        fun plugin(name: String) = object : SolvitPlugin {
            override val id = "test.$name"
            override val requires = setOf(ModelProvidersServiceKey)
            override suspend fun enable(context: PluginContext) {
                val provider = object : ModelProvider {
                    override val id = name
                    override fun stream(request: ModelRequest) = flowOf(ModelChunk.Text(name), ModelChunk.Done)
                }
                val catalog = object : ModelCatalogService {
                    override suspend fun models(config: ModelConfig) = listOf(AvailableModel("$name-model"))
                }
                context.own(context.require(ModelProvidersServiceKey).register(
                    ModelProviderInfo(name,name,"https://example.com/v1",""),provider,catalog))
            }
        }
        listOf(ModelRoutingPlugin(),plugin("first"),plugin("second"),ModelProvidersPlugin()).forEach { manager.load(it) }
        manager.enableAll()
        val registry = manager.registry.require(ModelProvidersServiceKey)
        val router = manager.registry.require(Services.Model)
        val config = ModelConfig(providerId = "second")
        assertEquals(listOf(ModelChunk.Text("second"),ModelChunk.Done),router.stream(ModelRequest(config,emptyList())).toList())
        assertEquals("second-model",manager.registry.require(ModelCatalogServiceKey).models(config).single().id)
        manager.disable("test.second")
        assertEquals(listOf("first"),registry.available.value.map { it.id })
        assertFailsWith<ModelException> { router.stream(ModelRequest(config,emptyList())).toList() }
        manager.enableAll()
        assertEquals(2,registry.available.value.size)
        manager.shutdown()
        assertTrue(registry.available.value.isEmpty())
    }
    @Test fun staleDisposerCannotRemoveNewRegistrationAndDuplicatesFail() {
        val registry = ModelProviderRegistry()
        val provider = object : ModelProvider {
            override val id = "example"
            override fun stream(request: ModelRequest) = flowOf(ModelChunk.Done)
        }
        val info = ModelProviderInfo("example","Example","https://example.com","")
        val old = registry.register(info,provider)
        assertFailsWith<IllegalStateException> { registry.register(info,provider) }
        old()
        registry.register(info,provider)
        old()
        assertSame(provider,registry.provider("example"))
        assertFailsWith<ModelException> { registry.catalog("example") }
    }
}
