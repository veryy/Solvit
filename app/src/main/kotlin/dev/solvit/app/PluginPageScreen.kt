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

/** Uses service contracts; plugin-specific business logic lives in JS/TS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginPageScreen(title: String, open: suspend () -> ExtensionPageSession, onDismiss: () -> Unit) {
    var session by remember { mutableStateOf<ExtensionPageSession?>(null) }
    var snapshot by remember { mutableStateOf<ExtensionPageSnapshot?>(null) }
    var view by remember { mutableStateOf<ScriptPageView?>(null) }
    var document by remember { mutableStateOf<ExtensionPageDocument?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val documents = rememberPluginPageDocuments()
    // LaunchedEffect owns lifetime, including a racing open during dismissal.
    LaunchedEffect(Unit) {
        var owned: ExtensionPageSession? = null
        try {
            val current = open(); owned = current; session = current
            current.attachDocuments(documents)
            val initial = current.open()
            document = current.document()
            snapshot = initial; view = ScriptPageView.parse(initial.json); busy = false
            current.closed.collect { closed -> if (closed) {
                view = null; snapshot = null; busy = false; error = "插件页面已关闭，请检查启用状态和权限。"
            } }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { busy = false; error = "插件页面无法打开，请检查启用状态和权限。" }
        finally { withContext(NonCancellable) { owned?.close() } }
    }
    FullScreenPage(onDismiss, secure = document?.sensitive == true) {
        Scaffold(topBar = { TopAppBar(title = { Text(view?.title ?: title) }, navigationIcon = {
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("plugin_page_close")) { UiIcon(Icons.Default.Close, "关闭插件界面") }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { UiText(it, Modifier.padding(12.dp).testTag("plugin_page_error"), color = MaterialTheme.colorScheme.error) }
                view?.let { displayed ->
                    PluginPageContent(displayed, busy, Modifier.weight(1f), document,
                        resource = { path -> checkNotNull(session).resource(path) }) { event ->
                        val current = session
                        val revision = snapshot?.revision
                        if (!busy && event.view === view && current != null && revision != null) {
                            busy = true; error = null
                            scope.launch {
                                try {
                                    val next = current.dispatch(revision, event.nodeId, event.value, event.fields, event.confirmed)
                                    snapshot = next; view = ScriptPageView.parse(next.json)
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "插件页面操作失败，请重新打开后重试。" }
                                finally { busy = false }
                            }
                        }
                    }
                }
            }
        }
    }
}
