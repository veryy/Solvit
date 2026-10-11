package dev.solvit.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Serial, bounded anonymous browser observations, separate from TS search policies. */
class AndroidWebPages(context: Context, private val timeoutMs: Long = 7_000) : WebPageService {
    private val application = context.applicationContext
    private val lock = Mutex()
    override suspend fun visit(url: String): WebPage = observe(PageObservation.validateUrl(url), null)

    /** Instrumentation exercises real WebView DOM loading with a controlled offline document. */
    internal suspend fun observeFixture(url: String, html: String): WebPage = observe(PageObservation.validateUrl(url), html)

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun observe(url: String, fixture: String?): WebPage = withTimeout(timeoutMs) {
        lock.withLock {
            withContext(Dispatchers.Main.immediate) {
                val view = WebView(application)
                try {
                    view.settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        safeBrowsingEnabled = true
                    }
                    // This app currently has no login WebViews. Browser tools are anonymous;
                    // no shared cookies, app tokens or custom authorization headers are used.
                    CookieManager.getInstance().setAcceptCookie(false)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
                    suspendCancellableCoroutine { continuation ->
                        var starts = 0
                        var extracting = false
                        fun fail(message: String) {
                            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(message))
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                                val blocked = runCatching { PageObservation.validateUrl(request.url.toString()) }.isFailure
                                if (blocked && request.isForMainFrame) fail("网页试图跳转到不支持的地址")
                                return blocked
                            }
                            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (runCatching { PageObservation.validateUrl(request.url.toString()) }.isSuccess) return null
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))
                            }
                            override fun onPageStarted(v: WebView, target: String, favicon: Bitmap?) {
                                if (++starts > 6 || runCatching { PageObservation.validateUrl(target) }.isFailure)
                                    fail("网页跳转次数或地址超出限制")
                            }
                            override fun onReceivedSslError(v: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel(); fail("网页 HTTPS 证书验证失败")
                            }
                            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) fail("网页加载失败（错误码 ${error.errorCode}）")
                            }
                            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) fail("网页返回 HTTP ${response.statusCode}")
                            }
                            override fun onPageFinished(v: WebView, target: String) {
                                if (!continuation.isActive || extracting) return
                                if (runCatching { PageObservation.validateUrl(target) }.isFailure) { fail("网页最终地址无效"); return }
                                extracting = true
                                launch {
                                delay(500) // Permit a bounded initial DOM update after load.
                                if (!continuation.isActive) return@launch
                                v.evaluateJavascript(PageObservation.script) { encoded ->
                                    if (continuation.isActive) {
                                        val result = runCatching {
                                            val payload = JSONTokener(encoded).nextValue() as? String ?: error("网页观察未返回结果")
                                            PageObservation.parse(payload)
                                        }
                                        result.fold({ continuation.resume(it) }, { fail("网页正文提取失败") })
                                    }
                                }
                                }
                            }
                        }
                        if (fixture == null) view.loadUrl(url)
                        else view.loadDataWithBaseURL(url, fixture, "text/html", "UTF-8", url)
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        view.stopLoading(); view.webViewClient = WebViewClient()
                        view.loadUrl("about:blank"); view.removeAllViews(); view.destroy()
                    }
                }
            }
        }
    }
}

class WebPagesPlugin(private val context: Context) : SolvitPlugin {
    override val id = "builtin.web-pages"
    override val provides = setOf(WebPageServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(WebPageServiceKey, AndroidWebPages(this.context)) }
}
