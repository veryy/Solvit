package dev.solvit.app

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
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
import dev.solvit.core.PageTerminalAccess
import androidx.compose.material3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import dev.solvit.plugins.ScriptPageView
import dev.solvit.plugins.ScriptWebPageEvent
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

// Temporary native-crash probe; remove before publishing the eventual fix.
private val nextPageLifecycleTrace = AtomicInteger()

private const val PLUGIN_ORIGIN = "https://solvit-plugin.invalid"
private const val PAGE_CSP = "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"

private data class TerminalPageConfirmation(val title: String, val detail: String,
    val answer: CompletableDeferred<Boolean>)

/** Generic offline container. The plugin's bundled HTML/CSS/JS owns all business layout. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginWebPage(view: ScriptPageView, document: ExtensionPageDocument,
    resource: suspend (String) -> ExtensionPageResource, busy: Boolean, modifier: Modifier = Modifier,
    terminalAccess: PageTerminalAccess? = null, onEvent: (ScriptWebPageEvent) -> Unit) {
    // A new document owns a new AndroidView node, bridge token and coroutine scope.
    // Updating factory alone would retain the previous, already destroyed View.
    key(document) {
        PluginWebPageDocument(view, document, resource, busy, modifier, terminalAccess, onEvent)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginWebPageDocument(view: ScriptPageView, document: ExtensionPageDocument,
    resource: suspend (String) -> ExtensionPageResource, busy: Boolean, modifier: Modifier,
    terminalAccess: PageTerminalAccess?, onEvent: (ScriptWebPageEvent) -> Unit) {
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
    val latestTerminal by rememberUpdatedState(terminalAccess)
    val scope = rememberCoroutineScope()
    val terminalMutex = remember(document) { Mutex() }
    val terminalPending = remember(document) { AtomicInteger(0) }
    var terminalConfirmation by remember(document) { mutableStateOf<TerminalPageConfirmation?>(null) }
    var failed by remember(document) { mutableStateOf(false) }
    val live = remember(document) { AtomicBoolean(true) }
    val traceId = remember(document) { nextPageLifecycleTrace.incrementAndGet() }
    fun trace(phase: String) {
        if (BuildConfig.DEBUG) android.util.Log.i("WEB_PAGE_LIFECYCLE_DIAG", "$traceId|$phase|live=${live.get()}")
    }
    fun release(browser: WebView) {
        trace("release.request")
        if (live.compareAndSet(true, false)) {
            trace("listener.remove.before")
            runCatching { WebViewCompat.removeWebMessageListener(browser, "solvitHost") }
            trace("listener.remove.after")
            trace("parent.remove.before")
            (browser.parent as? android.view.ViewGroup)?.removeView(browser)
            trace("parent.remove.after")
            trace("stop.before")
            runCatching { browser.stopLoading() }
            trace("stop.after")
            trace("children.remove.before")
            runCatching { browser.removeAllViews() }
            trace("children.remove.after")
            trace("destroy.before")
            browser.destroy()
            trace("destroy.after")
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
        trace("construct.before")
        WebView(context).apply {
            trace("construct.after")
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
                    trace("resource.begin")
                    try {
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
                    } finally { trace("resource.return") }
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    trace("receivedError.main=${request.isForMainFrame}")
                    if (request.isForMainFrame && live.get()) { failed = true; release(view) }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    trace("renderGone.crashed=${detail.didCrash()}")
                    failed = true; release(view); return true
                }
            }
            WebViewCompat.addWebMessageListener(this, "solvitHost", setOf(PLUGIN_ORIGIN)) { web, message, origin, mainFrame, reply ->
                trace("message.enter")
                if (!live.get() || !mainFrame || origin.scheme != "https" || origin.host != "solvit-plugin.invalid" ||
                    origin.port !in setOf(-1, 443)) return@addWebMessageListener
                runCatching {
                    val body = message.data ?: error("Empty page request")
                    require(body.toByteArray(Charsets.UTF_8).size <= ScriptPageView.MAX_BYTES)
                    val input = Json.parseToJsonElement(body).jsonObject
                    if (input == buildJsonObject { put("type", "ready") }) {
                        trace("ready.evaluate.before")
                        web.evaluateJavascript("window.solvitPageUpdate&&window.solvitPageUpdate($latestState)", null)
                        trace("ready.evaluate.after")
                    } else if (input["type"] == JsonPrimitive("terminal")) {
                        check(!latestBusy && input.keys == setOf("type","token","id","operation","arguments"))
                        val requestToken = input.getValue("token").jsonPrimitive.content
                        check(requestToken == latestToken)
                        val requestId = input.getValue("id").jsonPrimitive.content.also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) }
                        val operation = input.getValue("operation").jsonPrimitive.content
                        val args = input.getValue("arguments").jsonObject
                        val access = checkNotNull(latestTerminal) { "No terminal page capability" }
                        if (terminalPending.incrementAndGet() > 64) {
                            terminalPending.decrementAndGet(); error("Terminal page queue full")
                        }
                        scope.launch {
                            try {
                                val result = terminalMutex.withLock {
                                    suspend fun authorize() { check(live.get() && requestToken == latestToken && !latestBusy) }
                                    authorize()
                                    var confirmed = false
                                    if (operation == "open" || operation == "createShell" || operation == "attach") {
                                        val detail = if (operation == "open") {
                                            val names = Json.parseToJsonElement(access.request("snapshot", "{}", authorize = { authorize() })).jsonObject
                                            val name = names.getValue("shells").jsonArray.firstOrNull { it.jsonObject["id"] == args["shellId"] }
                                                ?.jsonObject?.get("name")?.jsonPrimitive?.content ?: error("Shell does not exist")
                                            "允许此插件页面控制当前聊天的「$name」终端？页面可发送命令和交互输入；关闭页面后停止接受输入，Shell 会保留到明确关闭或应用退出。"
                                        } else if (operation == "attach") {
                                            val names=Json.parseToJsonElement(access.request("snapshot", "{}", authorize = { authorize() })).jsonObject
                                            val candidate=names.getValue("terminals").jsonArray.single { it.jsonObject["id"]==args["id"] }.jsonObject
                                            "允许此页面查看并控制当前聊天的 ${candidate.getValue("owner").jsonPrimitive.content} 终端？页面将能发送命令、交互输入及关闭会话。"
                                        } else "在当前工作区创建 Shell：${args["name"]?.jsonPrimitive?.content?.take(80).orEmpty()}"
                                        val answer = CompletableDeferred<Boolean>()
                                        val question = TerminalPageConfirmation("终端授权", detail, answer)
                                        terminalConfirmation = question
                                        try { confirmed = answer.await() }
                                        finally { if (terminalConfirmation === question) terminalConfirmation = null }
                                        check(confirmed) { "Terminal authorization declined" }
                                    }
                                    access.request(operation, args.toString(), confirmed) { authorize() }
                                }
                                if (live.get()) reply.postMessage(buildJsonObject {
                                    put("type","terminalReply"); put("id",requestId); put("result",Json.parseToJsonElement(result))
                                }.toString())
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                if (live.get()) reply.postMessage(buildJsonObject {
                                    put("type","terminalReply"); put("id",requestId); put("error","终端操作失败或授权已失效，请重新打开页面。")
                                }.toString())
                            } finally { terminalPending.decrementAndGet() }
                        }
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
            trace("load.before")
            loadUrl("$PLUGIN_ORIGIN/${document.entry}")
            trace("load.after")
        }
    }
    Column(modifier.fillMaxSize()) {
        if (failed) UiText("插件页面加载失败，请关闭后重新打开。", Modifier.weight(1f))
        else AndroidView(factory = { trace("androidView.factory"); browser }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("plugin_web_page"),
            onRelease = { trace("androidView.release"); release(it) }, update = {
            trace("androidView.update.enter")
            if (live.get()) {
                it.setBackgroundColor(colors.surface.toArgb())
                trace("update.evaluate.before")
                it.evaluateJavascript("window.solvitPageUpdate&&window.solvitPageUpdate($state)", null)
                trace("update.evaluate.after")
            }
        })
        terminalConfirmation?.let { question ->
            Surface(Modifier.fillMaxWidth().testTag("terminal_page_approval"),
                color=colors.surfaceContainerHigh,shape=MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    UiText(question.title,style=MaterialTheme.typography.titleSmall)
                    UiText(question.detail,Modifier.heightIn(max=140.dp).verticalScroll(rememberScrollState()),
                        style=MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick={question.answer.complete(false)},modifier=Modifier.testTag("terminal_page_reject")) { UiText("取消") }
                        TextButton(onClick={question.answer.complete(true)},modifier=Modifier.testTag("terminal_page_allow")) { UiText("允许") }
                    }
                }
            }
        }
    }
}
