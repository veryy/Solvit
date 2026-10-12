package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceDialog(vm: WorkspaceViewModel, system: SystemExecutionService, createOnOpen: Boolean = false, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val directoryManager = remember(context) { dev.solvit.market.BuiltinWorkspaceManager(context) }
    val locale = LocalUiLanguage.current
    val executionModes by system.modes.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val terminals by vm.terminalSessions.collectAsStateWithLifecycle()
    val id by vm.workspaceId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chooser by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(createOnOpen) }
    var name by remember { mutableStateOf("") }
    var shellId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var command by rememberSaveable(id, shellId) { mutableStateOf("") }
    var interactive by rememberSaveable(id) { mutableStateOf(false) }
    var selectedTerminal by rememberSaveable(id, shellId) { mutableStateOf<String?>(null) }
    val shells = state.shells.filter { it.workspaceId == id }
    val activeShell = shellId ?: shells.firstOrNull()?.id
    val shellTerminals = terminals.filter { it.workspaceId == id && it.shellId == activeShell }
    val activeTerminal = shellTerminals.firstOrNull { it.id == selectedTerminal }
        ?: shellTerminals.lastOrNull { it.owner == TERMINAL_OWNER && it.active }
        ?: shellTerminals.lastOrNull { it.owner == TERMINAL_OWNER }
    FullScreenPage(onDismissRequest = onDismiss) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Scaffold(topBar = { TopAppBar(title = { UiText("工作区") }, navigationIcon = {
            IconButton(onClick = onDismiss) { UiIcon(Icons.Default.Close, "关闭工作区") }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (id == null) {
                    Column(Modifier.weight(1f).fillMaxWidth(),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                        UiText("此对话尚未绑定工作区")
                        TextButton(onClick = { creating = true }) { UiText("新建工作区") }
                        TextButton(onClick = { chooser = true }) { UiText("绑定已有工作区") }
                        error?.let { UiText(it,color=MaterialTheme.colorScheme.error) }
                    }
                } else {
                TextButton(onClick = { chooser = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(state.workspaces.firstOrNull { it.id == id }?.name ?: uiString("打开工作区…"))
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { UiText("Shell") })
                    Tab(selected = tab == 1, onClick = { tab = 1; vm.browse(directory) }, text = { UiText("工作目录") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { UiText(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                if (tab == 0) {
                    // Keep the composer reachable when the IME leaves only a short viewport.
                    // All selectors and transcript controls scroll in the remaining space.
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("terminal_transcript")) {
                    item { Column {
                    if (executionModes[TERMINAL_OWNER] == ExecutionMode.ROOT) UiText("手动终端：Root 模式", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shells, key = { it.id }) { shell -> FilterChip(selected = shell.id == activeShell,
                            onClick = { shellId = shell.id }, label = { Text(shell.name) }) }
                        item { TextButton(onClick = vm::createShell, enabled = !busy && id != null) { UiText("＋ Shell") } }
                    }
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !interactive, onClick = { interactive = false }, label = { UiText("普通命令") })
                        FilterChip(selected = interactive, onClick = { interactive = true }, label = { UiText("交互终端") })
                    }
                    } }
                    if (interactive) {
                        item { LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { FilterChip(selected = activeTerminal == null, onClick = { selectedTerminal = null }, label = { UiText("手动 Shell") }) }
                            items(shellTerminals, key = { it.id }) { session -> FilterChip(selected = activeTerminal?.id == session.id,
                                onClick = { selectedTerminal = session.id }, label = { UiText((if (session.owner == TERMINAL_OWNER) uiString("手动") else session.owner) +
                                    (session.roleId?.let { " · $it" } ?: "") + if (session.active) "" else uiString(" · 已关闭")) }) }
                        }
                        }
                        item { Column(Modifier.padding(12.dp)) {
                                UiText("同一终端保留目录和环境。输入会直接发送给 Shell；支持交互提示和 Ctrl-C。", style = MaterialTheme.typography.bodySmall)
                                if (activeTerminal == null) UiText("输入命令后打开终端。", modifier = Modifier.padding(vertical = 16.dp))
                                activeTerminal?.let { session ->
                                    if (session.truncated) UiText("仅保留最近输出", style = MaterialTheme.typography.labelSmall)
                                    TerminalScreen(session) { vm.sendKeys(session, it) }
                                    session.failure?.let { UiText(it, color = MaterialTheme.colorScheme.error) }
                                    if (!session.active) UiText("终端已关闭 · ${session.exitCode ?: "—"}")
                                }
                            }
                        }
                        item { activeTerminal?.takeIf { it.active }?.let { session -> Row(Modifier.padding(horizontal = 12.dp)) {
                            TextButton(onClick = { vm.interrupt(session) }, enabled = !busy) { UiText("Ctrl-C") }
                            TextButton(onClick = { vm.closeTerminal(session) }, enabled = !busy) { UiText("关闭终端") }
                        } } }
                    } else {
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
                    }
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(command, { command = it }, placeholder = { UiText(if (interactive) "命令或交互输入" else "输入命令") }, maxLines = 4, modifier = Modifier.weight(1f).testTag("shell_command"))
                        Button(onClick = { activeShell?.let {
                            keyboard?.hide(); focus.clearFocus()
                            if (interactive) vm.send(it, activeTerminal?.takeIf { session -> session.active }?.id, command) else vm.run(it, command)
                            command = ""
                        } }, enabled = !busy && activeShell != null && (command.isNotBlank() || interactive)) { UiText(if (interactive) "发送输入" else "运行") }
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(locale) { PluginPageScreen("工作目录", { vm.directoryPage(directoryManager, locale) }, embedded = true) {} }
                    }
                }
            }
        }
    }
    if (chooser) AlertDialog(onDismissRequest = { chooser = false }, title = { UiText("绑定工作区") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(state.workspaces, key = { it.id }) { workspace -> TextButton(onClick = { vm.bind(workspace.id); chooser = false }, modifier = Modifier.fillMaxWidth()) {
                Text((if (workspace.id == id) "✓ " else "") + workspace.name)
            } }
        }
    }, confirmButton = { TextButton(onClick = { chooser = false; creating = true }) { UiText("新建工作区") } },
        dismissButton = { TextButton(onClick = { chooser = false }) { UiText("关闭") } })
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { UiText("新建工作区") }, text = {
        OutlinedTextField(name, { name = it }, label = { UiText("名称") }, singleLine = true, modifier = Modifier.testTag("workspace_create_name"))
    }, confirmButton = { TextButton(onClick = { vm.create(name); creating = false; name = "" }, enabled = name.isNotBlank()) { UiText("创建并绑定") } })
}
