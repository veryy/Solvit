package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDialog(vm: WorkspaceViewModel, system: SystemExecutionService, createOnOpen: Boolean = false, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val directoryManager = remember(context) { dev.solvit.market.BuiltinWorkspaceManager(context) }
    val gitManager = remember(context) { dev.solvit.market.BuiltinWorkspaceManager(context, "dev.solvit.git") }
    val terminalManager = remember(context) { dev.solvit.market.BuiltinWorkspaceManager(context, "dev.solvit.terminal") }
    val environmentManager = remember(context) { dev.solvit.market.BuiltinWorkspaceManager(context, "dev.solvit.environment") }
    val locale = LocalUiLanguage.current
    val executionModes by system.modes.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val id by vm.workspaceId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    var pendingSubmission by rememberSaveable { mutableStateOf<Long?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chooser by rememberSaveable { mutableStateOf(false) }
    var creating by rememberSaveable { mutableStateOf(createOnOpen) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(submission, pendingSubmission) {
        submission?.takeIf { it.id == pendingSubmission && !it.running }?.let { result ->
            pendingSubmission = null
            if (result.boundWorkspaceId != null) { chooser = false; creating = false; name = "" }
        }
    }
    var shellId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var command by rememberSaveable(id, shellId) { mutableStateOf("") }
    var interactive by rememberSaveable(id) { mutableStateOf(true) }
    val shells = state.shells.filter { it.workspaceId == id }
    val activeShell = shellId ?: (shells.firstOrNull { it.runtime == ShellRuntime.UBUNTU } ?: shells.firstOrNull())?.id
    FullScreenPage(onDismissRequest = onDismiss) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Scaffold(topBar = {
            TopAppBar(title = {
                if (id == null) UiText("工作区") else TextButton(onClick = { chooser = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("workspace_switcher"), contentPadding = PaddingValues(0.dp)) {
                    Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.Start) {
                        UiText("工作区", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.workspaces.firstOrNull { it.id == id }?.name ?: uiString("打开工作区…"),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                    UiIcon(Icons.Default.KeyboardArrowDown, null)
                }
            }, navigationIcon = {
                IconButton(onClick = onDismiss) { UiIcon(Icons.Default.Close, "关闭工作区") }
            }, actions = {
                if (id != null && tab == 0) TextButton(onClick = { interactive = !interactive },
                    modifier = Modifier.testTag("workspace_terminal_mode")) { UiText(if (interactive) "命令任务" else "交互终端") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (id == null && tab != 3) {
                    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) {
                        UiText("此对话尚未绑定工作区")
                        TextButton(onClick = { vm.error.value = null; creating = true }, enabled = !busy) { UiText("新建工作区") }
                        TextButton(onClick = { vm.error.value = null; chooser = true }, enabled = !busy) { UiText("绑定已有工作区") }
                        TextButton(onClick = { tab = 3 }, modifier = Modifier.testTag("workspace_environment_open")) { Text("Linux") }
                        error?.takeIf { !creating && !chooser }?.let { UiText(it, color=MaterialTheme.colorScheme.error) }
                    }
                } else {
                TabRow(selectedTabIndex = tab, modifier = Modifier.testTag("workspace_tabs")) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.testTag("workspace_shell_tab"), text = { UiText("Shell") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.testTag("workspace_directory_tab"), text = { UiText("工作目录") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, modifier = Modifier.testTag("workspace_git_tab"), text = { Text("Git") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, modifier = Modifier.testTag("workspace_environment_tab"), text = { Text("Linux") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.takeIf { !creating && !chooser }?.let { UiText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                if (tab == 0) {
                    if (interactive) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            key(id, locale) { PluginPageScreen("交互终端", { vm.terminalPage(terminalManager, locale) }, embedded = true) {} }
                        }
                    } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("terminal_transcript")) {
                    item { Column {
                    if (executionModes[TERMINAL_OWNER] == ExecutionMode.ROOT) UiText("手动终端：Root 模式", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shells, key = { it.id }) { shell -> FilterChip(selected = shell.id == activeShell,
                            onClick = { shellId = shell.id }, label = { Text(shell.name) }) }
                        item { TextButton(onClick = vm::createShell, enabled = !busy && id != null) { UiText("＋ Shell") } }
                    }
                    } }
                        item { UiText("以本应用身份运行非交互式命令，可访问本应用的文件和网络。每条命令从工作区目录启动。", style = MaterialTheme.typography.bodySmall) }
                        items(jobs.filter { it.workspaceId == id && it.shellSessionId == activeShell }.reversed(), key = { it.id }) { job ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                SelectionContainer { Text("$ ${job.command}", fontFamily = FontFamily.Monospace) }
                                UiText(when (job.stage) {
                                    ShellStage.STARTING -> "启动中"; ShellStage.RUNNING -> "运行中"; ShellStage.COMPLETED -> "完成 · ${job.exitCode}"
                                    ShellStage.FAILED -> "失败 · ${job.exitCode ?: uiString("无法启动")}"; ShellStage.CANCELLED -> "已停止"; ShellStage.TIMED_OUT -> "已超时"
                                }, style = MaterialTheme.typography.labelSmall)
                                UiText(if (job.mode == ExecutionMode.ROOT) "Root" else "普通应用身份", style = MaterialTheme.typography.labelSmall)
                                if (job.truncated) UiText("仅保留最近输出", style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(job.output.joinToString("") { it.text }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                job.failure?.let { UiText(it, color = MaterialTheme.colorScheme.error) }
                                if (job.stage in setOf(ShellStage.STARTING, ShellStage.RUNNING)) TextButton(onClick = { vm.stop(job) }, enabled = !busy) { UiText("停止") }
                            } }
                        }
                    }
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(command, { command = it }, placeholder = { UiText("输入命令") }, maxLines = 4, modifier = Modifier.weight(1f).testTag("shell_command"))
                        Button(onClick = { activeShell?.let {
                            keyboard?.hide(); focus.clearFocus()
                            vm.run(it, command)
                            command = ""
                        } }, enabled = !busy && activeShell != null && command.isNotBlank()) { UiText("运行") }
                    }
                    }
                } else if (tab == 3) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(locale) { PluginPageScreen("Linux", { vm.environmentPage(environmentManager, locale) }, embedded = true) {} }
                    }
                } else if (tab == 2) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(id, locale) { PluginPageScreen("Git", { vm.gitPage(gitManager, locale) }, embedded = true) {} }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(locale) { PluginPageScreen("工作目录", { vm.directoryPage(directoryManager, locale) }, embedded = true) {} }
                    }
                }
                }
            }
        }
    }
    if (chooser) AlertDialog(onDismissRequest = { chooser = false }, title = { UiText("绑定工作区") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("workspace_form_progress"))
            error?.let { UiText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace_form_error")) }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(state.workspaces, key = { it.id }) { workspace -> TextButton(
                    onClick = { pendingSubmission = vm.bind(workspace.id) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text((if (workspace.id == id) "✓ " else "") + workspace.name)
                } }
            }
        }
    }, confirmButton = { TextButton(onClick = { vm.error.value = null; chooser = false; creating = true }, enabled = !busy) { UiText("新建工作区") } },
        dismissButton = { TextButton(onClick = { chooser = false }) { UiText("关闭") } })
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { UiText("新建工作区") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { UiText("名称") }, singleLine = true, enabled = !busy,
                modifier = Modifier.testTag("workspace_create_name"))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("workspace_form_progress"))
            error?.let { UiText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("workspace_form_error")) }
        }
    }, confirmButton = { TextButton(onClick = { pendingSubmission = vm.create(name) }, enabled = !busy && name.isNotBlank()) { UiText("创建并绑定") } },
        dismissButton = { TextButton(onClick = { creating = false }) { UiText("取消") } })
}
