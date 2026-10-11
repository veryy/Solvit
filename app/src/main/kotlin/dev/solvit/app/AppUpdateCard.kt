package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.solvit.core.AppRelease

@Composable
fun AppUpdateCard(state: UpdateUiState, download: ApkDownloadState, onDismiss: () -> Unit,
    onRetry: () -> Unit, onDownload: (AppRelease) -> Unit, onCancel: () -> Unit, onInstall: () -> Unit) {
    if (state == UpdateUiState.Hidden && download == ApkDownloadState.Idle) return
    if (state == UpdateUiState.Checking && download == ApkDownloadState.Idle) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        return
    }
    var notesOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (state) {
                    UpdateUiState.Checking -> "正在检查更新…"
                    UpdateUiState.Current -> "已是最新版本 · ${BuildConfig.VERSION_NAME}"
                    is UpdateUiState.Available -> "Solvit ${state.release.version} 可更新"
                    is UpdateUiState.Failed -> "更新检查未完成"
                    UpdateUiState.Hidden -> "应用更新"
                }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (download == ApkDownloadState.Idle && state != UpdateUiState.Checking)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭更新卡片") }
            }
            if (state is UpdateUiState.Available) {
                Text("当前版本 ${BuildConfig.VERSION_NAME} · 在应用内下载并安装", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { notesOpen = true }) { Text("查看版本说明") }
            }
            when (download) {
                is ApkDownloadState.Downloading -> {
                    val progress = if (download.total > 0) (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) else null
                    Text((if (download.waiting) "等待网络 · " else "下载中 · ") +
                        (if (progress == null) "${download.bytes / 1024} KiB" else "${(progress * 100).toInt()}% · ${download.bytes / 1024} / ${download.total / 1024} KiB"))
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = onCancel) { Text("取消下载") }
                }
                ApkDownloadState.Verifying -> { Text("正在校验安装包…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is ApkDownloadState.Ready -> {
                    Text("下载和校验完成。若系统要求允许安装应用，授权后回来点安装。", style = MaterialTheme.typography.bodySmall)
                    Row { Button(onClick = onInstall) { Text("安装更新") }; TextButton(onClick = onCancel) { Text("删除下载") } }
                }
                is ApkDownloadState.Failed -> {
                    Text(download.message, color = MaterialTheme.colorScheme.error)
                    Row {
                        if (state is UpdateUiState.Available) TextButton(onClick = { onDownload(state.release) }) { Text("重新下载") }
                        TextButton(onClick = onCancel) { Text("清除任务") }
                    }
                }
                ApkDownloadState.Idle -> when (state) {
                    is UpdateUiState.Available -> Button(onClick = { onDownload(state.release) }) { Text("下载更新") }
                    is UpdateUiState.Failed -> { Text(state.message); TextButton(onClick = onRetry) { Text("重试") } }
                    UpdateUiState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> Unit
                }
            }
        }
    }
    if (notesOpen && state is UpdateUiState.Available) AlertDialog(onDismissRequest = { notesOpen = false },
        title = { Text("Solvit ${state.release.version} · 版本说明") },
        text = { SelectionContainer { Text(state.release.notes.ifBlank { "此版本没有附带更新说明。" },
            modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { notesOpen = false }) { Text("关闭") } })
}
