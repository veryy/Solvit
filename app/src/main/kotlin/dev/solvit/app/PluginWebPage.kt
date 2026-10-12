package dev.solvit.app

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.solvit.core.ExtensionPageDocument
import dev.solvit.core.ExtensionPageResource
import dev.solvit.plugins.ScriptPageView
import dev.solvit.plugins.ScriptWebPageEvent
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

private const val PLUGIN_ORIGIN = "https://solvit-plugin.invalid"
private const val PAGE_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

/** Generic offline container. The plugin's bundled HTML/CSS/JS owns all business layout. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebPage(view: ScriptPageView, document: ExtensionPageDocument,
    resource: suspend (String) -> ExtensionPageResource, busy: Boolean, modifier: Modifier = Modifier,
    onEvent: (ScriptWebPageEvent) -> Unit) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        UiText("当前系统 WebView 不支持插件页面消息桥，请更新系统 WebView。", modifier)
        return
    }
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val locale = LocalUiLanguage.current
    val token = remember(view) { UUID.randomUUID().toString() }
    val latestView by rememberUpdatedState(view)
    val latestToken by rememberUpdatedState(token)
    val latestBusy by rememberUpdatedState(busy)
    val latestResource by rememberUpdatedState(resource)
    val latestEvent by rememberUpdatedState(onEvent)
    var failed by remember(document) { mutableStateOf(false) }
    val live = remember(document) { AtomicBoolean(true) }
    fun release(browser: WebView) {
        if (live.compareAndSet(true, false)) {
            runCatching { WebViewCompat.removeWebMessageListener(browser, "solvitHost") }
            (browser.parent as? android.view.ViewGroup)?.removeView(browser)
            runCatching { browser.stopLoading() }
            runCatching { browser.removeAllViews() }
            browser.destroy()
        }
    }
    val state = buildJsonObject {
        put("token", token); put("busy", busy); put("locale", locale)
        put("view", Json.parseToJsonElement(view.toJson()))
        putJsonObject("theme") {
            fun color(key: String, value: androidx.compose.ui.graphics.Color) {
                put(key, "#%06x".format(value.toArgb() and 0xffffff))
            }
            color("surface", colors.surface); color("onSurface", colors.onSurface)
            color("primary", colors.primary); color("outline", colors.outline)
            color("surfaceContainer", colors.surfaceContainer); color("onPrimary", colors.onPrimary)
        }
    }.toString()
    val latestState by rememberUpdatedState(state)
    val browser = remember(document) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.domStorageEnabled = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            setBackgroundColor(colors.surface.toArgb())
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val result = if (live.get() && uri.scheme == "https" && uri.host == "solvit-plugin.invalid" &&
                        uri.port in setOf(-1, 443) && request.method == "GET") runCatching {
                        runBlocking { latestResource(uri.path.orEmpty().removePrefix("/")) }
                    }.getOrNull() else null
                    return if (result == null) WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                        ByteArrayInputStream(ByteArray(0)))
                    else WebResourceResponse(result.mimeType, "UTF-8", 200, "OK",
                        mapOf("Content-Security-Policy" to PAGE_CSP, "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"),
                        ByteArrayInputStream(result.content.toByteArray(Charsets.UTF_8)))
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && live.get()) { failed = true; release(view) }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    failed = true; release(view); return true
                }
            }
            WebViewCompat.addWebMessageListener(this, "solvitHost", setOf(PLUGIN_ORIGIN)) { web, message, origin, mainFrame, reply ->
                if (!live.get() || !mainFrame || origin.scheme != "https" || origin.host != "solvit-plugin.invalid" ||
                    origin.port !in setOf(-1, 443)) return@addWebMessageListener
                runCatching {
                    val body = message.data ?: error("Empty page request")
                    require(body.toByteArray(Charsets.UTF_8).size <= ScriptPageView.MAX_BYTES)
                    val input = Json.parseToJsonElement(body).jsonObject
                    if (input == buildJsonObject { put("type", "ready") }) {
                        web.evaluateJavascript("window.solvitPageUpdate&&window.solvitPageUpdate($latestState)", null)
                    } else {
                        check(!latestBusy)
                        val event = ScriptWebPageEvent.parse(latestView, latestToken, body)
                        latestEvent(event)
                        if (event.node["confirm"] != null || event.node["hostAction"] != null) {
                            reply.postMessage(buildJsonObject { put("type", "confirmation"); put("token", latestToken) }.toString())
                        }
                    }
                }.onFailure {
                    // Never echo form data, secrets or script exception text into diagnostics.
                    reply.postMessage("{\"type\":\"error\",\"message\":\"Page request rejected\"}")
                }
            }
            loadUrl("$PLUGIN_ORIGIN/${document.entry}")
        }
    }
    DisposableEffect(browser) {
        onDispose {
            release(browser)
        }
    }
    if (failed) UiText("插件页面加载失败，请关闭后重新打开。", modifier)
    else AndroidView(factory = { browser }, modifier = modifier.fillMaxSize().testTag("plugin_web_page"), update = {
        it.setBackgroundColor(colors.surface.toArgb())
        it.evaluateJavascript("window.solvitPageUpdate&&window.solvitPageUpdate($state)", null)
    })
}
