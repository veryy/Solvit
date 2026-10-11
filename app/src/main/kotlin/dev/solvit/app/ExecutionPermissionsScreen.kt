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
fun ExecutionPermissionsScreen(system: SystemExecutionService, market: MarketplaceService, onDismiss: () -> Unit) {
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
        Scaffold(topBar = { TopAppBar(title = { Text("权限与执行方式") }, navigationIcon = {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
        }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("设备权限", style = MaterialTheme.typography.titleMedium)
                        Text("应用 UID：${device.appUid}")
                        Text(if (device.shellAvailable) "普通 Shell 可用" else "普通 Shell 未检测到")
                        Text(if (device.rootDetected) "已检测到 su" else "未检测到 su")
                        Text(device.rootStatus)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { operation { system.refresh() } }, enabled = !busy) { Text("重新检测") }
                            Button(onClick = { operation { system.requestRoot() } }, enabled = !busy) { Text("请求 Root") }
                        }
                        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"))) }) { Text("Android 应用权限") }
                    } }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { text -> item { Text(text, color = MaterialTheme.colorScheme.error) } }
                item { Text("Shell 执行方式", style = MaterialTheme.typography.titleLarge) }
                item { Text("普通模式使用应用身份；Root 使用设备最高权限。每个插件单独选择，AI 调用仍需逐次确认。切换模式会停止该插件正在运行的命令。") }
                val owners = listOf(TERMINAL_OWNER to "手动终端") + installed.filter { "shell" in it.permissions }.map { it.id to it.name }
                owners.forEach { (id, name) -> item(key = id) {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(if ((modes[id] ?: ExecutionMode.APP) == ExecutionMode.ROOT) "Root · 每次执行前核验 UID 0" else "普通应用权限")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = (modes[id] ?: ExecutionMode.APP) == ExecutionMode.APP,
                                onClick = { operation { system.setMode(id, ExecutionMode.APP) } }, enabled = !busy, label = { Text("普通") })
                            FilterChip(selected = modes[id] == ExecutionMode.ROOT, onClick = { rootOwner = id to name },
                                enabled = !busy, label = { Text("Root") })
                        }
                    } }
                } }
            }
        }
    }
    rootOwner?.let { (id, name) -> AlertDialog(onDismissRequest = { rootOwner = null }, title = { Text("为 $name 使用 Root？") },
        text = { Text("Root 命令可以读取和修改设备系统及其他应用数据。系统会请求 su 授权并验证 UID 0；授权失败时命令不会执行。插件的普通文件接口仍限定在当前工作区。") },
        confirmButton = { TextButton(onClick = { rootOwner = null; operation { system.setMode(id, ExecutionMode.ROOT) } }) { Text("请求并使用 Root") } },
        dismissButton = { TextButton(onClick = { rootOwner = null }) { Text("取消") } }) }
}
