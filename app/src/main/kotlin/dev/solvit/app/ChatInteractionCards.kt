@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.ExecutionMode

/** Host-owned authorization stays adjacent to the conversation, without a modal window. */
@Composable
internal fun ChatToolApprovalCard(pending: ChatViewModel.ToolApproval, approve: (Boolean, Boolean) -> Unit) {
    val resolving by pending.resolving.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxWidth().testTag("chat_tool_approval"), shape=MaterialTheme.shapes.medium,
        color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            UiText("等待工具授权", style=MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=150.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                item { Text(pending.descriptor.name, style=MaterialTheme.typography.labelMedium) }
                item { Text(pending.descriptor.description, style=MaterialTheme.typography.bodySmall) }
                pending.descriptor.executionMode?.let { mode -> item {
                    UiText(if(mode==ExecutionMode.ROOT) "执行身份：Root（设备最高权限）" else "执行身份：普通应用")
                } }
                item { SelectionContainer { Text(pending.call.argumentsJson, style=MaterialTheme.typography.bodySmall) } }
                item { UiText("不再询问仅用于此角色和当前版本的此工具，后续不同参数也可执行。可在设置 → 权限与执行方式清除。", style=MaterialTheme.typography.labelSmall) }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={approve(false,false)},enabled=!resolving,modifier=Modifier.testTag("tool_reject")) { UiText("拒绝") }
                TextButton(onClick={approve(true,false)},enabled=!resolving,modifier=Modifier.testTag("tool_allow_once")) { UiText("允许这一次") }
                TextButton(onClick={approve(true,true)},enabled=!resolving,modifier=Modifier.testTag("tool_allow_remember")) { UiText("允许且不再询问") }
            }
        }
    }
}

@Composable
internal fun ChatQueuePanel(items: List<ChatViewModel.QueuedMessage>, paused: Boolean, switching: Boolean,
    edit: (String,String) -> Boolean, remove: (String) -> Unit, sendNow: (String) -> Boolean, resume: () -> Unit) {
    if(items.isEmpty())return
    var expanded by remember { mutableStateOf(true) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editedText by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().testTag("chat_queue"), shape=MaterialTheme.shapes.medium,
        color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal=12.dp,vertical=6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={expanded=!expanded},modifier=Modifier.weight(1f)) { UiText("等待队列 · ${items.size}") }
                if(paused)TextButton(onClick=resume,enabled=!switching,modifier=Modifier.testTag("queue_resume")) { UiText("继续发送") }
            }
            if(expanded)LazyColumn(Modifier.fillMaxWidth().heightIn(max=180.dp)) {
                items(items,key={it.id}) { item ->
                    Column(Modifier.fillMaxWidth().testTag("queued_${item.id}")) {
                        if(editingId==item.id) {
                            OutlinedTextField(editedText,{editedText=it},modifier=Modifier.fillMaxWidth().testTag("queue_edit_input"),maxLines=4,enabled=!switching)
                            Row {
                                TextButton(onClick={if(edit(item.id,editedText))editingId=null},enabled=!switching&&editedText.isNotBlank()) { UiText("保存") }
                                TextButton(onClick={editingId=null}) { UiText("取消") }
                            }
                        }else {
                            Text(item.text,maxLines=2,style=MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick={sendNow(item.id)},enabled=!switching,modifier=Modifier.testTag("queue_send_${item.id}")) { UiText("立即发送并打断") }
                                TextButton(onClick={editingId=item.id;editedText=item.text},enabled=!switching) { UiText("编辑") }
                                TextButton(onClick={remove(item.id)},enabled=!switching) { UiText("移除") }
                            }
                        }
                    }
                }
            }
        }
    }
}
