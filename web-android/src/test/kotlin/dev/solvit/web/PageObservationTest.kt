package dev.solvit.web

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PageObservationTest {
    @Test fun observedContentKeepsSourcesAndRejectsUnsafeLinks() {
        val page = PageObservation.parse("""{"url":"https://example.org","title":"Source","text":"正文",
            "links":[{"url":"https://example.org/a","title":"A"},{"url":"file:///private/key"},
            {"url":"https://example.org/a","title":"duplicate"},{"url":"https://user:password@example.org"}]}""")
        assertEquals("正文", page.text)
        assertEquals(listOf("https://example.org/a"), page.links.map { it.url })
        assertFalse(page.truncated)
    }
    @Test fun addressesAndOutputAreBounded() {
        for (url in listOf("http://example.org", "file:///private/key", "javascript:alert(1)", "https://user:secret@example.org"))
            assertFailsWith<IllegalArgumentException> { PageObservation.validateUrl(url) }
        val page = PageObservation.parse("""{"url":"https://example.org","text":"${"文".repeat(20_000)}"}""")
        assertEquals(16_000, page.text.length)
        assertTrue(page.truncated)
    }
}
