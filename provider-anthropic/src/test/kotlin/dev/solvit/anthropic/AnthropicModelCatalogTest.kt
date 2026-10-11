package dev.solvit.anthropic

import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import kotlin.test.*

class AnthropicModelCatalogTest {
    @Test fun followsNativePaginationAndRetainsDisplayNames() = runBlocking {
        val server=MockWebServer();server.start();val client=OkHttpClient()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"first","display_name":"First Claude"}],"has_more":true,"last_id":"first"}"""))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"second","display_name":"Second Claude"}],"has_more":false}"""))
            val models=AnthropicModelCatalog(client).models(ModelConfig(server.url("/v1").toString(),"","native-key",providerId="anthropic"))
            assertEquals(listOf("first","second"),models.map { it.id });assertEquals("First Claude",models.first().name)
            assertEquals("native-key",server.takeRequest().getHeader("x-api-key"))
            assertEquals("first",server.takeRequest().requestUrl!!.queryParameter("after_id"))
        } finally { server.shutdown();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    }
    @Test fun independentSubscriptionNeverReceivesKeyUnlessOptedInAndRepeatingCursorFails() = runBlocking {
        val server=MockWebServer();server.start();val client=OkHttpClient()
        try {
            for(optIn in listOf(false,true)) {
                server.enqueue(MockResponse().setBody("""["native-model"]"""))
                val config=ModelConfig("https://api.anthropic.com/v1","","secret",modelListUrl=server.url("/subscription").toString(),modelListUsesApiKey=optIn,providerId="anthropic")
                assertEquals("native-model",AnthropicModelCatalog(client).models(config).single().id)
                val request=server.takeRequest()
                assertEquals(if(optIn) "secret" else null,request.getHeader("x-api-key"));assertNull(request.getHeader("Authorization"))
            }
            repeat(2) { server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}""")) }
            assertFailsWith<ModelException> { AnthropicModelCatalog(client).models(ModelConfig(server.url("/v1").toString(),"",providerId="anthropic")) }
        } finally { server.shutdown();client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
    }
}
