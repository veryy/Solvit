package dev.solvit.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.solvit.core.*

/** Stored observations only. Opening this view never reads or executes the current workspace. */
@Composable
internal fun WorkspaceChangesReview(events: List<RecordedExecutionEvent>) {
    val baseline=events.firstOrNull { it.event.kind==ExecutionKind.WORKSPACE_BASELINE } ?: return
    val latest=events.lastOrNull { it.event.kind==ExecutionKind.WORKSPACE_OBSERVED }
    var expanded by rememberSaveable(baseline.recordedAt,baseline.sequence) { mutableStateOf(false) }
    val comparison=remember(baseline,latest) {
        latest?.let { compareWorkspaceSnapshots(requireNotNull(baseline.event.workspace),requireNotNull(it.event.workspace)) }
    }
    Column(Modifier.fillMaxWidth().testTag("workspace_changes"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded=!expanded }.padding(vertical=6.dp).testTag("workspace_changes_toggle")) {
            UiText("文件变更",style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
            Text("${comparison?.changes?.size ?: "?"} ${if(expanded) "▾" else "▸"}",style=MaterialTheme.typography.labelMedium)
        }
        if(expanded) {
            UiText("比较首次授权前与最近一次工具返回后的已记录内容",style=MaterialTheme.typography.bodySmall)
            if(latest==null || events.any { it.sequence>latest.sequence && it.event.kind==ExecutionKind.TOOL_STARTED })
                UiText("仍有操作未记录后续观察，文件当前状态需重新检查",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(comparison==null) UiText("尚无操作后的文件观察")
            else {
                if(!comparison.complete) UiText("扫描不完整，未观察到的文件不能视为未变更或已删除",style=MaterialTheme.typography.bodySmall)
                if(comparison.changes.isEmpty()) UiText("已观察范围内没有内容变化")
                comparison.changes.forEach { change ->
                    var detail by rememberSaveable(change.path) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { detail=!detail }.padding(vertical=4.dp).testTag("workspace_change_${change.path}")) {
                            Text(change.path,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            UiText(when(change.kind) {
                                WorkspaceChangeKind.CREATED->"新建文件";WorkspaceChangeKind.MODIFIED->"修改文件"
                                WorkspaceChangeKind.DELETED->"删除文件";WorkspaceChangeKind.UNKNOWN->"变化未知"
                            },style=MaterialTheme.typography.labelSmall)
                        }
                        if(detail) {
                            WorkspaceFilePreview("操作前预览",change.before)
                            WorkspaceFilePreview("操作后预览",change.after)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFilePreview(title: String,observation: FileObservation) {
    UiText(title,style=MaterialTheme.typography.labelSmall)
    when(observation.kind) {
        FileObservationKind.ABSENT->UiText("文件不存在",style=MaterialTheme.typography.bodySmall)
        FileObservationKind.UNAVAILABLE->UiText("文件内容未能读取",style=MaterialTheme.typography.bodySmall)
        FileObservationKind.OVERSIZED->UiText("文件超过读取上限",style=MaterialTheme.typography.bodySmall)
        FileObservationKind.BINARY->UiText("二进制文件不显示文本预览",style=MaterialTheme.typography.bodySmall)
        FileObservationKind.TEXT->SelectionContainer { Text(observation.excerpt ?: "",fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) }
    }
    observation.bytes?.let { Text("$it bytes",style=MaterialTheme.typography.labelSmall) }
    observation.sha256?.let { Text("SHA-256: $it",style=MaterialTheme.typography.labelSmall) }
    if(observation.excerptTruncated) UiText("记录片段已截断",style=MaterialTheme.typography.labelSmall)
}
