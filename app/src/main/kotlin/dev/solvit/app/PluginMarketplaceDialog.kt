package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
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
fun PluginMarketplaceDialog(vm: MarketplaceViewModel, workspaceAccess: ((Boolean) -> dev.solvit.core.PageWorkspaceAccess)? = null, terminalAccess: ((String) -> PageTerminalAccess)? = null, gitAccess: (() -> PageGitAccess)? = null, onDismiss: () -> Unit) {
    val installed by vm.installed.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val configuring by vm.configuring.collectAsStateWithLifecycle()
    val configurationValues by vm.configurationValues.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var marketQuery by rememberSaveable { mutableStateOf("") }
    var installedQuery by rememberSaveable { mutableStateOf("") }
    var installedFilter by rememberSaveable { mutableIntStateOf(0) }
    val marketGrid = rememberLazyGridState()
    val installedGrid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    fun changeQuery(value: String) {
        if (tab == 0) marketQuery = value else installedQuery = value
        val grid = if (tab == 0) marketGrid else installedGrid
        scope.launch { grid.scrollToItem(0) }
    }
    val query = if (tab == 0) marketQuery else installedQuery
    val search = query.trim()
    val visibleCatalog = remember(catalog, search) { catalog.filter { entry ->
        listOf(entry.id, entry.name, entry.description).any { it.contains(search, ignoreCase = true) }
    } }
    val visibleInstalled = remember(installed, search, installedFilter) { installed.filter { extension ->
        (installedFilter == 0 || (installedFilter == 1) == extension.enabled) &&
            (listOf(extension.id, extension.name).any { it.contains(search, ignoreCase = true) } ||
                extension.tools.any { it.name.contains(search, ignoreCase = true) || it.description.contains(search, ignoreCase = true) } ||
                extension.pages.any { it.title.contains(search, ignoreCase = true) })
    } }
    var enableTarget by remember { mutableStateOf<InstalledExtension?>(null) }
    var deleteTarget by remember { mutableStateOf<InstalledExtension?>(null) }
    var marketTarget by remember { mutableStateOf<MarketEntry?>(null) }
    var installedTarget by remember { mutableStateOf<String?>(null) }
    var openedPage by remember { mutableStateOf<Pair<String, ExtensionPage>?>(null) }

    FullScreenPage(onDismissRequest = onDismiss) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        fun finishSearch() { focus.clearFocus(); keyboard?.hide() }
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
                        Tab(selected = tab == 0, onClick = { finishSearch(); tab = 0 }, modifier = Modifier.testTag("plugins_market_tab"), text = { UiText("市场") })
                        Tab(selected = tab == 1, onClick = { finishSearch(); tab = 1 }, modifier = Modifier.testTag("plugins_installed_tab"), text = { UiText("已安装 (${installed.size})") })
                    }
                    OutlinedTextField(value = query, onValueChange = ::changeQuery,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("plugins_search"),
                        placeholder = { UiText("搜索名称、ID 或功能", maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { changeQuery("") }) { UiIcon(Icons.Default.Close, "清除搜索") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { finishSearch() }))
                    if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { UiText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), state = if (tab == 0) marketGrid else installedGrid, modifier = Modifier.weight(1f).testTag("plugins_list"),
                        contentPadding = if (imeVisible) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (tab == 1 && !imeVisible) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("全部", "已启用", "已停用").forEachIndexed { index, label ->
                                        FilterChip(selected = installedFilter == index,
                                            onClick = { installedFilter = index; finishSearch(); scope.launch { installedGrid.scrollToItem(0) } },
                                            label = { UiText(label) }, modifier = Modifier.testTag("plugins_filter_$index"))
                                    }
                                }
                                val count = if (tab == 0) visibleCatalog.size else visibleInstalled.size
                                val total = if (tab == 0) catalog.size else installed.size
                                val countLabel = uiString("显示 $count / $total 个插件")
                                val summary = if (tab == 1 && imeVisible)
                                    "${uiString(listOf("全部", "已启用", "已停用")[installedFilter])} · $countLabel" else countLabel
                                Text(summary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("plugins_result_count"))
                            }
                        }
                        val noMatch = if (tab == 0) catalog.isNotEmpty() && visibleCatalog.isEmpty() else installed.isNotEmpty() && visibleInstalled.isEmpty()
                        if (noMatch) item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                UiText("没有匹配的插件", Modifier.testTag("plugins_no_results"))
                                TextButton(onClick = { if (tab == 1) installedFilter = 0; changeQuery(""); finishSearch() },
                                    modifier = Modifier.testTag("plugins_reset_search")) { UiText("清除筛选") }
                            }
                        }
                        if (tab == 0) {
                            if (catalog.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) { UiText("暂无插件，点击右上角刷新市场。") }
                            items(visibleCatalog, key = { it.id }) { entry ->
                                val local = installed.firstOrNull { it.id == entry.id }
                                val comparison = local?.let { compareExtensionVersions(entry.version, it.version) }
                                Card(onClick = { finishSearch(); marketTarget = entry }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("market_plugin_${entry.id}")) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(entry.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        UiText(when {
                                            comparison == 0 -> "已安装"
                                            local != null && comparison != null && comparison < 0 -> "已安装：${local.version}"
                                            else -> "版本 ${entry.version}"
                                        }, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        } else {
                            if (installed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { UiText("安装后可以在这里启用插件、管理权限和配置。") }
                            items(visibleInstalled, key = { it.id }) { extension ->
                                Card(onClick = { finishSearch(); installedTarget = extension.id }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag("installed_plugin_${extension.id}")) {
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
                    items(extension.settings.filterNot { it.pageManaged }, key = { it.key }) { field ->
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
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(permissionDescription(extension.permissions)) }
                if (extension.contextHooks.isNotEmpty()) item { UiText("插件会为聊天提供上下文，已保存的资料可能发送给当前模型服务。") }
                item { UiText("启用后插件可在这些权限范围内运行，停用将撤销已注册工具。") }
            } },
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
        val comparison = local?.let { compareExtensionVersions(entry.version, it.version) }
        AlertDialog(onDismissRequest = { marketTarget = null }, title = { Text(entry.name) },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(entry.description) }
                item { UiText("版本 ${entry.version}", style = MaterialTheme.typography.labelMedium) }
                local?.let { item { UiText("已安装：${it.version}", style = MaterialTheme.typography.labelMedium) } }
                item { UiText(permissionDescription(entry.permissions)) }
                if (entry.dependencies.isNotEmpty()) item { UiText("依赖：${entry.dependencies.joinToString()}") }
            } }, confirmButton = { TextButton(enabled = !busy && (comparison == null || comparison > 0), onClick = {
                vm.install(entry); marketTarget = null
                installedQuery = ""; installedFilter = 0; tab = 1
                scope.launch { installedGrid.scrollToItem(0) }
            }) {
                UiText(when {
                    comparison == 0 -> "已安装"
                    comparison != null && comparison < 0 -> "本地版本较新"
                    local != null -> "更新插件"
                    else -> "安装"
                })
            } }, dismissButton = { TextButton(onClick = { marketTarget = null }) { UiText("关闭") } })
    }
    installed.firstOrNull { it.id == installedTarget }?.let { extension ->
        InstalledPluginDetailsDialog(extension, busy, onDismiss = { installedTarget = null },
            onToggle = {
                if (extension.enabled) vm.disable(extension.id) else { installedTarget = null; enableTarget = extension }
            }, onConfigure = { installedTarget = null; vm.openConfiguration(extension) },
            onOpenPage = { page -> installedTarget = null; openedPage = extension.id to page },
            onUninstall = { installedTarget = null; deleteTarget = extension })
    }

    openedPage?.let { (pluginId, page) ->
        val locale = LocalUiLanguage.current
        key(pluginId, page.id, locale) {
            PluginPageScreen(page.title, {
                val permissions = installed.firstOrNull { it.id == pluginId }?.permissions.orEmpty()
                // Capture the chat before acquiring a page lease; a missing chat must not leak a lease.
                val access = if ("workspace" in permissions) workspaceAccess?.invoke("plugins" in permissions) else null
                var git: PageGitAccess? = null
                var opened: ExtensionPageSession? = null
                try {
                    git = if (permissions.containsAll(setOf("workspace", "git"))) gitAccess?.invoke() else null
                    vm.openPage(pluginId, page.id, locale).also { session ->
                        opened = session
                        access?.let(session::attachWorkspace)
                        git?.let(session::attachGit)
                        if (permissions.containsAll(setOf("workspace", "shell"))) terminalAccess?.invoke(pluginId)?.let(session::attachTerminal)
                    }
                } catch (failure: Throwable) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        try { opened?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        try { access?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        try { git?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    }
                    throw failure
                }
            }) { openedPage = null }
        }
    }
}

@Composable
internal fun permissionDescription(permissions: Set<String>): String = if (permissions.isEmpty()) uiString("无需额外权限") else
    uiString("所需权限：") + permissions.map { permissionLabel(it) }.joinToString(" · ")

@Composable
internal fun permissionLabel(permission: String): String = uiString(when (permission) {
        "storage" -> "插件自己的持久化存储"
        "network" -> "访问 HTTPS 网络服务"
        "workspace" -> "读取和修改当前对话工作区的文件与会话"
        "git" -> "读取当前工作区Git状态与差异，确认后暂存和提交"
        "shell" -> "以应用身份运行 Shell，可访问本应用文件和网络"
        "plugins" -> "生成和校验插件草稿；安装与启用需单独操作"
        "model" -> "后台模型调用，可能产生模型服务费用"
        "agent.read" -> "读取当前聊天请求、模型输出和工具结果，供 Agent 策略决策"
        "agent" -> "接管所有会话的 Agent 步骤；需另外打开插件配置开关，可能产生模型费用"
        else -> permission
    })
