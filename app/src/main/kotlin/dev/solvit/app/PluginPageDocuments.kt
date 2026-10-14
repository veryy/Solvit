package dev.solvit.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.solvit.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One page owner, one outstanding system picker; a late result cannot target a newer request. */
private class PluginPageDocuments(private val context: Context) : PageDocumentAccess {
    private data class Pending<T>(val authorize: suspend () -> Unit, val result: CompletableDeferred<T> = CompletableDeferred(),
        val active: AtomicBoolean = AtomicBoolean(true)) {
        @Volatile var selectedFile:PageBinaryDocument? = null
        suspend fun verify() { check(active.get()); authorize() }
    }
    private var selected: Pair<PageDocumentControl.Open, Pending<PageTextDocument?>>? = null
    private var selectedFile: Pending<PageBinaryDocument?>? = null
    private var output: Triple<PageDocumentControl.Save, String, Pending<Boolean>>? = null
    private var closed = false
    var launchOpen: (Array<String>) -> Unit = { error("文件选择器尚未准备好") }
    var launchSave: (Intent) -> Unit = { error("文件选择器尚未准备好") }

    override suspend fun open(control: PageDocumentControl.Open, authorize: suspend () -> Unit): PageTextDocument? {
        val pending = Pending<PageTextDocument?>(authorize)
        withContext(Dispatchers.Main.immediate) {
            check(!closed && selected == null && selectedFile == null && output == null) { "已有文件选择尚未返回" }
            pending.verify(); selected = control to pending
            try { launchOpen(control.mimeTypes.toTypedArray()) }
            catch (error: Throwable) { selected = null; throw error }
        }
        return try { pending.result.await() } finally { pending.active.set(false) }
    }
    override suspend fun selectFile(authorize: suspend () -> Unit): PageBinaryDocument? {
        val pending=Pending<PageBinaryDocument?>(authorize)
        withContext(Dispatchers.Main.immediate) {
            check(!closed && selected == null && selectedFile == null && output == null) { "已有文件选择尚未返回" }
            pending.verify();selectedFile=pending
            try { launchOpen(arrayOf("*/*")) }
            catch(error:Throwable) { selectedFile=null;throw error }
        }
        var delivered=false
        return try {
            val document=pending.result.await();delivered=true;pending.selectedFile=null;document
        } finally {
            pending.active.set(false)
            if(!delivered) { pending.result.cancel();pending.selectedFile?.close();pending.selectedFile=null }
        }
    }
    override suspend fun save(control: PageDocumentControl.Save, text: String, authorize: suspend () -> Unit): Boolean {
        require(text.toByteArray(Charsets.UTF_8).size <= control.maxBytes)
        val pending = Pending<Boolean>(authorize)
        withContext(Dispatchers.Main.immediate) {
            check(!closed && selected == null && selectedFile == null && output == null) { "已有文件选择尚未返回" }
            pending.verify(); output = Triple(control, text, pending)
            try { launchSave(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = control.mimeType; putExtra(Intent.EXTRA_TITLE, control.name)
            }) } catch (error: Throwable) { output = null; throw error }
        }
        return try { pending.result.await() } finally { pending.active.set(false) }
    }
    suspend fun completeOpen(uri: Uri?) {
        if (selectedFile != null) { completeFile(uri);return }
        val (control, pending) = selected ?: return
        // Keep the old request in place until its result arrives, even after cancellation.
        try {
            pending.verify()
            val document = if (uri == null) null else withContext(Dispatchers.IO) {
                pending.verify()
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val stream = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (stream.size() <= control.maxBytes) {
                        pending.verify()
                        val read = input.read(buffer, 0, minOf(buffer.size, control.maxBytes + 1 - stream.size()))
                        if (read == -1) break
                        stream.write(buffer, 0, read)
                    }
                    require(stream.size() <= control.maxBytes) { "选择的文本文件过大" }; stream.toByteArray()
                } ?: error("无法读取选择的文件")
                pending.verify()
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.take(256) else null
                }?.takeIf { it.isNotBlank() } ?: "document.txt"
                PageTextDocuments.decode(name, bytes)
            }
            pending.verify(); pending.result.complete(document)
        } catch (error: Throwable) { pending.result.completeExceptionally(error) }
        finally { selected = null }
    }
    private suspend fun completeFile(uri:Uri?) {
        val pending=selectedFile ?: return
        var document:PageBinaryDocument?=null
        var transferred=false
        try {
            pending.verify()
            if(uri!=null) document=withContext(Dispatchers.IO) {
                pending.verify()
                val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) cursor.getString(0)?.take(256) else null
                }?.takeIf { it.isNotBlank() } ?: "file"
                val mime=context.contentResolver.getType(uri)?.lowercase(java.util.Locale.ROOT) ?: "application/octet-stream"
                val bytes=context.contentResolver.openInputStream(uri)?.use { input ->
                    val stream=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                    try {
                        while(stream.size()<=MAX_WORKSPACE_IMPORT_BYTES) {
                            currentCoroutineContext().ensureActive();pending.verify()
                            val count=input.read(buffer,0,minOf(buffer.size,MAX_WORKSPACE_IMPORT_BYTES+1-stream.size()))
                            if(count<0) break
                            stream.write(buffer,0,count)
                        }
                        require(stream.size()<=MAX_WORKSPACE_IMPORT_BYTES) { "选择的文件超过20MiB导入限制" }
                        stream.toByteArray()
                    } finally { buffer.fill(0) }
                } ?: error("无法读取选择的文件")
                try { pending.verify();PageBinaryDocument(name,mime,bytes) } finally { bytes.fill(0) }
            }
            pending.selectedFile=document
            pending.verify();transferred=pending.result.complete(document)
        } catch(error:Throwable) { pending.result.completeExceptionally(error) }
        finally { if(!transferred) { document?.close();pending.selectedFile=null };selectedFile=null }
    }
    suspend fun completeSave(uri: Uri?) {
        val (control, text, pending) = output ?: return
        try {
            pending.verify()
            if (uri == null) pending.result.complete(false)
            else {
                withContext(Dispatchers.IO) {
                    pending.verify()
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= control.maxBytes)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        pending.verify(); stream.write(bytes)
                    } ?: error("无法保存选择的文件")
                }
                pending.verify(); pending.result.complete(true)
            }
        } catch (error: Throwable) { pending.result.completeExceptionally(error) }
        finally { output = null }
    }
    fun close() {
        closed = true
        selected?.second?.let { it.active.set(false); it.result.cancel() }
        selectedFile?.let { it.active.set(false);it.result.cancel();it.selectedFile?.close();it.selectedFile=null }
        output?.third?.let { it.active.set(false); it.result.cancel() }
        selected = null;selectedFile=null; output = null
    }
}

@Composable
internal fun rememberPluginPageDocuments(): PageDocumentAccess {
    val context = LocalContext.current
    val documents = remember(context) { PluginPageDocuments(context) }
    val scope = rememberCoroutineScope()
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        scope.launch { documents.completeOpen(uri) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch { documents.completeSave(if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.data else null) }
    }
    SideEffect { documents.launchOpen = { open.launch(it) }; documents.launchSave = { save.launch(it) } }
    DisposableEffect(documents) { onDispose { documents.close() } }
    return documents
}
