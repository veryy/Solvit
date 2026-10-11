package dev.solvit.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentLibraryDialog(vm: AgentLibraryViewModel, onDismiss: () -> Unit) {
    val roles by vm.roles.collectAsStateWithLifecycle()
    val active by vm.activeRole.collectAsStateWithLifecycle()
    val missing by vm.missingRole.collectAsStateWithLifecycle()
    val skills by vm.skills.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val content by vm.content.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AgentRole?>(null) }
    var deleteRole by remember { mutableStateOf<AgentRole?>(null) }
    var deleteSkill by remember { mutableStateOf<SkillSummary?>(null) }
    var replace by remember(pending) { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareImport {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { AgentLibraryViewModel.readImport(it) } ?: error("无法读取技能文件")
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportText; exportText = null
        if (uri != null && text != null) vm.writeExport {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入技能文件")
            }
        }
    }
    FullScreenPage(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("角色与 Skills") }, navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.Close, "关闭") }
                })
            }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("角色") })
                        Tab(selected = tab == 1, onClick = { tab = 1; vm.refreshSkills() }, text = { Text("技能库") })
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (tab == 0) {
                            item { Text("为当前对话选择角色。默认角色使用全部已启用插件；自定义角色只使用勾选的插件和技能。", style = MaterialTheme.typography.bodyMedium) }
                            item { OutlinedButton(onClick = { vm.selectRole(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (active == null && !missing) "✓ 默认角色" else "使用默认角色")
                            } }
                            item { Button(onClick = { editing = AgentRole(UUID.randomUUID().toString(), "") }, enabled = !busy) { Text("创建角色") } }
                            items(roles, key = { it.id }) { role ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                    Text(role.name, style = MaterialTheme.typography.titleMedium)
                                    Text("${role.pluginIds.size} 个插件 · ${role.skillIds.size} 个技能", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { vm.selectRole(role.id) }, enabled = !busy) { Text(if (active?.id == role.id) "✓ 当前使用" else "用于此对话") }
                                        TextButton(onClick = { editing = role }, enabled = !busy) { Text("编辑") }
                                        TextButton(onClick = { deleteRole = role }, enabled = !busy) { Text("删除") }
                                    }
                                } }
                            }
                        } else {
                            item { Text("请先在插件页启用 Skills 技能库。导入 SKILL.md、单个技能的文本 ZIP 或导出的 JSON 包；正文按需加载，脚本仅作为文本保存。") }
                            item { Row {
                                Button(onClick = { importPicker.launch(arrayOf("*/*")) }, enabled = !busy && installed.any { it.id == "dev.solvit.skills" && it.enabled }) { Text("导入技能") }
                                TextButton(onClick = vm::refreshSkills, enabled = !busy) { Text("刷新") }
                            } }
                            items(skills, key = { it.name }) { skill ->
                                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                    Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                    Text(skill.description)
                                    Text("${skill.resources.size} 个文本资源", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { vm.loadSkill(skill.name) }, enabled = !busy) { Text("查看") }
                                        TextButton(onClick = { vm.exportSkill(skill.name) { text -> exportText = text; export.launch("${skill.name}.skill.json") } }, enabled = !busy) { Text("导出") }
                                        TextButton(onClick = { deleteSkill = skill }, enabled = !busy) { Text("删除") }
                                    }
                                } }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { role -> RoleEditor(role, installed, skills, busy, error,
        onDismiss = { if (!busy) editing = null }, onSave = { vm.saveRole(it) { editing = null } }) }
    pending?.let { bundle ->
        AlertDialog(onDismissRequest = { if (!busy) vm.pendingImport.value = null }, title = { Text("导入这个技能？") },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { Text("${bundle.files.size} 个文本文件。确认后保存到本机技能库。") }
                item { SelectionContainer { Text(bundle.files["SKILL.md"].orEmpty().take(8000)) } }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replace, { replace = it }, enabled = !busy); Text("同名技能存在时替换")
                } }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            } }, confirmButton = { TextButton(onClick = { vm.confirmImport(replace) }, enabled = !busy) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = { vm.pendingImport.value = null }, enabled = !busy) { Text("取消") } })
    }
    content?.let { detail ->
        AlertDialog(onDismissRequest = { vm.content.value = null }, title = { Text(detail.summary.name) },
            text = { LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item { SelectionContainer { Text(detail.instructions) } }
                if (detail.summary.resources.isNotEmpty()) item { Text("文本资源：\n" + detail.summary.resources.joinToString("\n")) }
            } }, confirmButton = { TextButton(onClick = { vm.content.value = null }) { Text("关闭") } })
    }
    deleteRole?.let { role ->
        AlertDialog(onDismissRequest = { deleteRole = null }, title = { Text("删除角色？") },
            text = { Text("删除“${role.name}”后，使用它的对话需重新选择角色。聊天记录会保留。") },
            confirmButton = { TextButton(onClick = { vm.deleteRole(role.id); deleteRole = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteRole = null }) { Text("取消") } })
    }
    deleteSkill?.let { skill ->
        AlertDialog(onDismissRequest = { deleteSkill = null }, title = { Text("删除技能？") }, text = { Text("删除 ${skill.name} 及其文本资源。角色中的旧绑定将不再加载此技能。") },
            confirmButton = { TextButton(onClick = { vm.removeSkill(skill.name); deleteSkill = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteSkill = null }) { Text("取消") } })
    }
}

@Composable
private fun RoleEditor(role: AgentRole, installed: List<InstalledExtension>, skills: List<SkillSummary>, busy: Boolean,
    error: String?, onDismiss: () -> Unit, onSave: (AgentRole) -> Unit) {
    var name by remember(role.id) { mutableStateOf(role.name) }
    var prompt by remember(role.id) { mutableStateOf(role.prompt) }
    var plugins by remember(role.id) { mutableStateOf(role.pluginIds) }
    var selectedSkills by remember(role.id) { mutableStateOf(role.skillIds) }
    val candidate = AgentRole(role.id, name.trim(), prompt, plugins, selectedSkills)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑角色") },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("名称") }, enabled = !busy) }
            item { OutlinedTextField(prompt, { prompt = it }, label = { Text("角色提示词") }, minLines = 3, maxLines = 8, enabled = !busy) }
            item { Text("允许使用的插件（仍需在插件页启用和授权）") }
            items(installed, key = { it.id }) { extension ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(extension.id in plugins, { checked ->
                        plugins = if (checked) plugins + extension.id else plugins - extension.id
                        if (!checked && extension.id == "dev.solvit.skills") selectedSkills = emptySet()
                    }, enabled = !busy)
                    Text(extension.name + if (extension.enabled) "" else " · 未启用")
                }
            }
            if ("dev.solvit.skills" in plugins) {
                item { Text("可用技能。只加载勾选技能的目录和正文；未勾选时不会提供技能。") }
                if (skills.isEmpty()) item { Text("请启用 Skills 插件，在技能库导入或刷新后选择。") }
                items(skills, key = { "skill-${it.name}" }) { skill ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(skill.name in selectedSkills, { checked -> selectedSkills = if (checked) selectedSkills + skill.name else selectedSkills - skill.name }, enabled = !busy)
                        Text(skill.name)
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        } }, confirmButton = { TextButton(onClick = { onSave(candidate) }, enabled = !busy && runCatching { candidate.validate() }.isSuccess) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } })
}
