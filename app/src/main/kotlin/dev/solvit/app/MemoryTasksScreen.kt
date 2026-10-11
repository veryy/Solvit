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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.solvit.chat.PostTurnWorker
import dev.solvit.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Human task controls do not execute plugin tools or implicitly enable automatic saving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryTasksScreen(worker: PostTurnWorker, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val workerState by worker.state.collectAsStateWithLifecycle()
    var jobs by remember { mutableStateOf(emptyList<PostTurnJob>()) }
    var busy by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var paged by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retiring by remember { mutableStateOf<PostTurnJob?>(null) }
    var retry by remember { mutableStateOf<PostTurnJob?>(null) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    suspend fun refresh(append: Boolean = false) {
        if (busy) return
        busy = true
        try {
            val cursor = if (append) jobs.lastOrNull()?.let { PostTurnJobCursor(it.updatedAt, it.id) } else null
            val page = worker.jobs(cursor, 20)
            jobs = if (append) (jobs + page).distinctBy { it.id } else page
            paged = append
            more = page.size == 20
            error = null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "无法读取记忆任务，请刷新重试。" }
        finally { busy = false }
    }
    LaunchedEffect(workerState) { if (!paged) refresh() }
    FullScreenPage(onDismissRequest = onDismiss) {
        Scaffold(topBar = { TopAppBar(title = { UiText("自动记忆任务") }, navigationIcon = {
            IconButton(onClick = onDismiss) { UiIcon(Icons.AutoMirrored.Filled.ArrowBack, "返回设置") }
        }, actions = { TextButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { UiText("刷新") } }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("memory_tasks"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { UiText("仅处理启用自动保存后完成的回复；重试可能产生额外模型费用。", style = MaterialTheme.typography.bodySmall) }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { message -> item { UiText(message, color = MaterialTheme.colorScheme.error) } }
                if (jobs.isEmpty() && !busy && error == null) item { UiText("暂无自动记忆任务") }
                items(jobs, key = { it.id }) { job ->
                    Card(Modifier.fillMaxWidth().testTag("memory_task_${job.id}")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            UiText(taskStatus(job.status), style = MaterialTheme.typography.titleSmall)
                            Text(job.turn.roleName ?: "Solvit", style = MaterialTheme.typography.bodyMedium)
                            Text(job.target.pluginId, style = MaterialTheme.typography.bodySmall)
                            job.failure?.let { UiText(taskFailure(it), color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { expanded = if (job.id in expanded) expanded - job.id else expanded + job.id }) {
                                    UiText(if (job.id in expanded) "收起详情" else "查看详情")
                                }
                                if (job.status == PostTurnJobStatus.FAILED && job.attempt < 3) {
                                    TextButton(onClick = { retry = job }, enabled = !busy) { UiText("重试") }
                                }
                                if (job.status != PostTurnJobStatus.RUNNING) {
                                    TextButton(onClick = { retiring = job }, enabled = !busy) { UiText("删除任务") }
                                }
                                if (workerState.activeJobId == job.id) {
                                    TextButton(onClick = worker::cancelCurrent) { UiText("停止") }
                                }
                            }
                            if (job.id in expanded) {
                                UiText("尝试次数：${job.attempt}/3")
                                UiText("模型：${job.turn.requestedModel}")
                                UiText("会话 ID：${job.turn.sessionId}")
                                UiText("来源消息：${job.turn.userMessageId}")
                                UiText("回复消息：${job.turn.assistantMessageId}")
                            }
                        }
                    }
                }
                if (more) item { TextButton(onClick = { scope.launch { refresh(append = true) } }, enabled = !busy) { UiText("加载更多") } }
            }
        }
    }
    retiring?.let { job ->
        AlertDialog(onDismissRequest = { retiring = null }, title = { UiText("删除任务") },
            text = { UiText("取消此任务的后续处理和重试，保留已保存记忆。") },
            dismissButton = { TextButton(onClick = { retiring = null }) { UiText("取消") } },
            confirmButton = { TextButton(onClick = {
                retiring = null
                scope.launch {
                    try {
                        val removed = worker.delete(job)
                        refresh()
                        if (!removed) error = "任务状态已变化，请刷新查看。"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "任务操作未完成，请刷新核对。" }
                }
            }) { UiText("确认删除") } })
    }
    retry?.let { job ->
        AlertDialog(onDismissRequest = { retry = null }, title = { UiText("重试自动记忆") },
            text = { UiText("将使用原模型配置重新尝试，可能产生额外费用。权限、角色和插件状态会重新检查。") },
            dismissButton = { TextButton(onClick = { retry = null }) { UiText("取消") } },
            confirmButton = { TextButton(onClick = {
                retry = null
                scope.launch {
                    try {
                        val accepted = worker.retry(job.id, job.attempt)
                        refresh()
                        if (!accepted) error = "任务状态已变化或已达重试上限，请刷新查看。"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "无法重试记忆任务，请稍后重试。" }
                }
            }) { UiText("确认重试") } })
    }
}

private fun taskStatus(status: PostTurnJobStatus) = when (status) {
    PostTurnJobStatus.WAITING -> "等待处理"
    PostTurnJobStatus.RUNNING -> "正在处理"
    PostTurnJobStatus.SUCCEEDED -> "处理完成"
    PostTurnJobStatus.FAILED -> "处理失败"
    PostTurnJobStatus.SKIPPED -> "已跳过"
}
private fun taskFailure(failure: PostTurnFailure) = when (failure) {
    PostTurnFailure.INTERRUPTED -> "处理已中断，需要手动重试。"
    PostTurnFailure.DEADLINE -> "模型处理超时。"
    PostTurnFailure.INPUT_TOO_LARGE -> "输入超过自动记忆预算。"
    PostTurnFailure.MODEL -> "模型请求失败。"
    PostTurnFailure.INVALID_OUTPUT -> "模型输出不符合记忆格式。"
    PostTurnFailure.STORAGE -> "记忆存储失败或达到容量限制。"
    PostTurnFailure.PERMISSION -> "插件授权已失效。"
    PostTurnFailure.PLUGIN_CHANGED -> "插件版本已变化。"
    PostTurnFailure.CONFIGURATION -> "原模型配置不可用。"
}
