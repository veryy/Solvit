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
fun WorkspaceDialog(vm: WorkspaceViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val id by vm.workspaceId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fileText by vm.fileText.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val directory by vm.directory.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var chooser by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var shellId by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var command by rememberSaveable(id, shellId) { mutableStateOf("") }
    var path by rememberSaveable(id) { mutableStateOf("notes.txt") }
    var editor by rememberSaveable(id) { mutableStateOf(false) }
    var showDrafts by rememberSaveable(id) { mutableStateOf(false) }
    val shells = state.shells.filter { it.workspaceId == id }
    val activeShell = shellId ?: shells.firstOrNull()?.id
    FullScreenPage(onDismissRequest = onDismiss) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        Scaffold(topBar = { TopAppBar(title = { Text("工作区") }, navigationIcon = {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭工作区") }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                TextButton(onClick = { chooser = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(state.workspaces.firstOrNull { it.id == id }?.name ?: "打开工作区…")
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Shell") })
                    Tab(selected = tab == 1, onClick = { tab = 1; vm.browse(directory) }, text = { Text("工作目录") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                if (tab == 0) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(shells, key = { it.id }) { shell -> FilterChip(selected = shell.id == activeShell,
                            onClick = { shellId = shell.id }, label = { Text(shell.name) }) }
                        item { TextButton(onClick = vm::createShell, enabled = !busy && id != null) { Text("＋ Shell") } }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { Text("以本应用身份运行非交互式命令，可访问本应用的文件和网络。每条命令从工作区目录启动。", style = MaterialTheme.typography.bodySmall) }
                        items(jobs.filter { it.workspaceId == id && it.shellSessionId == activeShell }.reversed(), key = { it.id }) { job ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                                SelectionContainer { Text("$ ${job.command}", fontFamily = FontFamily.Monospace) }
                                Text(when (job.stage) {
                                    ShellStage.STARTING -> "启动中"; ShellStage.RUNNING -> "运行中"; ShellStage.COMPLETED -> "完成 · ${job.exitCode}"
                                    ShellStage.FAILED -> "失败 · ${job.exitCode ?: "无法启动"}"; ShellStage.CANCELLED -> "已停止"; ShellStage.TIMED_OUT -> "已超时"
                                }, style = MaterialTheme.typography.labelSmall)
                                if (job.truncated) Text("仅保留最近输出", style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(job.output.joinToString("") { it.text }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                                job.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                if (job.stage in setOf(ShellStage.STARTING, ShellStage.RUNNING)) TextButton(onClick = { vm.stop(job) }, enabled = !busy) { Text("停止") }
                            } }
                        }
                    }
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(command, { command = it }, placeholder = { Text("输入命令") }, maxLines = 4, modifier = Modifier.weight(1f).testTag("shell_command"))
                        Button(onClick = { activeShell?.let {
                            keyboard?.hide(); focus.clearFocus()
                            vm.run(it, command); command = ""
                        } }, enabled = !busy && activeShell != null && command.isNotBlank()) { Text("运行") }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { SelectionContainer { Text(location + if (directory.isEmpty()) "" else "/$directory", style = MaterialTheme.typography.bodySmall) } }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (directory.isNotEmpty()) TextButton(onClick = { vm.browse(directory.substringBeforeLast('/', "")) }, enabled = !busy) { Text("上级目录") }
                            TextButton(onClick = { vm.browse(directory) }, enabled = !busy) { Text("刷新") }
                            TextButton(onClick = { path = if (directory.isEmpty()) "notes.txt" else "$directory/notes.txt"; editor = true }, enabled = !busy) { Text("新建文本") }
                        } }
                        if (files.isEmpty()) item { Text("目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(files, key = { it.path }) { file ->
                            OutlinedButton(onClick = { if (file.directory) vm.browse(file.path) else { path = file.path; editor = true; vm.read(file.path) } },
                                enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text((if (file.directory) "目录  " else "文件  ") + file.name) }
                        }
                        if (editor) {
                        item { OutlinedTextField(path, { path = it }, label = { Text("工作区内相对路径") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.read(path) }, enabled = !busy && id != null) { Text("读取") }
                            Button(onClick = { vm.write(path) }, enabled = !busy && id != null) { Text("保存") }
                        } }
                        item { OutlinedTextField(fileText, { vm.fileText.value = it }, label = { Text("文本内容") }, minLines = 12, modifier = Modifier.fillMaxWidth()) }
                        }
                        item { TextButton(onClick = { showDrafts = !showDrafts }) { Text("插件草稿 (${drafts.count { it.workspaceId == id }})") } }
                        if (showDrafts) {
                        item { Text("AI 生成的 JS 插件包保存在此工作目录。查看代码和权限后安装；到插件页单独启用。") }
                        items(drafts.filter { it.workspaceId == id }.reversed(), key = { it.id }) { draft ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                Text(draft.name, style = MaterialTheme.typography.titleMedium)
                                Text("${draft.pluginId} · ${draft.version}")
                                Text("已通过启动校验 · 尚未自动安装", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { vm.review(draft) }, enabled = !busy) { Text("查看代码并安装") }
                            } }
                        }
                        }
                    }
                }
            }
        }
    }
    preview?.let { (draft, text) -> FullScreenPage(onDismissRequest = { if (!busy) vm.preview.value = null }) {
        Scaffold(topBar = { TopAppBar(title = { Text("审阅 ${draft.name}") }, navigationIcon = {
            IconButton(onClick = { vm.preview.value = null }, enabled = !busy) { Icon(Icons.Default.Close, "关闭草稿") }
        }) }, bottomBar = { Button(onClick = { vm.install(draft) }, enabled = !busy,
            modifier = Modifier.padding(16.dp).fillMaxWidth()) { Text(if (busy) "安装中…" else "安装为停用插件") } }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("所需权限：${draft.permissions.joinToString().ifBlank { "无" }}\n同 ID 的已安装插件会被替换并停用，启用时需重新授权。") }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                item { SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
            }
        }
    } }
    if (chooser) AlertDialog(onDismissRequest = { chooser = false }, title = { Text("绑定工作区") }, text = {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(state.workspaces, key = { it.id }) { workspace -> TextButton(onClick = { vm.bind(workspace.id); chooser = false }, modifier = Modifier.fillMaxWidth()) {
                Text((if (workspace.id == id) "✓ " else "") + workspace.name)
            } }
        }
    }, confirmButton = { TextButton(onClick = { chooser = false; creating = true }) { Text("新建工作区") } },
        dismissButton = { TextButton(onClick = { chooser = false }) { Text("关闭") } })
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("新建工作区") }, text = {
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { vm.create(name); creating = false; name = "" }, enabled = name.isNotBlank()) { Text("创建并绑定") } })
}
