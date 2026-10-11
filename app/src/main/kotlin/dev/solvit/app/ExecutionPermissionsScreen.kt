package dev.solvit.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExecutionPermissionsScreen(system: SystemExecutionService, market: MarketplaceService, approvals: ToolApprovalStore, onDismiss: () -> Unit) {
    val grants by approvals.policies.collectAsStateWithLifecycle()
    val device by system.device.collectAsStateWithLifecycle()
    val modes by system.modes.collectAsStateWithLifecycle()
    val installed by market.installed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rootOwner by remember { mutableStateOf<Pair<String, String>?>(null) }
    fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "权限操作失败" }
            finally { busy = false }
        }
    }
    LaunchedEffect(system) { system.refresh() }
    FullScreenPage(onDismissRequest = onDismiss) {
        Scaffold(topBar = { TopAppBar(title = { UiText("权限与执行方式") }, navigationIcon = {
            IconButton(onClick = onDismiss) { UiIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
        }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UiText("设备权限", style = MaterialTheme.typography.titleMedium)
                        UiText("应用 UID：${device.appUid}")
                        UiText(if (device.shellAvailable) "普通 Shell 可用" else "普通 Shell 未检测到")
                        UiText(if (device.rootDetected) "已检测到 su" else "未检测到 su")
                        UiText(device.rootStatus)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { operation { system.refresh() } }, enabled = !busy) { UiText("重新检测") }
                            Button(onClick = { operation { system.requestRoot() } }, enabled = !busy) { UiText("请求 Root") }
                        }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"))) }) { UiText("Android 应用权限") }
                    } }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { text -> item { UiText(text, color = MaterialTheme.colorScheme.error) } }
                item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    UiText("工具免询问授权", style = MaterialTheme.typography.titleMedium)
                    UiText("已记住 ${grants.size} 项授权。插件代码、权限、执行模式或角色配置变化后需重新确认。")
                    OutlinedButton(onClick = { operation { approvals.clear() } }, enabled = !busy && grants.isNotEmpty()) { UiText("清除所有免询问授权") }
                } } }
                item { UiText("Shell 执行方式", style = MaterialTheme.typography.titleLarge) }
                item { UiText("普通模式使用应用身份；Root 使用设备最高权限。每个插件单独选择，AI 调用遵循工具授权设置。切换模式会停止该插件正在运行的命令。") }
                val owners = listOf(TERMINAL_OWNER to "手动终端") + installed.filter { "shell" in it.permissions }.map { it.id to it.name }
                owners.forEach { (id, name) -> item(key = id) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UiText(name, style = MaterialTheme.typography.titleMedium)
                        UiText(if ((modes[id] ?: ExecutionMode.APP) == ExecutionMode.ROOT) "Root · 每次执行前核验 UID 0" else "普通应用权限")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = (modes[id] ?: ExecutionMode.APP) == ExecutionMode.APP,
                                onClick = { operation { system.setMode(id, ExecutionMode.APP) } }, enabled = !busy, label = { UiText("普通") })
                            FilterChip(selected = modes[id] == ExecutionMode.ROOT, onClick = { rootOwner = id to name },
                                enabled = !busy, label = { UiText("Root") })
                        }
                    } }
                } }
            }
        }
    }
    rootOwner?.let { (id, name) -> AlertDialog(onDismissRequest = { rootOwner = null }, title = { UiText("为 $name 使用 Root？") },
        text = { UiText("Root 命令可以读取和修改设备系统及其他应用数据。系统会请求 su 授权并验证 UID 0；授权失败时命令不会执行。插件的普通文件接口仍限定在当前工作区。") },
        confirmButton = { TextButton(onClick = { rootOwner = null; operation { system.setMode(id, ExecutionMode.ROOT) } }) { UiText("请求并使用 Root") } },
        dismissButton = { TextButton(onClick = { rootOwner = null }) { UiText("取消") } }) }
}
