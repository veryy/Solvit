package dev.solvit.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

private data class RestoredConfiguration(val model: ModelConfig, val roles: List<AgentRole>, val theme: String, val dynamic: Boolean, val language: String)

private fun configurationJson(config: ModelConfig, roles: List<AgentRole>, theme: String, dynamic: Boolean, language: String): ByteArray = JSONObject()
    .put("format", 1).put("model", JSONObject().put("base", config.baseUrl).put("name", config.model)
        .put("key", config.apiKey).put("prompt", config.systemPrompt)
        .put("modelListUrl", config.modelListUrl).put("modelListUsesApiKey", config.modelListUsesApiKey))
    .put("roles", JSONArray(roles.map { role -> JSONObject().put("id", role.id).put("name", role.name).put("prompt", role.prompt)
        .put("plugins", JSONArray(role.pluginIds.sorted())).put("skills", JSONArray(role.skillIds.sorted())) }))
    .put("theme", theme).put("dynamic", dynamic).put("language", language).toString().toByteArray(Charsets.UTF_8)

private fun parseConfiguration(bytes: ByteArray): RestoredConfiguration {
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    require(root.getInt("format") == 1)
    val m = root.getJSONObject("model")
    val model = ModelConfig(m.getString("base"), m.getString("name"), m.getString("key"), m.getString("prompt"),
        m.optString("modelListUrl", ""), m.optBoolean("modelListUsesApiKey", false))
    require(model.validate() == null)
    val entries = root.getJSONArray("roles")
    require(entries.length() <= 64)
    val roles = (0 until entries.length()).map { index ->
        val r = entries.getJSONObject(index)
        fun strings(key: String): Set<String> { val list = r.getJSONArray(key); require(list.length() <= 64)
            return (0 until list.length()).map { list.getString(it) }.toSet() }
        AgentRole(r.getString("id"), r.getString("name"), r.getString("prompt"), strings("plugins"), strings("skills")).also { it.validate() }
    }
    require(roles.map { it.id }.toSet().size == roles.size)
    val theme = root.getString("theme"); require(theme in setOf("system", "light", "dark"))
    val language = root.optString("language", "zh"); require(uiLanguages.any { it.code == language })
    return RestoredConfiguration(model, roles, theme, root.getBoolean("dynamic"), language)
}

@Composable
fun ConfigurationBackupDialog(vm: ChatViewModel, roles: RoleService, appearance: AppearanceStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val language = (context.applicationContext as SolvitApplication).language
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var archive by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<RestoredConfiguration?>(null) }
    DisposableEffect(Unit) { onDispose { archive?.fill(0) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = archive; archive = null
        if (uri == null || bytes == null) { bytes?.fill(0); busy = false }
        else scope.launch {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法写入备份") }
                message = "加密配置备份已保存。请保存文件和密码，卸载后可用此文件恢复。"
                password = ""
            } catch (e: Exception) { message = "备份文件保存失败，请重试" }
            finally { bytes.fill(0); busy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false
        else scope.launch {
            val chars = password.toCharArray()
            try {
                preview = withContext(Dispatchers.IO) {
                    val encrypted = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val n = stream.read(buffer); if (n == -1) break
                            require(out.size() + n <= ConfigurationBackupCipher.MAX_PAYLOAD + 64); out.write(buffer, 0, n) }
                        out.toByteArray()
                    } ?: error("无法读取备份")
                    val plain = ConfigurationBackupCipher.decrypt(encrypted, chars)
                    try { parseConfiguration(plain) } finally { plain.fill(0); encrypted.fill(0) }
                }
                message = null
            } catch (e: Exception) { message = "无法读取备份：密码错误、文件损坏或格式不兼容。已有配置未修改。" }
            finally { chars.fill('\u0000'); password = ""; busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { UiText("配置备份与恢复") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UiText("备份包含模型地址、API Key、提示词、角色、语言和外观设置。文件使用密码加密，可在重装或换机后恢复。")
            UiText("聊天记录、插件包、插件密钥和插件记忆暂不包含；Skills 可在技能库单独导出。卸载前请先备份，已删除且未备份的数据无法恢复。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(password, { password = it }, label = { UiText("备份密码（至少 8 位）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && password.length in 8..1024 && vm.ready.value && !vm.busy.value, onClick = {
                    busy = true; message = null
                    scope.launch {
                        val chars = password.toCharArray()
                        try {
                            val plain = configurationJson(vm.config.value, roles.roles().first(), appearance.theme.value, appearance.dynamicColors.value, language.language.value)
                            archive = withContext(Dispatchers.IO) { try { ConfigurationBackupCipher.encrypt(plain, chars) } finally { plain.fill(0) } }
                            export.launch("solvit-configuration.solvitbackup")
                        } catch (e: Exception) { message = "无法创建配置备份，请检查配置后重试"; busy = false }
                        finally { chars.fill('\u0000') }
                    }
                }) { UiText("导出备份") }
                OutlinedButton(enabled = !busy && password.length in 8..1024 && !vm.busy.value, onClick = { busy = true; import.launch(arrayOf("*/*")) }) { UiText("导入备份") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { UiText(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { UiText("关闭") } })
    preview?.let { incoming -> AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { UiText("恢复这些配置？") },
        text = { UiText("模型：${incoming.model.model}\n角色：${incoming.roles.size} 个\n\n将替换当前模型和外观配置，并合并角色（相同 ID 会覆盖）。插件仍需重新安装和授权。") },
        confirmButton = { TextButton(enabled = !busy && !vm.busy.value, onClick = {
            busy = true
            scope.launch {
                val previousConfig = vm.config.value
                var previousRoles = emptyList<AgentRole>()
                var started = false
                val previousTheme = appearance.theme.value; val previousDynamic = appearance.dynamicColors.value; val previousLanguage = language.language.value
                try {
                    previousRoles = roles.roles().first()
                    check(!vm.busy.value && !vm.saving.value) { "请等待当前操作结束" }
                    require((previousRoles.map { it.id } + incoming.roles.map { it.id }).toSet().size <= 64)
                    started = true
                    incoming.roles.forEach { roles.save(it) }
                    vm.restoreConfig(incoming.model)
                    appearance.setTheme(incoming.theme); appearance.setDynamicColors(incoming.dynamic); language.set(incoming.language)
                    preview = null; message = "配置已恢复，API Key 已重新加密保存在本机。"
                } catch (e: Exception) {
                    val rolledBack = !started || withContext(NonCancellable) { runCatching {
                        incoming.roles.filter { r -> previousRoles.none { it.id == r.id } }.forEach { roles.delete(it.id) }
                        previousRoles.forEach { roles.save(it) }; vm.restoreConfig(previousConfig)
                        appearance.setTheme(previousTheme); appearance.setDynamicColors(previousDynamic); language.set(previousLanguage)
                    }.isSuccess }
                    if (e is CancellationException) throw e
                    message = if (rolledBack) "恢复失败，已有配置未修改或已恢复，请检查本机存储后重试。"
                        else "恢复未完成且回滚失败，请保留备份文件并检查本机存储。"
                } finally { busy = false }
            }
        }) { UiText("确认恢复") } }, dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { UiText("取消") } }) }
}
