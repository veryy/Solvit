package dev.solvit.provider

import dev.solvit.core.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ModelCatalogTest {
    private fun client() = OkHttpClient()
    private fun close(client: OkHttpClient) { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    @Test fun modelsCanBeDiscoveredBeforeChoosingOneUsingProviderCredentials() = runBlocking<Unit> {
        val server = MockWebServer(); val client = client(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"z-model","owned_by":"provider"},{"id":"a-model","name":"Display name"},{"id":"a-model"},{"id":""},{"id":42}]}"""))
            val config = ModelConfig(server.url("/custom/v1").toString(), "", "PRIVATE_KEY")
            val models = OpenAiModelCatalog(client).models(config)
            assertEquals(listOf("a-model", "z-model"), models.map { it.id })
            assertEquals("Display name", models.first().name)
            val request = server.takeRequest()
            assertEquals("GET", request.method); assertEquals("/custom/v1/models", request.path)
            assertEquals("Bearer PRIVATE_KEY", request.getHeader("Authorization"))
        } finally { close(client); server.shutdown() }
    }
    @Test fun independentSubscriptionUrlDoesNotReceiveApiKeyWithoutExplicitOptIn() = runBlocking<Unit> {
        val server = MockWebServer(); val client = client(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"models":["custom-model",{"name":"other-model"}]}"""))
            val url = server.url("/subscription?token=subscription-token").toString()
            val catalog = OpenAiModelCatalog(client)
            val models = catalog.models(ModelConfig("https://provider.example/v1", "", "PRIVATE_KEY", modelListUrl = url))
            assertEquals(listOf("custom-model", "other-model"), models.map { it.id })
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""["custom-model"]"""))
            catalog.models(ModelConfig("https://provider.example/v1", "", "PRIVATE_KEY", modelListUrl = url, modelListUsesApiKey = true))
            assertEquals("Bearer PRIVATE_KEY", server.takeRequest().getHeader("Authorization"))
        } finally { close(client); server.shutdown() }
    }
    @Test fun redirectsNeverForwardDiscoveryOrCredentialsToAnotherSite() = runBlocking<Unit> {
        val server = MockWebServer(); val destination = MockWebServer(); val client = client()
        server.start(); destination.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/models")))
            val failure = assertFailsWith<ModelException> { OpenAiModelCatalog(client).models(ModelConfig(server.url("/v1").toString(), "", "PRIVATE_KEY")) }
            assertTrue(failure.message!!.contains("重定向"))
            assertEquals(0, destination.requestCount)
        } finally { close(client); server.shutdown(); destination.shutdown() }
    }
    @Test fun invalidEmptyAndAuthenticationFailuresCannotLeakRemoteErrorDetails() = runBlocking<Unit> {
        val server = MockWebServer(); val client = client(); server.start()
        try {
            for ((code, body) in listOf(401 to "PRIVATE_KEY in error", 404 to "not found", 200 to "bad JSON", 200 to """{"data":[]}""", 200 to """{"error":"PRIVATE_KEY"}""")) {
                server.enqueue(MockResponse().setResponseCode(code).setBody(body))
                val failure = assertFailsWith<ModelException> { OpenAiModelCatalog(client).models(ModelConfig(server.url("/v1").toString(), "", "PRIVATE_KEY")) }
                assertFalse(failure.message!!.contains("PRIVATE_KEY"))
            }
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            assertFailsWith<ModelException> { OpenAiModelCatalog(client).models(ModelConfig(server.url("/v1").toString(), "")) }
        } finally { close(client); server.shutdown() }
    }
    @Test fun cancellationClosesPendingBodyAndCatalogRemainsReusable() = runBlocking<Unit> {
        val bodyStarted = CompletableDeferred<Unit>()
        val server = MockWebServer()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { bodyStarted.complete(Unit) }
        }).build()
        server.start()
        try {
            val catalog = OpenAiModelCatalog(client)
            val config = ModelConfig(server.url("/v1").toString(), "")
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"slow"}]}""").setBodyDelay(2, TimeUnit.SECONDS))
            val pending = async(start = CoroutineStart.UNDISPATCHED) { catalog.models(config) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(5000) { bodyStarted.await() }
            withTimeout(1000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"next"}]}"""))
            assertEquals("next", catalog.models(config).single().id)
        } finally { close(client); server.shutdown() }
    }
}
