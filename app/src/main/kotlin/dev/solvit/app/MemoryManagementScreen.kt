package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.market.BuiltinMemoryManager
import dev.solvit.chat.PostTurnWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryManagementScreen(manager: BuiltinMemoryManager, worker: PostTurnWorker? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf(emptyList<JsonObject>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<JsonObject?>(null) }
    var cleanupOpen by remember { mutableStateOf(false) }
    var receipts by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    suspend fun reload() {
        val snapshot = manager.snapshot()
        facts = snapshot.getValue("facts").jsonArray.map { it.jsonObject }
        receipts = snapshot["completedSources"]?.jsonArray?.size ?: 0
    }
    fun act(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { operation(); reload(); error = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    LaunchedEffect(manager) {
        busy = true
        try { reload(); error = false }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = true }
        finally { busy = false }
    }
    FullScreenPage(onDismissRequest = onDismiss) {
        Scaffold(topBar = { TopAppBar(title = { UiText("记忆管理") }, navigationIcon = {
            IconButton(onClick = onDismiss) { UiIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
        }, actions = { TextButton(onClick = { act {} }, enabled = !busy) { UiText("刷新") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("memory_facts"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { UiText("查看所有角色的已保存事实；停用插件后仍可修改或删除。") }
                item {
                    UiText("自动保存记录：$receipts/512", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { cleanupOpen = true }, enabled = !busy && worker != null, modifier = Modifier.testTag("memory_cleanup")) {
                        UiText("清理已完成任务")
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (error) item { UiText("记忆操作未完成，请刷新核对数据。", color = MaterialTheme.colorScheme.error) }
                if (facts.isEmpty() && !busy && !error) item { UiText("暂无已保存记忆") }
                items(facts, key = { it.getValue("id").jsonPrimitive.content }) { fact ->
                    val id = fact.getValue("id").jsonPrimitive.content
                    Card(Modifier.fillMaxWidth().testTag("memory_fact_$id")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(fact.getValue("text").jsonPrimitive.content)
                            Text(fact.getValue("category").jsonPrimitive.content, style = MaterialTheme.typography.labelSmall)
                            fact["source"]?.let { source ->
                                val origin = source.jsonObject
                                UiText("来自对话中的用户原文", style = MaterialTheme.typography.labelMedium)
                                Text(origin.getValue("quote").jsonPrimitive.content, style = MaterialTheme.typography.bodySmall)
                                origin["roleName"]?.jsonPrimitive?.contentOrNull?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                UiText("来源消息：${origin.getValue("userMessageId").jsonPrimitive.content}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (fact["modifiedByUser"]?.jsonPrimitive?.booleanOrNull == true) UiText("已人工修改", style = MaterialTheme.typography.labelSmall)
                            Row {
                                TextButton(onClick = { editing = fact }, enabled = !busy) { UiText("修改") }
                                TextButton(onClick = { deleting = fact }, enabled = !busy) { UiText("删除") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (cleanupOpen) AlertDialog(onDismissRequest = { cleanupOpen = false }, title = { UiText("清理已完成任务") },
        text = { UiText("释放自动保存容量，保留已保存记忆和可重试任务。") },
        dismissButton = { TextButton(onClick = { cleanupOpen = false }) { UiText("取消") } },
        confirmButton = { TextButton(onClick = {
            cleanupOpen = false
            act {
                val consumer = checkNotNull(worker)
                withTimeout(30_000) {
                    while (consumer.cleanupFinished("dev.solvit.memory") > 0) yield()
                    manager.pruneRetiredSources { consumer.sourceRetired("dev.solvit.memory", it) }
                }
            }
        }) { UiText("确认清理") } })
    editing?.let { fact ->
        var text by remember(fact) { mutableStateOf(fact.getValue("text").jsonPrimitive.content) }
        AlertDialog(onDismissRequest = { editing = null }, title = { UiText("修改记忆") }, text = {
            OutlinedTextField(text, { text = it }, Modifier.testTag("memory_edit_text"), label = { UiText("记忆内容") }, minLines = 3, maxLines = 8)
        }, dismissButton = { TextButton(onClick = { editing = null }) { UiText("取消") } }, confirmButton = {
            TextButton(enabled = text.isNotBlank() && text.length <= 2000 && !busy, onClick = {
                editing = null
                act { manager.save(text, fact.getValue("category").jsonPrimitive.content,
                    fact.getValue("tags").jsonArray.map { it.jsonPrimitive.content }, fact.getValue("id").jsonPrimitive.content) }
            }) { UiText("保存") }
        })
    }
    deleting?.let { fact ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { UiText("删除记忆") }, text = {
            UiText("删除此事实。保留来源去重记录，避免任务重试重新生成。")
        }, dismissButton = { TextButton(onClick = { deleting = null }) { UiText("取消") } }, confirmButton = {
            TextButton(onClick = { deleting = null; act { manager.delete(fact.getValue("id").jsonPrimitive.content) } }) { UiText("确认删除") }
        })
    }
}
