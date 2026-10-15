package dev.solvit.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.core.ExtensionPageDocument
import dev.solvit.core.ExtensionPageResource
import dev.solvit.plugins.ScriptPageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test

/** Actual AndroidView ownership when an offline page changes at the same composition slot. */
class PluginWebPageLifecycleTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun browser(view: View): WebView? = if (view is WebView) view else
        (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { browser(group.getChildAt(it)) }
        }

    @Test fun replacingDocumentReplacesAttachedBrowserAndDeliversOnlyCurrentPageState() {
        var document by mutableStateOf(ExtensionPageDocument("ui/first.html"))
        val view = ScriptPageView.parse("""{"title":"Lifecycle","nodes":[]}""")
        val resources = listOf("first", "second").associate { name ->
            "ui/$name.html" to ExtensionPageResource("text/html", """
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <h1>$name</h1><script>
                window.solvitPageUpdate = state => { window.currentTitle = state.view.title; };
                solvitHost.postMessage(JSON.stringify({type:'ready'}));
                </script>
            """.trimIndent())
        }
        rule.runOnUiThread {
            rule.activity.setContent { MaterialTheme {
                PluginWebPage(view, document, { path -> resources.getValue(path) }, busy = false) {}
            } }
        }
        val page = HtmlPageProbe(rule.activity)
        rule.waitUntil(10_000) { runCatching { page.js("window.currentTitle") == "\"Lifecycle\"" }.getOrDefault(false) }
        assertEquals("\"first\"", page.js("document.querySelector('h1').textContent"))
        var first: WebView? = null
        rule.runOnUiThread { first = browser(rule.activity.window.decorView) }
        assertNotNull(first)

        rule.runOnIdle { document = ExtensionPageDocument("ui/second.html") }
        rule.waitForIdle()
        var second: WebView? = null
        rule.runOnUiThread { second = browser(rule.activity.window.decorView) }
        assertNotNull("The new document must have an attached WebView, not a detached destroyed instance", second)
        assertNotSame("AndroidView must not retain the previous document's browser", first, second)
        rule.waitUntil(10_000) { runCatching { page.js("window.currentTitle") == "\"Lifecycle\"" }.getOrDefault(false) }
        assertEquals("\"second\"", page.js("document.querySelector('h1').textContent"))
    }
}
