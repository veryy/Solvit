package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.solvit.core.ExtensionPage
import dev.solvit.core.InstalledExtension

/** Host management only: the plugin remains responsible for its business pages. */
@Composable
internal fun InstalledPluginDetailsDialog(extension: InstalledExtension, busy: Boolean,
    onDismiss: () -> Unit, onToggle: () -> Unit, onConfigure: () -> Unit,
    onOpenPage: (ExtensionPage) -> Unit, onUninstall: () -> Unit) {
    var permissionsExpanded by rememberSaveable(extension.id) { mutableStateOf(false) }
    val permissionState = uiString(if (permissionsExpanded) "收起" else "展开")
    AlertDialog(onDismissRequest = onDismiss, title = { Text(extension.name) },
        text = {
            LazyColumn(Modifier.testTag("plugin_details_content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.small,
                            color = if (extension.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            UiText(if (extension.enabled) "已启用" else "已停用", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        UiText("版本 ${extension.version}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                extension.failure?.let { failure -> item {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer) {
                        Text(failure, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall)
                    }
                } }
                if (extension.pages.isNotEmpty()) {
                    item { UiText("插件页面", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    if (!extension.enabled) item {
                        UiText("启用插件后可打开它的页面。", style = MaterialTheme.typography.bodySmall)
                    }
                    items(extension.pages, key = { "page_" + it.id }) { page ->
                        FilledTonalButton(enabled = !busy && extension.enabled,
                            modifier = Modifier.fillMaxWidth().testTag("open_plugin_page_${extension.id}_${page.id}"),
                            onClick = { onOpenPage(page) }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(page.title, Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
                if (extension.settings.any { !it.pageManaged }) item {
                    OutlinedButton(enabled = !busy && !extension.enabled, onClick = onConfigure, modifier = Modifier.fillMaxWidth()) {
                        UiText(if (extension.enabled) "停用后可配置" else "配置插件")
                    }
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(onClick = { permissionsExpanded = !permissionsExpanded },
                        modifier = Modifier.fillMaxWidth().testTag("plugin_permissions_toggle")
                            .semantics { stateDescription = permissionState }, contentPadding = PaddingValues(vertical = 8.dp)) {
                        UiText("权限与能力", Modifier.weight(1f))
                        Icon(if (permissionsExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null)
                    }
                    if (permissionsExpanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("plugin_permissions_details")) {
                        if (extension.permissions.isEmpty()) UiText("无需额外权限", style = MaterialTheme.typography.bodySmall)
                        else extension.permissions.forEach { permission ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(permissionLabel(permission), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (extension.contextHooks.isNotEmpty()) UiText("插件可为聊天提供上下文；停用后停止提供。", style = MaterialTheme.typography.bodySmall)
                        if (extension.enabled) UiText("AI 可发现 ${extension.tools.count { it.chatVisible }} 个工具；角色需允许此插件，执行时会请求确认。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TextButton(enabled = !busy, onClick = onUninstall, modifier = Modifier.testTag("plugin_uninstall"),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { UiText("卸载") }
                }
            }
        }, confirmButton = {
            FilledTonalButton(enabled = !busy, onClick = onToggle, modifier = Modifier.testTag("plugin_toggle")) {
                UiText(if (extension.enabled) "停用" else "启用")
            }
        }, dismissButton = { TextButton(onClick = onDismiss) { UiText("关闭") } })
}
