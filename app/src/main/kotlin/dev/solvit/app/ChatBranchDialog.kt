package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.core.*

@Composable
fun ChatBranchDialog(message: ChatMessage, mode: BranchMode, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var edited by remember(message.id) { mutableStateOf(message.content) }
    val before = mode == BranchMode.BEFORE_USER
    AlertDialog(onDismissRequest={ if (!busy) onDismiss() },title={ Text(if (before) "编辑并创建分支" else "从这条回复创建分支") },
        text={ Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if (before) "复制这条问题之前的记录，编辑后的问题会放入新分支的输入框，由你发送。"
                else "复制到这轮回复为止的完整记录，包含工具结果和思考过程。")
            Text("原对话保持完整。已有工作区会共享同一目录和 Shell；没有工作区时不会自动创建。",style=MaterialTheme.typography.bodySmall)
            if (before) OutlinedTextField(edited,{ edited=it },label={ Text("修改问题") },enabled=!busy,
                modifier=Modifier.fillMaxWidth().testTag("branch_edit"),minLines=2,maxLines=8)
            else Text(message.content.take(400),style=MaterialTheme.typography.bodySmall)
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        } },confirmButton={ TextButton(onClick={ onConfirm(if (before) edited else "") },enabled=!busy && (!before || edited.isNotBlank())) {
            Text(if (busy) "创建中…" else "创建分支")
        } },dismissButton={ TextButton(onClick=onDismiss,enabled=!busy) { Text("取消") } })
}
