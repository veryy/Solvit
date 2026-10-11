package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginMarketplaceDialog(vm: MarketplaceViewModel, onDismiss: () -> Unit) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val output by vm.output.collectAsStateWithLifecycle()
    val configuring by vm.configuring.collectAsStateWithLifecycle()
    val configurationValues by vm.configurationValues.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var enableTarget by remember { mutableStateOf<InstalledExtension?>(null) }
    var deleteTarget by remember { mutableStateOf<InstalledExtension?>(null) }
    var toolTarget by remember { mutableStateOf<ToolDescriptor?>(null) }
    var arguments by remember { mutableStateOf("{}") }

    FullScreenPage(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("插件") }, navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭插件页面") }
                }, actions = {
                    IconButton(onClick = vm::refresh, enabled = !loading && !busy) { Icon(Icons.Default.Refresh, "刷新市场") }
                })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("市场") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("已安装 (${installed.size})") })
                    }
                    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (tab == 0) {
                            if (catalog.isEmpty() && !loading) item {
                                Text("暂无插件，点击右上角刷新市场。", modifier = Modifier.padding(16.dp))
                            }
                            items(catalog, key = { it.id }) { entry ->
                                val local = installed.firstOrNull { it.id == entry.id }
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                        Text(entry.description)
                                        Text("版本 ${entry.version}", style = MaterialTheme.typography.labelMedium)
                                        Text(permissionDescription(entry.permissions), style = MaterialTheme.typography.bodySmall)
                                        if (entry.dependencies.isNotEmpty()) Text("依赖：${entry.dependencies.joinToString()}", style = MaterialTheme.typography.bodySmall)
                                        Button(onClick = { vm.install(entry); tab = 1 }, enabled = !busy && local?.version != entry.version) {
                                            Text(if (local?.version == entry.version) "已安装" else if (local != null) "更新插件" else "安装")
                                        }
                                    }
                                }
                            }
                        } else {
                            if (installed.isEmpty()) item { Text("安装后可以在这里启用插件、管理权限和试运行工具。") }
                            items(installed, key = { it.id }) { extension ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(extension.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${extension.version} · ${if (extension.enabled) "已启用" else "已停用"}", style = MaterialTheme.typography.labelMedium)
                                        Text(permissionDescription(extension.permissions), style = MaterialTheme.typography.bodySmall)
                                        if (extension.enabled) Text("AI 可发现 ${extension.tools.count { it.chatVisible }} 个工具；角色需允许此插件，执行时会请求确认。", style = MaterialTheme.typography.bodySmall)
                                        extension.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = { if (extension.enabled) vm.disable(extension.id) else enableTarget = extension }, enabled = !busy) {
                                                Text(if (extension.enabled) "停用" else "启用")
                                            }
                                            TextButton(onClick = { deleteTarget = extension }, enabled = !busy) { Text("卸载") }
                                        }
                                        if (extension.settings.isNotEmpty()) TextButton(
                                            onClick = { vm.openConfiguration(extension) }, enabled = !busy && !extension.enabled,
                                        ) { Text(if (extension.enabled) "停用后可配置" else "配置插件") }
                                        extension.tools.forEach { tool ->
                                            OutlinedButton(onClick = { toolTarget = tool; arguments = "{}" }, enabled = !busy) { Text("试运行：${tool.description}") }
                                        }
                                    }
                                }
                            }
                        }
                        output?.let { result -> item {
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                Text("工具结果", style = MaterialTheme.typography.titleMedium)
                                SelectionContainer { Text(result) }
                            } }
                        } }
                    }
                }
            }
        }
    }
    configuring?.let { extension ->
        var values by remember(extension.id) { mutableStateOf(configurationValues) }
        val valid = extension.settings.filter { it.required }.all { !values[it.key].isNullOrBlank() }
        Dialog(onDismissRequest = { if (!busy) vm.closeConfiguration() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            Surface(shape = MaterialTheme.shapes.large) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("配置 ${extension.name}", style = MaterialTheme.typography.titleLarge)
                Text("配置只提供给此插件，密钥加密保存在本机。")
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(extension.settings, key = { it.key }) { field ->
                        OutlinedTextField(value = values[field.key].orEmpty(), onValueChange = { values = values + (field.key to it) },
                            label = { Text(field.label + if (field.required) " *" else "") }, singleLine = true, enabled = !busy,
                            visualTransformation = if (field.kind == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::closeConfiguration, enabled = !busy) { Text("取消") }
                    Button(onClick = { vm.saveConfiguration(extension.id, values) }, enabled = !busy && valid) { Text("保存") }
                }
            } }
        }
    }
    enableTarget?.let { extension ->
        AlertDialog(onDismissRequest = { enableTarget = null }, title = { Text("启用 ${extension.name}？") },
            text = { Text(permissionDescription(extension.permissions) + "\n\n启用后插件可在这些权限范围内运行，停用将撤销已注册工具。") },
            confirmButton = { TextButton(onClick = { vm.enable(extension); enableTarget = null }) { Text("允许并启用") } },
            dismissButton = { TextButton(onClick = { enableTarget = null }) { Text("取消") } })
    }
    deleteTarget?.let { extension ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("卸载 ${extension.name}？") },
            text = { Text("移除插件和权限配置。插件保存的数据会保留，重新安装后可继续使用。") },
            confirmButton = { TextButton(onClick = { vm.uninstall(extension.id); deleteTarget = null }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
    toolTarget?.let { tool ->
        AlertDialog(onDismissRequest = { toolTarget = null }, title = { Text("确认运行工具") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tool.description)
                OutlinedTextField(arguments, { arguments = it }, label = { Text("参数（JSON 对象）") }, minLines = 3)
            } },
            confirmButton = { TextButton(onClick = { vm.run(tool, arguments); toolTarget = null }) { Text("运行") } },
            dismissButton = { TextButton(onClick = { toolTarget = null }) { Text("取消") } })
    }
}

private fun permissionDescription(permissions: Set<String>): String = if (permissions.isEmpty()) "无需额外权限" else
    "所需权限：" + permissions.joinToString("、") { when (it) {
        "storage" -> "插件自己的持久化存储"
        "network" -> "访问 HTTPS 网络服务"
        else -> it
    } }
