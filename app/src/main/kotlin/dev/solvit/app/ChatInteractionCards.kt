@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.ExecutionMode

/** Host-owned authorization stays adjacent to the conversation, without a modal window. */
@Composable
internal fun ChatToolApprovalCard(pending: ChatViewModel.ToolApproval, modifier: Modifier = Modifier, approve: (Boolean, Boolean) -> Unit) {
    val resolving by pending.resolving.collectAsStateWithLifecycle()
    Surface(modifier.fillMaxWidth().testTag("chat_tool_approval"), shape=MaterialTheme.shapes.medium,
        color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            UiText("等待工具授权", style=MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max=150.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                item { Text(pending.descriptor.name, style=MaterialTheme.typography.labelMedium) }
                item { Text(pending.descriptor.description, style=MaterialTheme.typography.bodySmall) }
                pending.descriptor.executionMode?.let { mode -> item {
                    UiText(if(mode==ExecutionMode.ROOT) "执行身份：Root（设备最高权限）" else "执行身份：普通应用")
                } }
                item { SelectionContainer { Text(pending.call.argumentsJson, style=MaterialTheme.typography.bodySmall) } }
                item { UiText("不再询问仅用于此角色和当前版本的此工具，后续不同参数也可执行。可在设置 → 权限与执行方式清除。", style=MaterialTheme.typography.labelSmall) }
            }
            // Give every decision its own width. FlowRow could place the last decision
            // on a new row outside the bounded card at large text sizes.
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(4.dp), verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={approve(false,false)},enabled=!resolving,
                    modifier=Modifier.weight(1f).testTag("tool_reject"),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) { UiText("拒绝", textAlign=TextAlign.Center) }
                FilledTonalButton(onClick={approve(true,false)},enabled=!resolving,
                    modifier=Modifier.weight(1f).testTag("tool_allow_once"),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) { UiText("允许这一次", textAlign=TextAlign.Center) }
                TextButton(onClick={approve(true,true)},enabled=!resolving,
                    modifier=Modifier.weight(1f).testTag("tool_allow_remember"),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) { UiText("允许且不再询问", textAlign=TextAlign.Center) }
            }
        }
    }
}

@Composable
internal fun ChatQueuePanel(items: List<ChatViewModel.QueuedMessage>, paused: Boolean, switching: Boolean,
    edit: (String,String) -> Boolean, remove: (String) -> Unit, sendNow: (String) -> Boolean, resume: () -> Unit, modifier: Modifier = Modifier) {
    if(items.isEmpty())return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editedText by rememberSaveable { mutableStateOf("") }
    val expansionState = uiString(if (expanded) "收起" else "展开")
    Surface(modifier.fillMaxWidth().testTag("chat_queue"), shape=MaterialTheme.shapes.medium,
        color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal=12.dp,vertical=4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={expanded=!expanded}, modifier=Modifier.weight(1f).testTag("queue_toggle")
                    .semantics { stateDescription=expansionState }, contentPadding=PaddingValues(vertical=8.dp)) {
                    UiText("等待队列 · ${items.size}", Modifier.weight(1f), style=MaterialTheme.typography.labelLarge)
                    Icon(if(expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
                if(paused)TextButton(onClick=resume,enabled=!switching,modifier=Modifier.testTag("queue_resume")) { UiText("继续发送") }
            }
            if(expanded)LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max=180.dp).testTag("chat_queue_items"),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                items(items,key={it.id}) { item ->
                    Column(Modifier.fillMaxWidth().testTag("queued_${item.id}")) {
                        if(editingId==item.id) {
                            OutlinedTextField(editedText,{editedText=it},modifier=Modifier.fillMaxWidth().testTag("queue_edit_input"),maxLines=4,enabled=!switching)
                            Row {
                                TextButton(onClick={if(edit(item.id,editedText))editingId=null},enabled=!switching&&(editedText.isNotBlank()||item.attachments.isNotEmpty())) { UiText("保存") }
                                TextButton(onClick={editingId=null}) { UiText("取消") }
                            }
                        }else {
                            Text(item.text.ifBlank { item.attachments.joinToString { it.name } }, modifier=Modifier.padding(top=4.dp),
                                maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                            if(item.text.isNotBlank() && item.attachments.isNotEmpty()) Text(item.attachments.joinToString { it.name },
                                maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={sendNow(item.id)},enabled=!switching,modifier=Modifier.weight(1f).testTag("queue_send_${item.id}")) {
                                    UiText("立即发送并打断", style=MaterialTheme.typography.labelMedium)
                                }
                                var menu by remember(item.id) { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick={menu=true},enabled=!switching,modifier=Modifier.testTag("queue_menu_${item.id}")) {
                                        UiIcon(Icons.Default.MoreVert,"队列消息操作")
                                    }
                                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                        DropdownMenuItem(text={UiText("编辑")},onClick={menu=false;editingId=item.id;editedText=item.text},enabled=!switching)
                                        DropdownMenuItem(text={UiText("移除")},onClick={menu=false;remove(item.id)},enabled=!switching)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
