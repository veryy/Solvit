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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
                            ChatScreen(vm, updateVm, marketVm, agentVm)
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
private fun ChatScreen(vm: ChatViewModel, updates: UpdateViewModel, marketplace: MarketplaceViewModel, agents: AgentLibraryViewModel) {
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
    val saving by vm.saving.collectAsStateWithLifecycle()
    val toolApproval by vm.toolApproval.collectAsStateWithLifecycle()
    val toolActivity by vm.toolActivity.collectAsStateWithLifecycle()
    val activeRole by agents.activeRole.collectAsStateWithLifecycle()
    val missingRole by agents.missingRole.collectAsStateWithLifecycle()
    val agentBusy by agents.busy.collectAsStateWithLifecycle()
    var input by rememberSaveable(selected) { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var pluginsOpen by rememberSaveable { mutableStateOf(false) }
    var agentsOpen by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(selected) { agents.setSession(selected) }
    LaunchedEffect(messages.size, draft) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet {
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
            Text("轻量 · 可组合 · 原生", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
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
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "对话历史") } },
                    actions = {
                        IconButton(onClick = { pluginsOpen = true }) { Icon(Icons.Default.Extension, "插件市场") }
                        IconButton(onClick = vm::newChat, enabled = ready && !busy) { Icon(Icons.Default.Add, "新对话") }
                        IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Settings, "设置") }
                    },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.navigationBarsPadding().imePadding().padding(12.dp)) {
                        if (error != null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(error!!, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.error.value = null }) { Icon(Icons.Default.Close, "关闭提示") }
                            }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            OutlinedTextField(value = input, onValueChange = { input = it },
                                placeholder = { Text("问点什么，或描述你的想法…") },
                                modifier = Modifier.weight(1f), maxLines = 5, enabled = ready,
                                shape = RoundedCornerShape(24.dp))
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = { if (busy) vm.stop() else if (vm.send(input)) input = "" },
                                enabled = busy || (ready && input.isNotBlank() && !missingRole && !agentBusy), modifier = Modifier.size(52.dp),
                            ) { Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (busy) "停止生成" else "发送") }
                        }
                        Text("AI 可能出错，请核实重要信息", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AppUpdateCard(updateState, downloadState, updates::dismiss, { updates.check() }, updates::download, updates::cancelDownload, updates::install)
                OutlinedButton(onClick = { agentsOpen = true }, enabled = ready && !busy && !agentBusy,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
                    Text(if (missingRole) "角色已删除 · 重新选择" else "角色：${activeRole?.name ?: "默认"} · 管理角色与 Skills")
                }
            if (messages.isEmpty() && !busy) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(20.dp))
                    Text("从一个想法开始", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("一起整理思路、解答问题，\n让复杂的事情变得清晰。", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { settingsOpen = true }) { Text("配置你的模型") }
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
    toolApproval?.let { pending ->
        AlertDialog(onDismissRequest = { vm.approveTool(false) }, title = { Text("允许调用工具？") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text(pending.descriptor.description) }
                item { Text(pending.descriptor.name, style = MaterialTheme.typography.labelSmall) }
                item { SelectionContainer { Text(pending.call.argumentsJson) } }
            } },
            confirmButton = { TextButton(onClick = { vm.approveTool(true) }) { Text("允许这一次") } },
            dismissButton = { TextButton(onClick = { vm.approveTool(false) }) { Text("拒绝") } })
    }
    val app = LocalContext.current.applicationContext as SolvitApplication
    if (settingsOpen) SettingsScreen(updates, { settingsOpen = false }, { modelsOpen = true },
        { agentsOpen = true }, { pluginsOpen = true }, { backupOpen = true }, app.appearance)
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
    var picker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var fetchJob by remember { mutableStateOf<Job?>(null) }
    var fetchRevision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(base, key, listUrl, listAuth) {
        fetchRevision++; fetchJob?.cancel(); fetching = false; models = emptyList(); fetchError = null; picker = false
    }
    val candidate = ModelConfig(base.trim(), model.trim(), key.trim(), prompt, listUrl.trim(), listAuth)
    val invalid = candidate.validate()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { TopAppBar(title = { Text("模型设置") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.Default.Close, "关闭设置") }
            }) }, bottomBar = {
                Button(onClick = { onSave(candidate) }, enabled = !saving && invalid == null,
                    modifier = Modifier.navigationBarsPadding().imePadding().padding(16.dp).fillMaxWidth()) {
                    Text(if (saving) "保存中…" else "保存配置")
                }
            }) { padding ->
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Text("连接你选择的模型", style = MaterialTheme.typography.headlineSmall) }
                    item { Text("支持 OpenAI、DeepSeek 等兼容 Chat Completions API 的服务。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedTextField(base, { base = it }, label = { Text("API 基础地址") },
                        supportingText = { Text("例如 https://api.deepseek.com/v1；无需填写 /chat/completions") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    item { OutlinedTextField(model, { model = it }, label = { Text("模型名称") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    item { OutlinedTextField(listUrl, { listUrl = it }, label = { Text("模型列表 / 订阅地址（可选）") },
                        supportingText = { Text("留空时从 API 基础地址下的 /models 获取；也支持独立 JSON 模型列表地址。") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    if (listUrl.isNotBlank()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = listAuth, onCheckedChange = { listAuth = it }, enabled = !saving)
                            Text("向这个独立列表地址发送当前 API Key", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !saving && !fetching && candidate.validateConnection() == null, onClick = {
                            fetching = true; fetchError = null
                            val revision = ++fetchRevision
                            fetchJob = scope.launch {
                                try { val result = onFetchModels(candidate); if (revision == fetchRevision) { models = result; search = ""; picker = true } }
                                catch (e: CancellationException) { throw e }
                                catch (e: ModelException) { if (revision == fetchRevision) fetchError = e.message }
                                catch (e: Exception) { if (revision == fetchRevision) fetchError = "获取模型失败，请检查地址、密钥和网络" }
                                finally { if (revision == fetchRevision) fetching = false }
                            }
                        }) { Text(if (fetching) "正在获取…" else "获取模型") }
                        if (fetching) TextButton(onClick = { fetchRevision++; fetchJob?.cancel(); fetching = false }) { Text("取消") }
                        else if (models.isNotEmpty()) TextButton(onClick = { picker = true }) { Text("选择模型 (${models.size})") }
                    } }
                    if (fetching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (fetchError != null) item { Text(fetchError!!, color = MaterialTheme.colorScheme.error) }
                    item { OutlinedTextField(key, { key = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, enabled = !saving, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥") } },
                        supportingText = { Text("密钥加密保存在本机；无需认证的本地模型可留空。") }) }
                    item { OutlinedTextField(prompt, { prompt = it }, label = { Text("系统提示词") },
                        minLines = 3, maxLines = 8, enabled = !saving, modifier = Modifier.fillMaxWidth()) }
                    if (invalid != null) item { Text(invalid, color = MaterialTheme.colorScheme.error) }
                    if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
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
