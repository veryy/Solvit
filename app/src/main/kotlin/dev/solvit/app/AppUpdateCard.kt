package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun AppUpdateCard(state: UpdateUiState, onDismiss: () -> Unit, onRetry: () -> Unit) {
    if (state == UpdateUiState.Hidden) return
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (state) {
                    UpdateUiState.Checking -> "正在检查更新…"
                    UpdateUiState.Current -> "已是最新版本 · ${BuildConfig.VERSION_NAME}"
                    is UpdateUiState.Available -> "Solvit ${state.release.version} 可更新"
                    is UpdateUiState.Failed -> "更新检查未完成"
                    UpdateUiState.Hidden -> ""
                }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (state != UpdateUiState.Checking) IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭更新卡片") }
            }
            when (state) {
                is UpdateUiState.Available -> {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}，下载新版 APK 后安装即可更新。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { uriHandler.openUri(state.release.apkUrl) }) { Text("下载更新") }
                        TextButton(onClick = { uriHandler.openUri(state.release.pageUrl) }) { Text("版本说明") }
                    }
                }
                is UpdateUiState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                UpdateUiState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                else -> Unit
            }
        }
    }
}
