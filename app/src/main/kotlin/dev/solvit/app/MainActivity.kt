package dev.solvit.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.solvit.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SolvitApplication
        setContent {
            SolvitTheme {
                Surface(Modifier.fillMaxSize()) {
                    val result by app.ready.collectAsStateWithLifecycle()
                    when {
                        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        result!!.isFailure -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("Solvit 初始化失败，请重启应用。聊天记录不会被自动清除。")
                        }
                        else -> {
                            val service = result!!.getOrThrow()
                            val factory = remember(service) { object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(service, app.settings, app.roles, app.modelCatalog) as T
                            } }
                            val vm: ChatViewModel = viewModel(factory = factory)
                            val updateFactory = remember(app.updates) { object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(app.updates, app.updateDownloader) as T
                            } }
                            val updateVm: UpdateViewModel = viewModel(factory = updateFactory)
                            val marketFactory = remember(app.marketplace) { object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = MarketplaceViewModel(app.marketplace) as T
                            } }
                            val marketVm: MarketplaceViewModel = viewModel(factory = marketFactory)
                            val agentFactory = remember(app.roles, app.skills) { object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentLibraryViewModel(app.roles, app.skills, app.marketplace) as T
                            } }
                            val agentVm: AgentLibraryViewModel = viewModel(factory = agentFactory)
                            val workspaceFactory = remember(app.workspaces) { object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = WorkspaceViewModel(app.workspaces, app.pluginDrafts, app.terminals) as T
                            } }
                            val workspaceVm: WorkspaceViewModel = viewModel(factory = workspaceFactory)
                            ChatScreen(vm, updateVm, marketVm, agentVm, workspaceVm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SolvitTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance = (context.applicationContext as SolvitApplication).appearance
    val mode by appearance.theme.collectAsStateWithLifecycle()
    val dynamic by appearance.dynamicColors.collectAsStateWithLifecycle()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFBACDA8), secondary = Color(0xFFBBCAB4))
        else -> lightColorScheme(primary = Color(0xFF526743), secondary = Color(0xFF576350), tertiary = Color(0xFF38666B))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel, updates: UpdateViewModel, marketplace: MarketplaceViewModel, agents: AgentLibraryViewModel, workspaces: WorkspaceViewModel) {
    val updateState by updates.state.collectAsStateWithLifecycle()
    val downloadState by updates.downloadState.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val selected by vm.sessionId.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val errorDetails by vm.errorDetails.collectAsStateWithLifecycle()
    var showErrorDetails by remember { mutableStateOf(false) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val toolApproval by vm.toolApproval.collectAsStateWithLifecycle()
    val toolActivity by vm.toolActivity.collectAsStateWithLifecycle()
    val missingRole by agents.missingRole.collectAsStateWithLifecycle()
    val agentBusy by agents.busy.collectAsStateWithLifecycle()
    var input by rememberSaveable(selected) { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var pluginsOpen by rememberSaveable { mutableStateOf(false) }
    var workspaceOpen by rememberSaveable { mutableStateOf(false) }
    val workspaceId by workspaces.workspaceId.collectAsStateWithLifecycle()
    val workspaceState by workspaces.state.collectAsStateWithLifecycle()
    var agentsOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val inputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun hideInput() { keyboard?.hide(); focus.clearFocus() }
    LaunchedEffect(ready) {
        if (ready && !settingsOpen && !modelsOpen && !pluginsOpen && !agentsOpen && !workspaceOpen) {
            withFrameNanos { }
            inputFocus.requestFocus()
            keyboard?.show()
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(selected) { agents.setSession(selected); workspaces.selectChat(selected) }
    LaunchedEffect(messages.size, draft) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.width(LocalConfiguration.current.screenWidthDp.dp * 0.75f).testTag("chat_drawer")) {
            Text("Solvit", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(24.dp))
            FilledTonalButton(onClick = { vm.newChat(); scope.launch { drawer.close() } }, enabled = ready && !busy,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("新对话")
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { session ->
                    NavigationDrawerItem(
                        label = { Text(session.title, maxLines = 1) },
                        selected = selected == session.id,
                        onClick = { if (!busy) { vm.select(session.id); scope.launch { drawer.close() } } },
                        badge = { IconButton(onClick = { deleteTarget = session }, enabled = !busy) {
                            Icon(Icons.Default.DeleteOutline, "删除 ${session.title}")
                        } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            HorizontalDivider()
            TextButton(onClick = { scope.launch { drawer.close(); settingsOpen = true } },
                modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                Icon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); Text("设置")
            }
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column {
                        Text("Solvit", fontWeight = FontWeight.SemiBold)
                        Text(config.model, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } },
                    navigationIcon = { IconButton(onClick = { hideInput(); scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "对话历史") } },
                    actions = {
                        IconButton(onClick = { hideInput(); pluginsOpen = true }) { Icon(Icons.Default.Extension, "插件市场") }
                        IconButton(onClick = vm::newChat, enabled = ready && !busy) { Icon(Icons.Default.Add, "新对话") }
                    },
                )
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Surface(modifier = Modifier.fillMaxWidth().testTag("chat_composer"), shape = RoundedCornerShape(28.dp), tonalElevation = 4.dp, shadowElevation = 4.dp) {
                    Column(Modifier.padding(8.dp)) {
                        if (error != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(error!!, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = vm::dismissError) { Icon(Icons.Default.Close, "关闭提示") }
                            }
                            if (errorDetails != null) TextButton(onClick = { showErrorDetails = true }) { Text("查看详细信息") }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            TextField(value = input, onValueChange = { input = it },
                                placeholder = { Text("问点什么，或描述你的想法…") },
                                modifier = Modifier.weight(1f).focusRequester(inputFocus), maxLines = 5, enabled = ready,
                                shape = RoundedCornerShape(24.dp),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent))
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = { if (busy) vm.stop() else if (vm.send(input)) input = "" },
                                enabled = busy || (ready && input.isNotBlank() && !missingRole && !agentBusy), modifier = Modifier.size(52.dp),
                            ) { Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (busy) "停止生成" else "发送") }
                        }
                    }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AppUpdateCard(updateState, downloadState, updates::dismiss, { updates.check() }, updates::download, updates::cancelDownload, updates::install)
                TextButton(onClick = { hideInput(); workspaceOpen = true }, enabled = ready && !busy && workspaceId != null,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    Text("工作区：" + (workspaceState.workspaces.firstOrNull { it.id == workspaceId }?.name ?: "打开中…") + " · Shell / 文件")
                }
            if (messages.isEmpty() && !busy) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val expanded = maxHeight >= 240.dp
                    val showTitle = maxHeight >= 100.dp
                    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 4.dp)) {
                        Icon(painterResource(R.drawable.ic_solvit_monochrome), null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (expanded) 80.dp else 48.dp))
                        if (showTitle) Text("从一个想法开始", style = if (expanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
                        if (expanded) {
                            Text("一起整理思路、解答问题", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { hideInput(); modelsOpen = true }) { Text("配置你的模型") }
                        }
                    }
                }
            } else {
                LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(messages, key = { it.id }) { MessageCard(it) }
                    items(toolActivity, key = { "tool-${it.call.id}" }) { ToolActivityCard(it) }
                    if (busy) item(key = "live") {
                        MessageCard(ChatMessage("live", selected.orEmpty(), Role.ASSISTANT,
                            if (draft.isEmpty()) "正在思考…" else draft))
                    }
                }
            }
            }
        }
    }
    if (showErrorDetails) errorDetails?.let { ModelErrorDialog(it) { showErrorDetails = false } }
    LaunchedEffect(errorDetails) { showErrorDetails = false }
    toolApproval?.let { pending ->
        AlertDialog(onDismissRequest = { vm.approveTool(false) }, title = { Text("允许调用工具？") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text(pending.descriptor.description) }
                item { Text(pending.descriptor.name, style = MaterialTheme.typography.labelSmall) }
                pending.descriptor.executionMode?.let { mode -> item {
                    Text(if (mode == ExecutionMode.ROOT) "执行身份：Root（设备最高权限）" else "执行身份：普通应用")
                } }
                item { SelectionContainer { Text(pending.call.argumentsJson) } }
            } },
            confirmButton = { TextButton(onClick = { vm.approveTool(true) }) { Text("允许这一次") } },
            dismissButton = { TextButton(onClick = { vm.approveTool(false) }) { Text("拒绝") } })
    }
    val app = LocalContext.current.applicationContext as SolvitApplication
    if (workspaceOpen) WorkspaceDialog(workspaces, app.systemExecution) { workspaceOpen = false }
    if (settingsOpen) SettingsScreen(updates, { settingsOpen = false }, { modelsOpen = true },
        { agentsOpen = true }, { pluginsOpen = true }, { backupOpen = true }, app.appearance, { permissionsOpen = true })
    if (permissionsOpen) ExecutionPermissionsScreen(app.systemExecution, app.marketplace) { permissionsOpen = false }
    if (modelsOpen) ModelSettings(config, saving, error, onDismiss = { if (!saving) modelsOpen = false },
        onSave = { vm.saveConfig(it) { modelsOpen = false } }, onFetchModels = vm::discoverModels)
    if (backupOpen) ConfigurationBackupDialog(vm, app.roles, app.appearance, onDismiss = { backupOpen = false })
    if (pluginsOpen) PluginMarketplaceDialog(marketplace, onDismiss = { pluginsOpen = false })
    if (agentsOpen) AgentLibraryDialog(agents, onDismiss = { agentsOpen = false })
    deleteTarget?.let { session ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除对话？") },
            text = { Text("“${session.title}” 的记录将从本机删除。") },
            confirmButton = { TextButton(onClick = { vm.delete(session.id); deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    val user = message.role == Role.USER
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Text(if (user) "你" else if (message.role == Role.TOOL) "工具结果" else "Solvit", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        Surface(shape = RoundedCornerShape(20.dp),
            color = if (user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp)) {
                SelectionContainer { Text(message.content.ifEmpty { if (message.toolCalls.isNotEmpty()) "调用工具：" + message.toolCalls.joinToString { it.name } else if (message.status == MessageStatus.INTERRUPTED) "已停止生成" else "未收到回复" }, style = MaterialTheme.typography.bodyLarge) }
                if (message.status != MessageStatus.COMPLETE) Text(
                    if (message.status == MessageStatus.INTERRUPTED) "已停止 · 此回复不参与后续上下文" else "回复失败 · 此回复不参与后续上下文",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSettings(config: ModelConfig, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (ModelConfig) -> Unit,
    onFetchModels: suspend (ModelConfig) -> List<AvailableModel>) {
    // API keys deliberately do not use rememberSaveable / saved-instance state.
    var base by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var key by remember { mutableStateOf(config.apiKey) }
    var prompt by remember { mutableStateOf(config.systemPrompt) }
    var visible by remember { mutableStateOf(false) }
    var listUrl by remember { mutableStateOf(config.modelListUrl) }
    var listAuth by remember { mutableStateOf(config.modelListUsesApiKey) }
    var models by remember { mutableStateOf<List<AvailableModel>>(emptyList()) }
    var fetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var fetchDetails by remember { mutableStateOf<ModelErrorDetails?>(null) }
    var showFetchDetails by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    var fetchRevision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(base, key, listUrl, listAuth) {
        fetchRevision++; fetchJob?.cancel(); fetching = false; models = emptyList(); fetchError = null; fetchDetails = null; showFetchDetails = false; picker = false
    }
    val candidate = ModelConfig(base.trim(), model.trim(), key.trim(), prompt, listUrl.trim(), listAuth)
    val invalid = candidate.validate()
    FullScreenPage(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { TopAppBar(title = { Text("模型设置") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Default.Close, "关闭设置") }
            }) }, bottomBar = {
                Button(onClick = { onSave(candidate) }, enabled = !saving && invalid == null,
                    modifier = Modifier.navigationBarsPadding().imePadding().padding(16.dp).fillMaxWidth()) {
                    Text(if (saving) "保存中…" else "保存配置")
                }
            }) { padding ->
                LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Text("连接你选择的模型", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("支持 OpenAI、DeepSeek 等兼容 Chat Completions API 的服务。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedTextField(base, { base = it }, label = { Text("API 基础地址") },
                        supportingText = { Text("例如 https://api.deepseek.com/v1；无需填写 /chat/completions") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    item { OutlinedTextField(key, { key = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, enabled = !saving, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥") } },
                        supportingText = { Text("密钥加密保存在本机；无需认证的本地模型可留空。") }) }
                    item { OutlinedTextField(listUrl, { listUrl = it }, label = { Text("模型列表 / 订阅地址（可选）") },
                        supportingText = { Text("留空时从 API 基础地址下的 /models 获取；也支持独立 JSON 模型列表地址。") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    if (listUrl.isNotBlank()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = listAuth, onCheckedChange = { listAuth = it }, enabled = !saving)
                            Text("不同站点的列表也使用此 API Key（同站点默认使用）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !saving && !fetching && candidate.validateConnection() == null, onClick = {
                            fetching = true; fetchError = null; fetchDetails = null
                            val revision = ++fetchRevision
                            fetchJob = scope.launch {
                                try { val result = onFetchModels(candidate); if (revision == fetchRevision) { models = result; search = ""; picker = true } }
                                catch (e: CancellationException) { throw e }
                                catch (e: ModelException) { if (revision == fetchRevision) { fetchError = e.message; fetchDetails = e.details } }
                                catch (e: Exception) { if (revision == fetchRevision) fetchError = "获取模型失败，请检查地址、密钥和网络" }
                                finally { if (revision == fetchRevision) fetching = false }
                            }
                        }) { Text(if (fetching) "正在获取…" else "获取模型") }
                        if (fetching) TextButton(onClick = { fetchRevision++; fetchJob?.cancel(); fetching = false }) { Text("取消") }
                        else if (models.isNotEmpty()) TextButton(onClick = { picker = true }) { Text("选择模型 (${models.size})") }
                    } }
                    if (fetching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (fetchError != null) item {
                        Text(fetchError!!, color = MaterialTheme.colorScheme.error)
                        if (fetchDetails != null) TextButton(onClick = { showFetchDetails = true }) { Text("查看详细信息") }
                    }
                    item { OutlinedTextField(model, { model = it }, label = { Text("模型名称") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    item { OutlinedTextField(prompt, { prompt = it }, label = { Text("系统提示词") },
                        minLines = 3, maxLines = 8, enabled = !saving, modifier = Modifier.fillMaxWidth()) }
                    if (invalid != null) item { Text(invalid, color = MaterialTheme.colorScheme.error) }
                    if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (showFetchDetails) fetchDetails?.let { ModelErrorDialog(it) { showFetchDetails = false } }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("选择模型") }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { Text("搜索模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(models.filter { it.id.contains(search, true) || it.name.contains(search, true) }, key = { it.id }) { item ->
                    TextButton(onClick = { model = item.id; picker = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text(item.name); if (item.name != item.id) Text(item.id, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { Text("关闭") } })
}

@Composable
private fun ToolActivityCard(activity: ModelChunk.ToolActivity) {
    var expanded by remember(activity.call.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(activity.call.name, style = MaterialTheme.typography.titleSmall)
        Text(when (activity.stage) {
            ToolStage.CONFIRMING -> "等待确认"
            ToolStage.RUNNING -> "执行中…"
            ToolStage.SUCCEEDED -> "已完成"
            ToolStage.FAILED -> "执行失败"
            ToolStage.DENIED -> "已拒绝"
        })
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "查看参数与结果") }
        if (expanded) SelectionContainer { Text(activity.call.argumentsJson + "\n" + activity.result.orEmpty()) }
    } }
}
