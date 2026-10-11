package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.core.ContextBudgetPolicy

@Composable
internal fun ContextBudgetDialog(initial: ContextBudgetPolicy, onDismiss: () -> Unit, onSave: (ContextBudgetPolicy) -> Unit) {
    var window by remember { mutableStateOf(initial.windowTokens.toString()) }
    var output by remember { mutableStateOf(initial.outputTokens.toString()) }
    var headroom by remember { mutableStateOf(initial.headroomTokens.toString()) }
    var automatic by remember { mutableStateOf(initial.automaticSummary) }
    val policy = ContextBudgetPolicy(window.toIntOrNull() ?: 0, output.toIntOrNull() ?: 0, headroom.toIntOrNull() ?: -1, automatic)
    val invalid = runCatching { policy.validate() }.exceptionOrNull()?.message
    AlertDialog(onDismissRequest = onDismiss, title = { UiText("上下文管理") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { UiText("按服务实际容量设置窗口。使用保守 UTF-8 字节估计，并非模型实际 Token 用量；默认窗口是本机预算，不代表模型容量。摘要会额外调用当前模型，原聊天记录保留。") }
            item { OutlinedTextField(window, { window = it }, label = { UiText("上下文窗口") }, singleLine = true, modifier = Modifier.testTag("context_window")) }
            item { OutlinedTextField(output, { output = it }, label = { UiText("输出预留") }, singleLine = true, modifier = Modifier.testTag("context_output")) }
            item { OutlinedTextField(headroom, { headroom = it }, label = { UiText("压缩余量") }, singleLine = true, modifier = Modifier.testTag("context_headroom")) }
            item { Row { Switch(automatic, { automatic = it }, modifier = Modifier.testTag("context_automatic")); UiText("自动摘要旧回合") } }
            invalid?.let { item { UiText(it, color = MaterialTheme.colorScheme.error) } }
        }
    }, confirmButton = { TextButton(onClick = { onSave(policy) }, enabled = invalid == null, modifier = Modifier.testTag("save_context_budget")) { UiText("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { UiText("取消") } })
}
