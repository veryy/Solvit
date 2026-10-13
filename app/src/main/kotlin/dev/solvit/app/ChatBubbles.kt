package dev.solvit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.solvit.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

@Composable
fun ChatBubble(row: ChatRow, onBranch: ((ChatMessage, BranchMode) -> Unit)? = null,
    loadExecutionEvents: (suspend (ExecutionRun) -> List<RecordedExecutionEvent>)? = null,
    onOpenFileChanges: ((ExecutionRun) -> Unit)? = null, assistantName: String = "Solvit") {
    val user = row.user
    val width = LocalConfiguration.current.screenWidthDp.dp * .88f
    Column(Modifier.fillMaxWidth().testTag(if (user != null) "user_message" else "assistant_message"),
        horizontalAlignment = if (user != null) Alignment.End else Alignment.Start) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            UiText(if (user != null) "你" else assistantName, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal=4.dp, vertical=4.dp))
            val anchor = user ?: row.messages.lastOrNull { it.role == Role.ASSISTANT }
            if (onBranch != null && !row.generating && anchor != null) {
                var menu by rememberSaveable(row.id) { mutableStateOf(false) }
                Box {
                    IconButton(onClick={ menu=true },modifier=Modifier.size(32.dp).testTag("message_actions_${anchor.id}")) {
                        UiIcon(Icons.Default.MoreVert,"消息操作",Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded=menu,onDismissRequest={ menu=false }) {
                        DropdownMenuItem(text={ UiText(if (user != null) "编辑并分支" else "从这里分支") }, onClick={
                            menu=false; onBranch(anchor, if (user != null) BranchMode.BEFORE_USER else BranchMode.AFTER_REPLY)
                        })
                    }
                }
            }
        }
        Surface(Modifier.widthIn(max=width), shape = if (user != null)
            RoundedCornerShape(topStart=10.dp,topEnd=2.dp,bottomEnd=10.dp,bottomStart=10.dp)
            else RoundedCornerShape(topStart=2.dp,topEnd=10.dp,bottomEnd=10.dp,bottomStart=10.dp),
            color = if (user != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if (user != null) SelectionContainer { Text(user.content, style=MaterialTheme.typography.bodyLarge) }
                else {
                    AssistantContents(row)
                    row.execution?.let { ExecutionDetails(it,loadExecutionEvents,onOpenFileChanges) }
                }
            }
        }
    }
}

@Composable
private fun AssistantContents(row: ChatRow) {
    val results = row.messages.filter { it.role == Role.TOOL }.associateBy { it.toolCallId }
    val activity = row.activity.associateBy { it.call.id }
    val emitted = mutableSetOf<String>()
    var hasContent = false
    row.messages.filter { it.role == Role.ASSISTANT }.forEach { message ->
        val split = splitThinking(message.content)
        val thinking = listOf(message.reasoning, split.thinking).filter { it.isNotBlank() }.joinToString("\n\n")
        if (thinking.isNotBlank()) { ThinkingSection(thinking, row.generating, message.id); hasContent = true }
        if (split.answer.isNotBlank()) { MarkdownBody(split.answer, streaming = row.generating && message.id == "live-answer"); hasContent = true }
        message.toolCalls.forEach { call ->
            if (emitted.add(call.id)) {
                val result = results[call.id]
                ToolSection(activity[call.id] ?: ModelChunk.ToolActivity(call,
                    result?.toolStage ?: if (result != null) ToolStage.SUCCEEDED else ToolStage.CONFIRMING, result?.content),
                    missingResult=!row.generating && result==null)
                hasContent = true
            }
        }
    }
    // A tool can enter confirming/running before its assistant transcript is delivered.
    row.activity.filter { it.call.id !in emitted }.forEach { ToolSection(it); hasContent = true }
    if (!hasContent) UiText(if (row.generating) "正在回复…" else if (row.execution!=null && row.messages.isEmpty()) "对话中断 · 已保留执行记录" else if (row.messages.any { it.status == MessageStatus.INTERRUPTED }) "已停止生成" else "未收到回复",
        color=MaterialTheme.colorScheme.onSurfaceVariant)
    if (!row.generating) row.messages.lastOrNull { it.status != MessageStatus.COMPLETE }?.let { message ->
        UiText(if (message.status == MessageStatus.INTERRUPTED) "已停止 · 此回复不参与后续上下文" else "回复失败 · 此回复不参与后续上下文",
            style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MarkdownBody(content: String, streaming: Boolean = false) {
    val latestContent by rememberUpdatedState(content)
    var rendered by remember { mutableStateOf(content) }
    LaunchedEffect(streaming) {
        if (streaming) snapshotFlow { latestContent }.conflate().collect {
            rendered = it
            // Parsing the complete prefix on every playback frame stalls long replies.
            // Conflate changes during this interval, including the last mutation.
            delay(80)
        }
    }
    // Completion and cancellation must show the exact settled prefix immediately.
    RichReplyBody(if (streaming) rendered else content, streaming)
}

@Composable
private fun RichReplyBody(content: String, streaming: Boolean) {
    SelectionContainer {
        Markdown(content=content, modifier=Modifier.fillMaxWidth()
            .testTag(if (streaming) "streaming_body" else "markdown_body"))
    }
}

@Composable
private fun ThinkingSection(thinking: String, generating: Boolean, id: String) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical=4.dp).testTag("thinking_toggle"), verticalAlignment=Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp))
            UiText(if (generating) "思考过程" else "查看思考过程", style=MaterialTheme.typography.labelMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(6.dp)) {
            Column(Modifier.padding(10.dp).testTag("thinking_details")) { MarkdownBody(thinking) }
        }
    }
}

@Composable
private fun ToolSection(activity: ModelChunk.ToolActivity, missingResult: Boolean = false) {
    var expanded by rememberSaveable(activity.call.id) { mutableStateOf(false) }
    val title = when (activity.call.name) {
        "dev_solvit_clock_current_time" -> "当前时间"
        else -> activity.call.name
    }
    Column(Modifier.fillMaxWidth().testTag("inline_tool")) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical=5.dp).testTag("tool_toggle"), verticalAlignment=Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp))
            UiText(title, style=MaterialTheme.typography.labelMedium, modifier=Modifier.weight(1f))
            if (!missingResult && activity.stage == ToolStage.RUNNING) CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.dp)
        }
        if (expanded) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(6.dp)) {
            Column(Modifier.padding(10.dp).testTag("tool_details"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                UiText(if(missingResult) "未记录结果" else when(activity.stage) {
                    ToolStage.CONFIRMING -> "等待确认"; ToolStage.RUNNING -> "执行中"; ToolStage.SUCCEEDED -> "已完成"
                    ToolStage.DENIED -> "已拒绝"; ToolStage.FAILED -> "执行失败"
                },style=MaterialTheme.typography.labelSmall)
                UiText("参数",style=MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(activity.call.argumentsJson,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                activity.result?.let { UiText("结果",style=MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(it,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
