package dev.solvit.app

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.core.RequestInspectionService

@Composable
fun RequestDiagnosticsDialog(inspection: RequestInspectionService, vm: ChatViewModel, onDismiss: () -> Unit) {
    val prepared by inspection.latest.collectAsStateWithLifecycle()
    val timing by vm.replyTiming.collectAsStateWithLifecycle()
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
                request.sources.forEach { source -> item {
                    UiText("来源：${source.name} · ${source.source} · ${source.characters}")
                } }
                item { UiText("提供的工具数：${request.tools.size}") }
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
}
