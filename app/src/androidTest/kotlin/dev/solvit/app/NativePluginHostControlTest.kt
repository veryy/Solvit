package dev.solvit.app

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Mirror the minimal production host's browser/bridge/resource behavior in a native container.
 * No Compose AndroidView or plugin business services; this is a diagnostic, not a new host.
 */
@RunWith(AndroidJUnit4::class)
class NativePluginHostControlTest {
    @SuppressLint("SetJavaScriptEnabled")
    @Test fun minimalBridgeAndResourceLifecycleInNativeContainer() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val app = instrument.targetContext.applicationContext as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val repetitions = InstrumentationRegistry.getArguments().getString("workspaceRecreations")?.toInt() ?: 3
        require(repetitions in 3..30)
        val html = """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <div id="state">host control</div><script>
            window.solvitPageUpdate = state => { document.getElementById('state').textContent = state.view.title; };
            solvitHost.postMessage(JSON.stringify({type:'ready'}));
            </script>
        """.trimIndent()
        val state = """{"view":{"title":"Lifecycle control","nodes":[]}}"""
        val csp = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            repeat(repetitions) { index ->
                scenario.recreate()
                lateinit var browser: WebView
                lateinit var container: FrameLayout
                val live = AtomicBoolean(true)
                var created = false
                fun release(web: WebView) {
                    if (live.compareAndSet(true, false)) {
                        runCatching { WebViewCompat.removeWebMessageListener(web, "solvitHost") }
                        (web.parent as? ViewGroup)?.removeView(web)
                        runCatching { web.stopLoading() }
                        runCatching { web.removeAllViews() }
                        web.destroy()
                    }
                }
                try {
                    scenario.onActivity { activity ->
                        container = FrameLayout(activity)
                        activity.setContentView(container)
                        browser = WebView(activity)
                        created = true
                        browser.apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = true
                            settings.domStorageEnabled = false
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webChromeClient = object : WebChromeClient() {
                                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                    val uri = request.url
                                    val accepted = live.get() && uri.scheme == "https" && uri.host == "solvit-plugin.invalid" &&
                                        uri.port in setOf(-1, 443) && request.method == "GET" && uri.path == "/ui/control.html"
                                    return if (!accepted) WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                                        ByteArrayInputStream(ByteArray(0)))
                                    else WebResourceResponse("text/html", "UTF-8", 200, "OK",
                                        mapOf("Content-Security-Policy" to csp, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                                        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (request.isForMainFrame && live.get()) release(view)
                                }
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    release(view); return true
                                }
                            }
                            WebViewCompat.addWebMessageListener(this, "solvitHost", setOf("https://solvit-plugin.invalid")) { web, message, origin, mainFrame, _ ->
                                if (live.get() && mainFrame && origin.scheme == "https" && origin.host == "solvit-plugin.invalid" &&
                                    origin.port in setOf(-1, 443) && message.data == """{"type":"ready"}""") {
                                    web.evaluateJavascript("window.solvitPageUpdate&&window.solvitPageUpdate($state)", null)
                                }
                            }
                            loadUrl("https://solvit-plugin.invalid/ui/control.html")
                        }
                        container.addView(browser, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        if (live.get()) browser.evaluateJavascript("window.solvitPageUpdate&&window.solvitPageUpdate($state)", null)
                    }
                    instrument.waitForIdleSync()
                    scenario.onActivity {
                        assertTrue("The native host must actually attach its browser", browser.isAttachedToWindow)
                        assertSame(container, browser.parent)
                        release(browser)
                        assertEquals(0, container.childCount)
                    }
                    instrument.waitForIdleSync()
                    android.util.Log.i("WORKSPACE_PAGE_DIAG", "NATIVE_PLUGIN_HOST_COMPLETED|${index + 1}|$repetitions")
                } finally {
                    if (created) scenario.onActivity { release(browser) }
                }
            }
        }
    }
}
