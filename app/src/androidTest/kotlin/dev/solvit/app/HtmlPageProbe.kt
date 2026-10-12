package dev.solvit.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.*

/** Evaluate the actual loaded document; callbacks and storage are never mocked. */
internal class HtmlPageProbe(private val activity: MainActivity) {
    private fun browser(view: View): WebView? = if (view is WebView) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { browser(group.getChildAt(it)) } }
    fun js(code: String): String = runBlocking {
        val result = CompletableDeferred<String>()
        activity.runOnUiThread {
            val web = browser(activity.window.decorView)
            if (web == null) result.completeExceptionally(IllegalStateException("No active HTML page"))
            else web.evaluateJavascript(code) { result.complete(it) }
        }
        withTimeout(10_000) { result.await() }
    }
}
