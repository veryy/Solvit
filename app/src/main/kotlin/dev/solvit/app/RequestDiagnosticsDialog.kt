package dev.solvit.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.RequestInspectionService
import dev.solvit.core.ContextSummary
import dev.solvit.core.ContextHookFailure
import dev.solvit.chat.ContextPreparationService
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
fun RequestDiagnosticsDialog(inspection: RequestInspectionService, vm: ChatViewModel, summaries: ContextPreparationService? = null, onDismiss: () -> Unit) {
    val prepared by inspection.latest.collectAsStateWithLifecycle()
    val timing by vm.replyTiming.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showingSummary by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<ContextSummary?>(null) }
    var summaryLoading by remember { mutableStateOf(false) }
    var summaryError by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { UiText("最近请求诊断") },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp).testTag("request_diagnostics")) {
            item { UiText("仅展示最近请求的元数据，不保存提示词、密钥或工具参数。") }
            val request = prepared
            if (request == null) item { UiText("尚未组装模型请求") }
            else {
                item { UiText("实际角色：${request.roleName}") }
                item { UiText("协议：${request.providerId}") }
                item { UiText("模型：${request.model}") }
                item { UiText("消息数：${request.messageCount}") }
                item { UiText("系统上下文字符数：${request.systemCharacters}") }
                request.contextProblems.forEach { problem -> item {
                    val reason = when (problem.failure) {
                        ContextHookFailure.FAILED -> "读取失败"
                        ContextHookFailure.TIMED_OUT -> "读取超时"
                        ContextHookFailure.RESOURCE_LIMIT -> "超过资料大小限制"
                    }
                    UiText("插件资料未获得：${problem.pluginId}", color = MaterialTheme.colorScheme.error)
                    UiText(reason)
                } }
                request.sources.forEach { source -> item {
                    UiText("来源：${source.name} · ${source.source} · ${source.characters}")
                } }
                item { UiText("提供的工具数：${request.tools.size}") }
                request.contextMeasurement?.let { measurement -> item {
                    UiText("上下文估计：${measurement.estimatedTokens} / ${measurement.inputBudget}")
                    UiText("估计包含消息与工具定义，不是服务商实际 Token 用量。")
                } }
                val summarySessionId = request.sessionId
                if (summaries != null && summarySessionId != null) item {
                    TextButton(onClick = {
                        showingSummary = true; summaryLoading = true; summaryError = false; summary = null
                        scope.launch {
                            try { summary = summaries.summary(summarySessionId) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { summaryError = true }
                            finally { summaryLoading = false }
                        }
                    }, enabled = !summaryLoading, modifier = Modifier.testTag("view_context_summary")) { UiText("查看历史摘要") }
                }
                request.tools.forEach { tool -> item { UiText(tool) } }
            }
            val run = timing
            if (run != null && (request == null || (run.sessionId == request.sessionId && request.preparedAtMs >= run.startedAtMs))) {
                item { UiText("首段文本到达应用：${run.firstTextMs?.toString() ?: "—"} ms") }
                item { UiText("首段思考到达应用：${run.firstReasoningMs?.toString() ?: "—"} ms") }
                item { UiText("首段文字显示：${run.firstVisibleMs?.toString() ?: "—"} ms") }
                item { UiText("模型事件结束：${run.receivedEndMs?.toString() ?: "—"} ms") }
                item { UiText("显示结束：${run.displayEndMs?.toString() ?: "—"} ms") }
                item { UiText("接收字符数：${run.receivedCharacters}") }
                item { UiText("状态：${run.outcome}") }
            }
            item { UiText("到达应用的时间包含网络、解析和执行等待，不能单独证明服务端缓冲。") }
        } }, confirmButton = { TextButton(onClick = onDismiss) { UiText("关闭") } })
    if (showingSummary) AlertDialog(onDismissRequest = { showingSummary = false }, title = { UiText("历史摘要") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp).testTag("context_summary")) {
            if (summaryLoading) item { UiText("正在读取…") }
            else if (summaryError) item { UiText("无法读取摘要，原聊天记录仍保留") }
            else if (summary == null) item { UiText("这个对话尚未生成摘要") }
            else {
                item { UiText("原聊天记录保留；摘要只用于后续模型上下文。") }
                item { Text(summary!!.text) }
            }
        }
    }, confirmButton = { TextButton(onClick = { showingSummary = false }) { UiText("关闭") } })
}
