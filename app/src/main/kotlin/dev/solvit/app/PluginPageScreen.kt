package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import dev.solvit.plugins.ScriptPageView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

/** Uses service contracts; plugin-specific business logic lives in JS/TS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPageScreen(title: String, open: suspend () -> ExtensionPageSession, embedded: Boolean = false, onDismiss: () -> Unit) {
    var session by remember { mutableStateOf<ExtensionPageSession?>(null) }
    var snapshot by remember { mutableStateOf<ExtensionPageSnapshot?>(null) }
    var view by remember { mutableStateOf<ScriptPageView?>(null) }
    var document by remember { mutableStateOf<ExtensionPageDocument?>(null) }
    var terminal by remember { mutableStateOf<PageTerminalAccess?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var dispatchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val documents = rememberPluginPageDocuments()
    val gitCredentials = rememberPluginPageGitCredentials()
    // collectLatest waits for the previous attempt's non-cancellable cleanup.
    // A reopen reads current state; it never repeats the last dispatched action.
    LaunchedEffect(Unit) {
        snapshotFlow { attempt }.collectLatest { generation ->
            var owned: ExtensionPageSession? = null
            try {
                busy = true; error = null
                val current = open(); owned = current
                currentCoroutineContext().ensureActive()
                session = current
                current.attachDocuments(documents)
                current.attachGitCredentialPrompt(gitCredentials)
                val initial = current.open()
                val nextDocument = current.document()
                val nextTerminal = current.terminalAccess()
                currentCoroutineContext().ensureActive()
                document = nextDocument; terminal = nextTerminal
                snapshot = initial; view = ScriptPageView.parse(initial.json); busy = false
                current.closed.collect { closed -> if (closed && generation == attempt) {
                    view = null; snapshot = null; document = null; terminal = null
                    busy = false; error = "插件页面已关闭，请检查启用状态和权限。"
                } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == attempt) { busy = false; error = "插件页面无法打开，请检查启用状态和权限。" }
            } finally {
                if (session === owned) { session = null; view = null; snapshot = null; document = null; terminal = null }
                withContext(NonCancellable) {
                    dispatchJob?.cancelAndJoin(); dispatchJob = null
                    owned?.close()
                }
            }
        }
    }
    val content: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.fillMaxSize()) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("plugin_page_loading"))
                error?.let { message ->
                    Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            UiText(message, Modifier.testTag("plugin_page_error"),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            UiText("重新打开页面不会重复执行刚才的操作。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = { busy = true; attempt++ }, enabled = !busy,
                                modifier = Modifier.testTag("plugin_page_reopen")) { UiText("重新打开页面") }
                        }
                    }
                }
                view?.let { displayed ->
                    PluginPageContent(displayed, busy, Modifier.weight(1f), document,
                        resource = { path -> checkNotNull(session).resource(path) }, terminalAccess = terminal) { event ->
                        val current = session
                        val revision = snapshot?.revision
                        if (!busy && event.view === view && current != null && !current.closed.value && revision != null) {
                            val generation = attempt
                            busy = true; error = null
                            dispatchJob = scope.launch {
                                try {
                                    val next = current.dispatch(revision, event.nodeId, event.value, event.fields, event.confirmed)
                                    if (session === current && generation == attempt && !current.closed.value) {
                                        snapshot = next; view = ScriptPageView.parse(next.json)
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) {
                                    if (session === current && generation == attempt) error = "插件页面操作失败，请重新打开后重试。"
                                }
                                finally { if (session === current && generation == attempt) busy = false }
                            }
                        }
                    }
                }
            }
    }
    if (embedded) content(Modifier) else FullScreenPage(onDismiss, secure = document?.sensitive == true) {
        Scaffold(topBar = { TopAppBar(title = { Text(view?.title ?: title) }, navigationIcon = {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("plugin_page_close")) { UiIcon(Icons.Default.Close, "关闭插件界面") }
        }) }) { padding -> content(Modifier.padding(padding).consumeWindowInsets(padding)) }
    }
}
