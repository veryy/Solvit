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
                            UiText("Solvit 初始化失败，请重启应用。聊天记录不会被自动清除。")
                        }
                        else -> {
                            val service = result!!.getOrThrow()
                            val factory = remember(service) { object : ViewModelProvider.Factory {
                                @Suppress("UNCHECKED_CAST")
                                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(service, app.settings, app.roles, app.modelCatalog, app.branches, app.toolApprovals, app.modelProfiles) as T
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
    val app = context.applicationContext as SolvitApplication
    val language by app.language.language.collectAsStateWithLifecycle()
    val strings = remember(app) { UiStrings.load(app) }
    val mode by appearance.theme.collectAsStateWithLifecycle()
    val dynamic by appearance.dynamicColors.collectAsStateWithLifecycle()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        dynamic && Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFBACDA8), secondary = Color(0xFFBBCAB4))
        else -> lightColorScheme(primary = Color(0xFF526743), secondary = Color(0xFF576350), tertiary = Color(0xFF38666B))
    }
    CompositionLocalProvider(LocalUiStrings provides strings, LocalUiLanguage provides language) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(vm: ChatViewModel, updates: UpdateViewModel, marketplace: MarketplaceViewModel, agents: AgentLibraryViewModel, workspaces: WorkspaceViewModel) {
    val app = LocalContext.current.applicationContext as SolvitApplication
    val contextProgress by app.contextPreparation.progress.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val downloadState by updates.downloadState.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val selected by vm.sessionId.collectAsStateWithLifecycle()
    val storedMessages by vm.messages.collectAsStateWithLifecycle()
    val messages = storedMessages.filter { it.sessionId == selected }
    val config by vm.config.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val modelConfigured by vm.modelConfigured.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val reasoning by vm.reasoning.collectAsStateWithLifecycle()
    val transcript by vm.liveTranscript.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val errorDetails by vm.errorDetails.collectAsStateWithLifecycle()
    var showErrorDetails by remember { mutableStateOf(false) }
    val branching by vm.branching.collectAsStateWithLifecycle()
    val branchError by vm.branchError.collectAsStateWithLifecycle()
    val branchInput by vm.branchInput.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val toolApproval by vm.toolApproval.collectAsStateWithLifecycle()
    val toolActivity by vm.toolActivity.collectAsStateWithLifecycle()
    val chatRows = presentChat(messages,busy,vm.turnBaseline,vm.sendingText,transcript,draft,reasoning,toolActivity,holdSettlement=busy)
    val missingRole by agents.missingRole.collectAsStateWithLifecycle()
    val agentBusy by agents.busy.collectAsStateWithLifecycle()
    var input by rememberSaveable(selected) { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val memoryManager = remember(app) { dev.solvit.market.BuiltinMemoryManager(app) }
    var memoryOpen by rememberSaveable { mutableStateOf(false) }
    var memoryTasksOpen by rememberSaveable { mutableStateOf(false) }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var modelsOpen by rememberSaveable { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var pluginsOpen by rememberSaveable { mutableStateOf(false) }
    var workspaceOpen by rememberSaveable { mutableStateOf(false) }
    var workspaceCreateRequested by rememberSaveable { mutableStateOf(false) }
    val workspaceId by workspaces.workspaceId.collectAsStateWithLifecycle()
    val workspaceState by workspaces.state.collectAsStateWithLifecycle()
    var agentsOpen by rememberSaveable { mutableStateOf(false) }
    var branchTarget by remember { mutableStateOf<Pair<ChatMessage,BranchMode>?>(null) }
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
    LaunchedEffect(selected, branchInput) {
        branchInput?.takeIf { it.first == selected }?.let {
            input=it.second
            withFrameNanos { }; inputFocus.requestFocus(); keyboard?.show()
            if (vm.branchInput.value == it) vm.branchInput.value=null
        }
    }
    LaunchedEffect(selected) { agents.setSession(selected); workspaces.selectChat(selected) }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.width(LocalConfiguration.current.screenWidthDp.dp * 0.75f).testTag("chat_drawer")) {
            UiText("Solvit", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(24.dp))
            FilledTonalButton(onClick = { vm.newChat(); scope.launch { drawer.close() } }, enabled = ready && !busy && !branching,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("new_chat")) {
                UiIcon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); UiText("新对话")
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { session ->
                    NavigationDrawerItem(
                        label = { Text(session.title + if (session.originSessionId != null && !session.title.endsWith(" · 分支")) uiString(" · 分支") else "", maxLines = 1) },
                        selected = selected == session.id,
                        onClick = { if (!busy) { vm.select(session.id); scope.launch { drawer.close() } } },
                        badge = { IconButton(onClick = { deleteTarget = session }, enabled = !busy) {
                            UiIcon(Icons.Default.DeleteOutline, "删除 ${session.title}")
                        } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            HorizontalDivider()
            TextButton(onClick = { scope.launch { drawer.close(); settingsOpen = true } },
                modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                UiIcon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); UiText("设置")
            }
        }
    }) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Column {
                        UiText("Solvit", fontWeight = FontWeight.SemiBold)
                        Text(config.model, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } },
                    navigationIcon = { IconButton(onClick = { hideInput(); scope.launch { drawer.open() } }) { UiIcon(Icons.Default.Menu, "对话历史") } },
                    actions = {
                        IconButton(onClick = { hideInput(); pluginsOpen = true }) { UiIcon(Icons.Default.Extension, "插件市场") }
                        IconButton(onClick = { hideInput(); settingsOpen = true }) { UiIcon(Icons.Default.Settings, "设置") }
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
                                UiText(error!!, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = vm::dismissError) { UiIcon(Icons.Default.Close, "关闭提示") }
                            }
                            if (errorDetails != null) TextButton(onClick = { showErrorDetails = true }) { UiText("查看详细信息") }
                        }
                        Row(verticalAlignment = Alignment.Bottom) {
                            TextField(value = input, onValueChange = { input = it },
                                placeholder = { UiText("问点什么，或描述你的想法…") },
                                modifier = Modifier.weight(1f).focusRequester(inputFocus), maxLines = 5, enabled = ready,
                                shape = RoundedCornerShape(24.dp),
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent))
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = { if (busy) vm.stop() else if (vm.send(input)) input = "" },
                                enabled = busy || (ready && !branching && input.isNotBlank() && !missingRole && !agentBusy), modifier = Modifier.size(52.dp),
                            ) { Icon(if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send, if (busy) "停止生成" else "发送") }
                        }
                    }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AppUpdateCard(updateState, downloadState, updates::dismiss, { updates.check() }, updates::download, updates::cancelDownload, updates::install)
                contextProgress?.takeIf { busy && it.sessionId == selected }?.let { progress ->
                    UiText("正在压缩历史，第 ${progress.batch} 批", modifier = Modifier.padding(horizontal = 16.dp).testTag("context_preparing"), style = MaterialTheme.typography.bodySmall)
                }
                if (workspaceId != null) TextButton(onClick = { hideInput(); workspaceCreateRequested = false; workspaceOpen = true }, enabled = ready && !busy,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    UiText("工作区：${workspaceState.workspaces.firstOrNull { it.id == workspaceId }?.name ?: uiString("打开中…")} · Shell / 文件")
                }
            if (messages.isEmpty() && !busy) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val expanded = maxHeight >= 240.dp
                    val showTitle = maxHeight >= 100.dp
                    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 4.dp)) {
                        Icon(painterResource(R.drawable.ic_solvit_monochrome), null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (expanded) 80.dp else 48.dp))
                        if (showTitle) UiText("从一个想法开始", style = if (expanded) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
                        if (expanded) {
                            UiText("一起整理思路、解答问题", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!modelConfigured) OutlinedButton(onClick = { hideInput(); modelsOpen = true }) { UiText("配置你的模型") }
                        }
                        if (workspaceId == null && showTitle) OutlinedButton(onClick = { hideInput(); workspaceCreateRequested = true; workspaceOpen = true },enabled=ready) { UiText("新建工作区") }
                    }
                }
            } else {
                ChatTimeline(chatRows, selected, Modifier.weight(1f),
                    onBranch = if (busy || branching) null else { message, mode ->
                        hideInput(); vm.branchError.value = null; branchTarget = message to mode
                    }, footer = if (workspaceId == null && !busy) ({
                        TextButton(onClick = { hideInput(); workspaceCreateRequested = true; workspaceOpen = true }) { UiText("新建工作区") }
                    }) else null)

            }
            }
        }
    }
    branchTarget?.let { (message,mode) ->
        ChatBranchDialog(message,mode,branching,branchError,
            onDismiss={ if (!branching) branchTarget=null },onConfirm={ edited ->
                vm.fork(BranchPoint(message.id,mode),edited) { branchTarget=null }
            })
    }
    if (showErrorDetails) errorDetails?.let { ModelErrorDialog(it) { showErrorDetails = false } }
    LaunchedEffect(errorDetails) { showErrorDetails = false }
    toolApproval?.let { pending ->
        AlertDialog(onDismissRequest = { vm.approveTool(false) }, title = { UiText("允许调用工具？") },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { Text(pending.descriptor.description) }
                item { Text(pending.descriptor.name, style = MaterialTheme.typography.labelSmall) }
                pending.descriptor.executionMode?.let { mode -> item {
                    UiText(if (mode == ExecutionMode.ROOT) "执行身份：Root（设备最高权限）" else "执行身份：普通应用")
                } }
                item { SelectionContainer { Text(pending.call.argumentsJson) } }
                item { UiText("不再询问仅用于此角色和当前版本的此工具，后续不同参数也可执行。可在设置 → 权限与执行方式清除。", style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { Column {
                TextButton(onClick = { vm.approveTool(true) }) { UiText("允许这一次") }
                TextButton(onClick = { vm.approveTool(true, remember = true) }) { UiText("允许且不再询问") }
            } },
            dismissButton = { TextButton(onClick = { vm.approveTool(false) }) { UiText("拒绝") } })
    }
    if (workspaceOpen) WorkspaceDialog(workspaces, app.systemExecution, createOnOpen=workspaceCreateRequested) { workspaceOpen = false }
    if (settingsOpen) SettingsScreen(updates, { settingsOpen = false }, { modelsOpen = true },
        { agentsOpen = true }, { pluginsOpen = true }, { backupOpen = true }, app.appearance, { permissionsOpen = true }, { diagnosticsOpen = true }, { memoryTasksOpen = true }, { memoryOpen = true })
    if (memoryOpen) MemoryManagementScreen(memoryManager, app.postTurnWorker) { memoryOpen = false }
    if (memoryTasksOpen) MemoryTasksScreen(app.postTurnWorker) { memoryTasksOpen = false }
    if (diagnosticsOpen) RequestDiagnosticsDialog(app.requestInspection, vm, app.contextPreparation) { diagnosticsOpen = false }
    if (permissionsOpen) ExecutionPermissionsScreen(app.systemExecution, app.marketplace, app.toolApprovals) { permissionsOpen = false }
    if (modelsOpen) ModelProfilesScreen(app.modelProfiles, busy, onDismiss = { modelsOpen = false }, onFetchModels = vm::discoverModels, providers = app.modelProviders)
    if (backupOpen) ConfigurationBackupDialog(vm, app.roles, app.appearance, onDismiss = { backupOpen = false })
    if (pluginsOpen) PluginMarketplaceDialog(marketplace, onDismiss = { pluginsOpen = false })
    if (agentsOpen) AgentLibraryDialog(agents, onDismiss = { agentsOpen = false })
    deleteTarget?.let { session ->
        AlertDialog(onDismissRequest = { deleteTarget = null }, title = { UiText("删除对话？") },
            text = { UiText("“${session.title}” 的记录将从本机删除。") },
            confirmButton = { TextButton(onClick = { vm.delete(session.id); deleteTarget = null }) { UiText("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { UiText("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSettings(config: ModelConfig, saving: Boolean, error: String?, onDismiss: () -> Unit, onSave: (ModelConfig) -> Unit,
    onFetchModels: suspend (ModelConfig) -> List<AvailableModel>, profileName: String? = null, onProfileNameChanged: (String) -> Unit = {}, providers: ModelProviderRegistry) {
    // API keys deliberately do not use rememberSaveable / saved-instance state.
    val availableProviders by providers.available.collectAsStateWithLifecycle()
    var protocol by remember { mutableStateOf(config.providerId) }
    var options by remember { mutableStateOf(config.providerOptions) }
    var protocolPicker by remember { mutableStateOf(false) }
    var budget by remember { mutableStateOf(config.contextBudget) }
    var budgetDialog by remember { mutableStateOf(false) }
    val descriptor = availableProviders.firstOrNull { it.id == protocol }
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
    LaunchedEffect(protocol, base, key, listUrl, listAuth) {
        fetchRevision++; fetchJob?.cancel(); fetching = false; models = emptyList(); fetchError = null; fetchDetails = null; showFetchDetails = false; picker = false
    }
    val candidate = ModelConfig(base.trim(), model.trim(), key.trim(), prompt, listUrl.trim(), listAuth, protocol, options, budget)
    val invalid = providers.validate(candidate)
    FullScreenPage(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(topBar = { TopAppBar(title = { UiText("模型设置") }, navigationIcon = {
                IconButton(onClick = onDismiss, enabled = !saving) { UiIcon(Icons.Default.Close, "关闭设置") }
            }) }, bottomBar = {
                Button(onClick = { onSave(candidate) }, enabled = !saving && invalid == null && (profileName == null || (profileName.isNotBlank() && profileName.length <= 80)),
                    modifier = Modifier.navigationBarsPadding().imePadding().padding(16.dp).fillMaxWidth().testTag("save_model_profile")) {
                    UiText(if (saving) "保存中…" else "保存配置")
                }
            }) { padding ->
                LazyColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("model_settings_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (profileName != null) item { OutlinedTextField(profileName,onProfileNameChanged,label={ UiText("配置名称") },singleLine=true,enabled=!saving,modifier=Modifier.fillMaxWidth().testTag("model_profile_name")) }
                    item { UiText("连接你选择的模型", style = MaterialTheme.typography.headlineSmall) }
                    item { Box {
                        OutlinedButton(onClick={ protocolPicker=true },enabled=!saving,modifier=Modifier.testTag("model_protocol")) {
                            UiText("模型协议"); Spacer(Modifier.width(8.dp));Text(descriptor?.name ?: protocol)
                        }
                        DropdownMenu(expanded=protocolPicker,onDismissRequest={ protocolPicker=false }) {
                            availableProviders.forEach { entry -> DropdownMenuItem(text={ Text(entry.name) },modifier=Modifier.testTag("model_protocol_${entry.id}"),onClick={
                                if(protocol!=entry.id) {
                                    protocol=entry.id;base=entry.defaultBaseUrl;model="";key="";listUrl="";listAuth=false
                                    options=entry.options.associate { it.key to it.defaultValue }
                                }
                                protocolPicker=false
                            }) }
                        }
                    } }
                    item { UiText("切换协议后请填写对应服务的模型和密钥；保存后才生效。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedTextField(base, { base = it }, label = { UiText("API 基础地址") },
                        supportingText = { descriptor?.let { UiText(it.endpointHint) } },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    item { OutlinedTextField(key, { key = it }, label = { UiText("API Key") }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, enabled = !saving, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { visible = !visible }) { UiIcon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (visible) "隐藏密钥" else "显示密钥") } },
                        supportingText = { UiText("密钥加密保存在本机；无需认证的本地模型可留空。") }) }
                    item { OutlinedTextField(listUrl, { listUrl = it }, label = { UiText("模型列表 / 订阅地址（可选）") },
                        supportingText = { UiText("留空时从 API 基础地址下的 /models 获取；也支持独立 JSON 模型列表地址。") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving) }
                    if (listUrl.isNotBlank()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = listAuth, onCheckedChange = { listAuth = it }, enabled = !saving)
                            UiText("不同站点的列表也使用此 API Key（同站点默认使用）", style = MaterialTheme.typography.bodySmall)
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
                        }) { UiText(if (fetching) "正在获取…" else "获取模型") }
                        if (fetching) TextButton(onClick = { fetchRevision++; fetchJob?.cancel(); fetching = false }) { UiText("取消") }
                        else if (models.isNotEmpty()) TextButton(onClick = { picker = true }) { UiText("选择模型 (${models.size})") }
                    } }
                    if (fetching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (fetchError != null) item {
                        UiText(fetchError!!, color = MaterialTheme.colorScheme.error)
                        if (fetchDetails != null) TextButton(onClick = { showFetchDetails = true }) { UiText("查看详细信息") }
                    }
                    item { OutlinedTextField(model, { model = it }, label = { UiText("模型名称") },
                        modifier = Modifier.fillMaxWidth().testTag("model_name"), singleLine = true, enabled = !saving) }
                    descriptor?.options?.forEach { option -> item {
                        val current = options[option.key] ?: option.defaultValue
                        if(option.choices.isEmpty()) OutlinedTextField(current,{ options=options+(option.key to it) },label={ UiText(option.label) },singleLine=true,enabled=!saving,modifier=Modifier.fillMaxWidth().testTag("provider_option_${option.key}"))
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(onClick={ expanded=true },enabled=!saving,modifier=Modifier.testTag("provider_option_${option.key}")) {
                                    UiText(option.label); Spacer(Modifier.width(8.dp)); UiText(option.choices.firstOrNull { it.first==current }?.second ?: current)
                                }
                                DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) {
                                    option.choices.forEach { (value,label) -> DropdownMenuItem(text={ UiText(label) },onClick={ options=options+(option.key to value);expanded=false }) }
                                }
                            }
                        }
                    } }
                    if(descriptor?.options?.any { it.key=="thinking_mode" }==true) item {
                        UiText("模型默认由服务端决定；自适应、手动预算和关闭模式的支持因模型而异。若服务拒绝，可查看返回详情。工具执行期间沿用发起轮次的思考参数。")
                    }
                    item { OutlinedButton(onClick = { budgetDialog = true }, enabled = !saving, modifier = Modifier.testTag("context_budget_settings")) { UiText("上下文管理") } }
                    item { OutlinedTextField(prompt, { prompt = it }, label = { UiText("系统提示词") },
                        minLines = 3, maxLines = 8, enabled = !saving, modifier = Modifier.fillMaxWidth()) }
                    if (invalid != null) item { UiText(invalid, color = MaterialTheme.colorScheme.error) }
                    if (error != null) item { UiText(error, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (budgetDialog) ContextBudgetDialog(budget, { budgetDialog = false }) { budget = it; budgetDialog = false }
    if (showFetchDetails) fetchDetails?.let { ModelErrorDialog(it) { showFetchDetails = false } }
    if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { UiText("选择模型") }, text = {
        Column {
            OutlinedTextField(search, { search = it }, label = { UiText("搜索模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(models.filter { it.id.contains(search, true) || it.name.contains(search, true) }, key = { it.id }) { item ->
                    TextButton(onClick = { model = item.id; picker = false }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text(item.name); if (item.name != item.id) Text(item.id, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { picker = false }) { UiText("关闭") } })
}


