package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** A page owns one native secret form. Field values never enter WebView or QuickJS state. */
internal class PluginPageGitCredentials : PageGitCredentialPrompt {
    class Pending(val endpoint: GitRemoteEndpoint, val configured: Boolean,
        val authorize: suspend () -> Unit, val save: suspend (String, CharArray) -> Unit,
        val remove: suspend () -> Unit) {
        val result = CompletableDeferred<GitCredentialOutcome>()
        var active = true
    }
    val pending = MutableStateFlow<Pending?>(null)
    private var closed = false

    override suspend fun edit(endpoint: GitRemoteEndpoint, configured: Boolean, authorize: suspend () -> Unit,
        save: suspend (String, CharArray) -> Unit, remove: suspend () -> Unit): GitCredentialOutcome {
        val request = Pending(endpoint, configured, authorize, save, remove)
        withContext(Dispatchers.Main.immediate) {
            check(!closed && pending.value == null) { "已有凭据输入尚未结束" }
            authorize(); pending.value = request
        }
        return try { request.result.await() } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                request.active = false
                if (pending.value === request) pending.value = null
            }
        }
    }
    private suspend fun verify(request: Pending) {
        check(!closed && pending.value === request && request.active && request.result.isActive) { "凭据输入已关闭" }
        request.authorize()
    }
    suspend fun save(request: Pending, username: String, secret: CharArray) {
        try {
            verify(request)
            request.save(username, secret)
            verify(request)
            request.result.complete(GitCredentialOutcome.SAVED)
        } finally { secret.fill('\u0000') }
    }
    suspend fun remove(request: Pending) {
        verify(request); request.remove(); verify(request)
        request.result.complete(GitCredentialOutcome.REMOVED)
    }
    fun cancel(request: Pending) {
        if (pending.value === request) {
            request.active = false
            request.result.complete(GitCredentialOutcome.CANCELLED)
            pending.value = null
        }
    }
    fun close() {
        closed = true
        pending.value?.let { it.active = false; it.result.cancel() }
        pending.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberPluginPageGitCredentials(): PageGitCredentialPrompt {
    val input = remember { PluginPageGitCredentials() }
    val pending by input.pending.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(input) { onDispose { input.close() } }
    pending?.let { request -> key(request) {
        var username by remember { mutableStateOf(if (request.endpoint.host == "github.com") "x-access-token" else "git") }
        var secret by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf(false) }
        DisposableEffect(request) { onDispose { secret = ""; username = "" } }
        FullScreenPage(onDismissRequest = { if (!saving) input.cancel(request) }, secure = true) {
            Scaffold(topBar = { TopAppBar(title = { UiText("仓库凭据") }) }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(request.endpoint.url, Modifier.testTag("git_credential_endpoint"))
                    UiText("凭据仅加密保存在本机，插件和模型不能读取。")
                    OutlinedTextField(username, { username = it }, label = { UiText("用户名") }, singleLine = true,
                        enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("git_credential_username"))
                    OutlinedTextField(secret, { secret = it }, label = { UiText("访问令牌或密码") }, singleLine = true,
                        enabled = !saving, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("git_credential_secret"))
                    if (failure) UiText("凭据操作失败，请检查输入或重新打开工作区。", color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        val value = secret.toCharArray(); saving = true; failure = false
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try { input.save(request, username, value); secret = "" }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { failure = true }
                            finally { value.fill('\u0000'); saving = false }
                        }
                    }, enabled = !saving && username.isNotBlank() && secret.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("git_credential_save")) { UiText("保存") }
                    if (request.configured) OutlinedButton(onClick = {
                        saving = true; failure = false
                        scope.launch {
                            try { input.remove(request); secret = "" }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { failure = true }
                            finally { saving = false }
                        }
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("git_credential_remove")) { UiText("移除凭据") }
                    TextButton(onClick = { secret = ""; input.cancel(request) }, enabled = !saving,
                        modifier = Modifier.fillMaxWidth().testTag("git_credential_cancel")) { UiText("取消") }
                }
            }
        }
    } }
    return input
}
