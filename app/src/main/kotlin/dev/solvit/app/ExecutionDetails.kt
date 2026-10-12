package dev.solvit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import kotlinx.coroutines.CancellationException

@Composable
fun ExecutionDetails(run: ExecutionRun, load: (suspend (ExecutionRun)->List<RecordedExecutionEvent>)?) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    var events by remember(run.id) { mutableStateOf<List<RecordedExecutionEvent>>(emptyList()) }
    var loading by remember(run.id) { mutableStateOf(false) }
    var failed by remember(run.id) { mutableStateOf(false) }
    LaunchedEffect(run,expanded,load) {
        if(expanded) {
            loading=true;failed=false
            try { events=(load ?: error("Unavailable journal"))(run) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { failed=true }
            finally { loading=false }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("execution_journal")) {
        Row(Modifier.fillMaxWidth().clickable { expanded=!expanded }.padding(vertical=5.dp).testTag("execution_journal_toggle"),verticalAlignment=Alignment.CenterVertically) {
            Icon(if(expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,null,Modifier.size(18.dp))
            UiText("执行记录",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            UiText(when(run.status) {
                ExecutionStatus.RUNNING->"执行中";ExecutionStatus.COMPLETE->"回复已结束";ExecutionStatus.CANCELLED->"已取消"
                ExecutionStatus.FAILED->"执行失败";ExecutionStatus.PROCESS_INTERRUPTED->"应用中断"
            },style=MaterialTheme.typography.labelSmall)
        }
        if(expanded) Column(Modifier.fillMaxWidth().heightIn(max=420.dp).verticalScroll(rememberScrollState()).testTag("execution_journal_details"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(run.status !in setOf(ExecutionStatus.RUNNING,ExecutionStatus.COMPLETE)) UiText("仅显示已记录的事实，不会自动重跑操作",style=MaterialTheme.typography.bodySmall)
            when {
                loading->UiText("正在读取执行记录…")
                failed->UiText("读取执行记录失败")
                else-> {
                    events.filter { it.event.kind==ExecutionKind.STEP_STARTED }.forEach { started ->
                        val step=started.event.step
                        Row { UiText("模型步骤",style=MaterialTheme.typography.labelSmall);Text(" $step",style=MaterialTheme.typography.labelSmall) }
                        events.filter { it.event.step==step && it.event.kind==ExecutionKind.TOOL_PROPOSED }.forEach { proposed ->
                            val call=proposed.event.call!!
                            val latest=events.lastOrNull { it.event.step==step && it.event.callId==call.id }?.event
                            Column(Modifier.fillMaxWidth().testTag("execution_call_${call.id}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(call.name,style=MaterialTheme.typography.labelMedium)
                                UiText(when(latest?.kind) {
                                    ExecutionKind.TOOL_FINISHED->when(latest.stage) { ToolStage.SUCCEEDED->"工具已返回";ToolStage.DENIED->"已拒绝";else->"执行失败" }
                                    ExecutionKind.TOOL_STARTED->if(run.status==ExecutionStatus.RUNNING) "执行中" else "结果未知，请先检查实际状态"
                                    else->if(run.status==ExecutionStatus.RUNNING) "等待确认" else "未开始执行"
                                },style=MaterialTheme.typography.labelSmall)
                                UiText("参数",style=MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(call.argumentsJson,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                                latest?.result?.let { result ->
                                    UiText("结果",style=MaterialTheme.typography.labelSmall)
                                    SelectionContainer { Text(result,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
                                }
                                if(proposed.event.truncated || latest?.truncated==true) UiText("记录片段已截断",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if(events.isEmpty()) UiText("暂无执行记录")
                }
            }
        }
    }
}
