package dev.solvit.app

import android.graphics.BitmapFactory
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.core.ChatAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ChatAttachmentPicker(vm: ChatViewModel, session: String?, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val owner = target
        target = null
        if (owner != null && uris.isNotEmpty()) scope.launch {
            try {
                for (uri in uris) {
                    val metadata = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: "attachment"
                        val safeName = name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(255).ifBlank { "attachment" }
                        val mime = context.contentResolver.getType(uri)?.lowercase() ?: when (safeName.substringAfterLast('.').lowercase()) {
                            "py", "kt", "java", "js", "ts", "md", "txt", "csv", "log" -> "text/plain"
                            "json" -> "application/json"
                            else -> "application/octet-stream"
                        }
                        safeName to mime
                    }
                    val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) } ?: error("无法读取附件")
                    input.use { vm.importAttachment(owner, metadata.first, metadata.second, it) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { vm.error.value = failure.message?.take(160) ?: "附件导入失败" }
        }
    }
    IconButton(onClick = {
        target = session
        picker.launch(arrayOf("image/*", "text/*", "application/json", "application/xml", "application/javascript"))
    }, enabled = enabled && session != null, modifier = Modifier.testTag("chat_attach")) {
        UiIcon(Icons.Default.AttachFile, "添加图片或文件")
    }
}

@Composable
internal fun ChatAttachmentCards(vm: ChatViewModel, session: String, attachments: List<ChatAttachment>, removable: Boolean = false) {
    var preview by remember(session) { mutableStateOf<ChatAttachment?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            key(attachment.id) {
                OutlinedCard(onClick = { preview = attachment }, modifier = Modifier.widthIn(max = 240.dp).testTag("attachment_${attachment.id}")) {
                    Row(Modifier.padding(8.dp)) {
                        if (attachment.mimeType.startsWith("image/")) {
                            AttachmentImage(vm, session, attachment, Modifier.size(48.dp))
                        }
                        Column(Modifier.padding(horizontal = 6.dp)) {
                            Text(attachment.name, maxLines = 1)
                            Text("${attachment.sizeBytes / 1024} KiB", style = MaterialTheme.typography.labelSmall)
                        }
                        if (removable) IconButton(onClick = { vm.removeDraftAttachment(session, attachment) }, modifier = Modifier.size(32.dp)) {
                            UiIcon(Icons.Default.Close, "移除附件")
                        }
                    }
                }
            }
        }
    }
    preview?.let { attachment ->
        FullScreenPage(onDismissRequest = { preview = null }) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(attachment.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { preview = null }) { UiText("关闭") }
                }
                if (attachment.mimeType.startsWith("image/")) AttachmentImage(vm, session, attachment, Modifier.fillMaxSize())
                else AttachmentText(vm, session, attachment)
            }
        }
    }
}

@Composable
private fun AttachmentImage(vm: ChatViewModel, session: String, attachment: ChatAttachment, modifier: Modifier) {
    var bitmap by remember(session, attachment.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(session, attachment.id) { mutableStateOf(false) }
    LaunchedEffect(session, attachment.id) {
        try {
            val bytes = vm.readAttachment(session, attachment)
            try {
                bitmap = withContext(Dispatchers.IO) {
                    validateChatAttachment(attachment, bytes)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                        ?: error("图片解码失败")
                }
            } finally { bytes.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val image = bitmap
    if (image != null) Image(image, contentDescription = attachment.name, modifier = modifier, contentScale = ContentScale.Fit)
    else if (failed) UiText("附件预览失败", modifier = modifier)
    else Icon(Icons.Default.Image, contentDescription = attachment.name, modifier = modifier)
}

@Composable
private fun AttachmentText(vm: ChatViewModel, session: String, attachment: ChatAttachment) {
    var text by remember(attachment.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, attachment.id) {
        try {
            val bytes = vm.readAttachment(session, attachment)
            try { text = withContext(Dispatchers.IO) { validateChatAttachment(attachment, bytes); bytes.toString(Charsets.UTF_8) } }
            finally { bytes.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { text = "附件预览失败" }
    }
    SelectionContainer { Text(text ?: "正在读取附件…", modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) }
}
