package dev.solvit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(updates: UpdateViewModel, onDismiss: () -> Unit, onModels: () -> Unit,
    onRoles: () -> Unit, onPlugins: () -> Unit, onBackup: () -> Unit, appearance: AppearanceStore, onPermissions: () -> Unit,
    onDiagnostics: () -> Unit = {}, onMemoryTasks: () -> Unit = {}) {
    val languageStore = (LocalContext.current.applicationContext as SolvitApplication).language
    val language by languageStore.language.collectAsStateWithLifecycle()
    val update by updates.state.collectAsStateWithLifecycle()
    val download by updates.downloadState.collectAsStateWithLifecycle()
    val theme by appearance.theme.collectAsStateWithLifecycle()
    val dynamic by appearance.dynamicColors.collectAsStateWithLifecycle()
    var languageOpen by remember { mutableStateOf(false) }
    FullScreenPage(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { UiText("设置", Modifier.testTag("settings_title")) }, navigationIcon = {
                IconButton(onClick = onDismiss) { UiIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回聊天") }
            }) }) { padding ->
                LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("settings_list"), contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SettingsGroup("模型配置") { SettingsRow("模型与连接", "API 地址、模型、密钥、系统提示词", onModels) } }
                    item { SettingsGroup("角色与能力") {
                        SettingsRow("角色管理与 Skills", "提示词、角色绑定、技能导入和管理", onRoles)
                        SettingsRow("插件管理", "安装、启用、权限与插件配置", onPlugins)
                    } }
                    item { SettingsGroup("外观") {
                        UiText("主题", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                                FilterChip(selected = theme == key, onClick = { appearance.setTheme(key) }, label = { UiText(label) })
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { UiText("动态配色"); UiText("Android 12 及以上使用壁纸配色", style = MaterialTheme.typography.bodySmall) }
                            Switch(checked = dynamic, onCheckedChange = appearance::setDynamicColors)
                        }
                    } }
                    item { SettingsGroup("数据与备份") { SettingsRow("配置备份与恢复", "加密导出模型密钥、角色和外观；重装后导入", onBackup) } }
                    item { SettingsGroup("权限与执行") { SettingsRow("权限与执行方式", "应用权限、Root 授权和插件 Shell 模式", onPermissions) } }
                    item { SettingsGroup("应用更新") {
                        SettingsRow("检查更新", "当前版本 ${BuildConfig.VERSION_NAME} · 启动时自动检查", { updates.check() }, update != UpdateUiState.Checking)
                        AppUpdateCard(update, download, updates::dismiss, { updates.check() }, updates::download, updates::cancelDownload, updates::install)
                    } }
                    item { SettingsGroup("诊断") {
                        SettingsRow("最近请求诊断", "角色、上下文来源、工具与回复耗时", onDiagnostics)
                    } }
                    item { SettingsGroup("记忆") { SettingsRow("自动记忆任务", "查看处理状态、失败原因与手动重试", onMemoryTasks) } }
                    item { SettingsGroup("语言") {
                        Row(Modifier.fillMaxWidth().testTag("language_settings").clickable { languageOpen = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { UiText("语言", style = MaterialTheme.typography.titleMedium); Text(uiLanguages.first { it.code == language }.label, style = MaterialTheme.typography.bodySmall) }
                            UiIcon(Icons.Default.ChevronRight, null)
                        }
                    } }
                    item { SettingsGroup("关于 Solvit") {
                        UiText("轻量、可组合的 Android AI 助手", Modifier.padding(16.dp))
                        UiText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                    } }
                }
            }
        }
    }
    if (languageOpen) AlertDialog(onDismissRequest = { languageOpen = false }, title = { UiText("语言") },
        text = { Column {
            uiLanguages.forEach { option ->
                Row(Modifier.fillMaxWidth().testTag("language_${option.code}").clickable { languageStore.set(option.code); languageOpen = false }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = language == option.code, onClick = { languageStore.set(option.code); languageOpen = false })
                    Text(option.label)
                }
            }
        } }, confirmButton = { TextButton(onClick = { languageOpen = false }) { UiText("关闭") } })

}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        UiText(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(8.dp))
        Card(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}
@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { UiText(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp));
            UiText(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        UiIcon(Icons.Default.ChevronRight, null)
    }
}
