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
    }
}
