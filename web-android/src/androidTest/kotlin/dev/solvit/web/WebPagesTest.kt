package dev.solvit.web

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebPagesTest {
    @Test fun actualWebViewLoadsScriptGeneratedTextAndExtractsLinks() = runBlocking {
        val service = AndroidWebPages(ApplicationProvider.getApplicationContext())
        val page = service.observeFixture("https://example.org/reference", """
            <html><head><title>Fixture source</title></head><body><main>
            <p id="content">before</p><a href="https://example.org/source">Source link</a>
            <script>setTimeout(() => document.getElementById('content').textContent='Rendered by browser',100);</script>
            </main></body></html>
        """.trimIndent())
        assertEquals("Fixture source", page.title)
        assertTrue(page.text.contains("Rendered by browser"))
        assertFalse(page.text.contains("getElementById"))
        assertEquals("https://example.org/source", page.links.single().url)
        assertFalse(page.searchResults)
        val search = service.observeFixture("https://www.bing.com/?q=Kotlin", """
            <html><head><title>Search fixture</title></head><body><main>
            <li class="b_algo"><h2><a href="https://kotlinlang.org/docs/home.html">Kotlin docs</a></h2><p>Official source</p></li>
            <a href="https://example.org/footer">Footer</a>
            </main></body></html>
        """.trimIndent())
        assertTrue(search.searchResults)
        assertEquals("https://kotlinlang.org/docs/home.html", search.links.single().url)
        val mobile = service.observeFixture("https://www.bing.com/?q=Kotlin", """
            <html><head><title>Mobile search fixture</title></head><body><main>
            <div class="b_algo"><div class="b_algoheader"><a href="https://kotlinlang.org/docs/home.html"><h2>Kotlin docs</h2></a></div><p>Official mobile source</p></div>
            <a href="https://example.org/footer">Footer</a>
            </main></body></html>
        """.trimIndent())
        assertTrue(mobile.searchResults)
        assertEquals("https://kotlinlang.org/docs/home.html", mobile.links.single().url)
        assertTrue(mobile.links.single().snippet.contains("Official mobile source"))
    }
}
