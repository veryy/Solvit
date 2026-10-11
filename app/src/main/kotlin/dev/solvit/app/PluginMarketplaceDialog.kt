package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    val configuring by vm.configuring.collectAsStateWithLifecycle()
    val configurationValues by vm.configurationValues.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var enableTarget by remember { mutableStateOf<InstalledExtension?>(null) }
    var deleteTarget by remember { mutableStateOf<InstalledExtension?>(null) }
    var marketTarget by remember { mutableStateOf<MarketEntry?>(null) }
    var installedTarget by remember { mutableStateOf<String?>(null) }

    FullScreenPage(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { UiText("插件") }, navigationIcon = {
                    IconButton(onClick = onDismiss) { UiIcon(Icons.Default.Close, "关闭插件页面") }
                }, actions = {
                    IconButton(onClick = vm::refresh, enabled = !loading && !busy) { UiIcon(Icons.Default.Refresh, "刷新市场") }
                })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { UiText("市场") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { UiText("已安装 (${installed.size})") })
                    }
                    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { UiText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.weight(1f).testTag("plugins_list"),
                        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tab == 0) {
                            if (catalog.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) { UiText("暂无插件，点击右上角刷新市场。") }
                            items(catalog, key = { it.id }) { entry ->
                                val local = installed.firstOrNull { it.id == entry.id }
                                Card(onClick = { marketTarget = entry }, modifier = Modifier.fillMaxWidth().testTag("market_plugin_${entry.id}")) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        UiText(if (local?.version == entry.version) "已安装" else "版本 ${entry.version}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        } else {
                            if (installed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { UiText("安装后可以在这里启用插件、管理权限和配置。") }
                            items(installed, key = { it.id }) { extension ->
                                Card(onClick = { installedTarget = extension.id }, modifier = Modifier.fillMaxWidth().testTag("installed_plugin_${extension.id}")) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(extension.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        UiText(if (extension.enabled) "已启用" else "已停用", style = MaterialTheme.typography.labelSmall)
                                        UiText("版本 ${extension.version}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
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
                UiText("配置 ${extension.name}", style = MaterialTheme.typography.titleLarge)
                UiText("配置只提供给此插件，密钥加密保存在本机。")
                LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(extension.settings, key = { it.key }) { field ->
                        if (field.kind == "boolean") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                UiText(field.label, modifier = Modifier.weight(1f))
                                Switch(checked = (values[field.key] ?: field.defaultValue ?: "false") == "true",
                                    onCheckedChange = { values = values + (field.key to it.toString()) }, enabled = !busy,
                                    modifier = Modifier.testTag("plugin_setting_${field.key}"))
                            }
                        } else OutlinedTextField(value = values[field.key].orEmpty(), onValueChange = { values = values + (field.key to it) },
                            label = { Text(field.label + if (field.required) " *" else "") }, singleLine = true, enabled = !busy,
                            visualTransformation = if (field.kind == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                            modifier = Modifier.fillMaxWidth())
                    }
                }
                error?.let { UiText(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::closeConfiguration, enabled = !busy) { UiText("取消") }
                    Button(onClick = { vm.saveConfiguration(extension.id, values) }, enabled = !busy && valid) { UiText("保存") }
                }
            } }
        }
    }
    enableTarget?.let { extension ->
        AlertDialog(onDismissRequest = { enableTarget = null }, title = { UiText("启用 ${extension.name}？") },
            text = { Column { Text(permissionDescription(extension.permissions));
                if (extension.contextHooks.isNotEmpty()) UiText("插件会为聊天提供上下文，已保存的资料可能发送给当前模型服务。")
                UiText("启用后插件可在这些权限范围内运行，停用将撤销已注册工具。") } },
            confirmButton = { TextButton(onClick = { vm.enable(extension); enableTarget = null }) { UiText("允许并启用") } },
            dismissButton = { TextButton(onClick = { enableTarget = null }) { UiText("取消") } })
    }
    deleteTarget?.let { extension ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { UiText("卸载 ${extension.name}？") },
            text = { UiText("移除插件和权限配置。插件保存的数据会保留，重新安装后可继续使用。") },
            confirmButton = { TextButton(onClick = { vm.uninstall(extension.id); deleteTarget = null }) { UiText("卸载") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { UiText("取消") } })
    }
    marketTarget?.let { entry ->
        val local = installed.firstOrNull { it.id == entry.id }
        AlertDialog(onDismissRequest = { marketTarget = null }, title = { Text(entry.name) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.description); UiText("版本 ${entry.version}"); UiText(permissionDescription(entry.permissions))
                if (entry.dependencies.isNotEmpty()) UiText("依赖：${entry.dependencies.joinToString()}")
            } }, confirmButton = { TextButton(enabled = !busy && local?.version != entry.version, onClick = { vm.install(entry); marketTarget = null; tab = 1 }) {
                UiText(if (local?.version == entry.version) "已安装" else if (local != null) "更新插件" else "安装")
            } }, dismissButton = { TextButton(onClick = { marketTarget = null }) { UiText("关闭") } })
    }
    installed.firstOrNull { it.id == installedTarget }?.let { extension ->
        AlertDialog(onDismissRequest = { installedTarget = null }, title = { Text(extension.name) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { UiText("版本 ${extension.version}"); UiText(permissionDescription(extension.permissions)) }
                if (extension.contextHooks.isNotEmpty()) item { UiText("插件可为聊天提供上下文；停用后停止提供。") }
                if (extension.enabled) item { UiText("AI 可发现 ${extension.tools.count { it.chatVisible }} 个工具；角色需允许此插件，执行时会请求确认。") }
                extension.failure?.let { item { UiText(it, color = MaterialTheme.colorScheme.error) } }
                if (extension.settings.isNotEmpty()) item { TextButton(enabled = !busy && !extension.enabled, onClick = { installedTarget = null; vm.openConfiguration(extension) }) { UiText(if (extension.enabled) "停用后可配置" else "配置插件") } }
                item { TextButton(enabled = !busy, onClick = { installedTarget = null; deleteTarget = extension }) { UiText("卸载") } }
            } }, confirmButton = { TextButton(enabled = !busy, onClick = {
                if (extension.enabled) vm.disable(extension.id) else { installedTarget = null; enableTarget = extension }
            }) { UiText(if (extension.enabled) "停用" else "启用") } },
            dismissButton = { TextButton(onClick = { installedTarget = null }) { UiText("关闭") } })
    }

}

@Composable
private fun permissionDescription(permissions: Set<String>): String = if (permissions.isEmpty()) uiString("无需额外权限") else
    uiString("所需权限：") + permissions.map { uiString(when (it) {
        "storage" -> "插件自己的持久化存储"
        "network" -> "访问 HTTPS 网络服务"
        "workspace" -> "读取和修改当前对话工作区的文件与会话"
        "shell" -> "以应用身份运行 Shell，可访问本应用文件和网络"
        "plugins" -> "生成和校验插件草稿；安装与启用需单独操作"
        else -> it
    }) }.joinToString(" · ")
