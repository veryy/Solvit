package dev.solvit.updates

import dev.solvit.core.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.*

class ReleaseUpdatesTest {
    private fun release(tag: String = "v0.2.0", apk: String = "https://github.com/veryy/Solvit/releases/download/v0.2.0/solvit-debug.apk", assets: Boolean = true) = """
        {"tag_name":"$tag","draft":false,"prerelease":false,
         "html_url":"https://github.com/veryy/Solvit/releases/tag/$tag","body":"Changes",
         "assets":${if (assets) """[{"name":"solvit-debug.apk","state":"uploaded","size":100,"browser_download_url":"$apk"}]""" else "[]"}}
    """.trimIndent()

    private suspend fun check(body: String, installed: String = "0.1.0", code: Int = 200): UpdateResult {
        val server = MockWebServer()
        val client = OkHttpClient()
        server.start()
        return try {
            server.enqueue(MockResponse().setResponseCode(code).setBody(body))
            GitHubReleaseUpdates(client, server.url("/latest").toString()).check(installed)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun newerReleaseOffersVerifiedRepositoryApk() = runTest {
        val result = assertIs<UpdateResult.Available>(check(release()))
        assertEquals("0.2.0", result.release.version)
        assertEquals("Changes", result.release.notes)
    }

    @Test fun versionsUseNumericComparison() = runTest {
        assertIs<UpdateResult.Available>(check(release("v0.10.0"), "0.9.0"))
        assertEquals(UpdateResult.Current, check(release("v0.9.0"), "0.10.0"))
        assertEquals(UpdateResult.Current, check(release("v0.2.0"), "0.2.0"))
    }

    @Test fun missingReleaseIsNotAnUpdate() = runTest { assertEquals(UpdateResult.Current, check("", code = 404)) }

    @Test fun missingApkCannotProduceBrokenDownloadCard() = runTest {
        assertFailsWith<IOException> { check(release(assets = false)) }
    }

    @Test fun foreignApkAndMalformedVersionsAreRejected() = runTest {
        assertFailsWith<IOException> { check(release(apk = "https://example.com/solvit.apk")) }
        assertFailsWith<IOException> { check(release(tag = "v0.2.0-beta.1")) }
    }

    @Test fun rateLimitsAndInvalidJsonFailExplicitly() = runTest {
        assertFailsWith<IOException> { check("{}", code = 403) }
        assertFailsWith<IOException> { check("invalid json") }
    }

    @Test fun pluginPublishesServiceAndUnloadsIt() = runTest {
        val manager = PluginManager(scope = backgroundScope)
        manager.load(ReleaseUpdatesPlugin())
        manager.enableAll()
        assertTrue(manager.registry.contains(UpdateServiceKey))
        manager.shutdown()
        assertFalse(manager.registry.contains(UpdateServiceKey))
    }
}
