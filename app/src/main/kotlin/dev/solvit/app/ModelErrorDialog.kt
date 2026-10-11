package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.solvit.core.ModelErrorDetails

@Composable
fun ModelErrorDialog(details: ModelErrorDetails, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = details.display()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("返回详细信息") },
        text = { LazyColumn(Modifier.heightIn(max = 480.dp)) {
            item { SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) } }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("复制详情") } })
}
