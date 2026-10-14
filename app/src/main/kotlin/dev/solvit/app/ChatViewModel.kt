package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val chat: ChatService, private val settings: SettingsStore, private val roles: RoleService? = null,
    private val catalog: ModelCatalogService? = null, private val branches: ChatBranchService? = null, private val approvals: ToolPolicyService? = null, private val profiles: ModelProfilesService? = null,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }, private val displaySpeed: () -> Int = { 0 }, private val attachmentStore: ChatAttachmentStore? = null) : ViewModel() {
    suspend fun discoverModels(candidate: ModelConfig): List<AvailableModel> =
        (catalog ?: error("模型列表服务不可用")).models(candidate)
    val error = MutableStateFlow<String?>(null)
    val errorDetails = MutableStateFlow<ModelErrorDetails?>(null)
    private var configurationProblem: String? = null
    private fun configurationAvailable(): Boolean {
        val problem = configurationProblem ?: return true
        error.value = problem
        return false
    }
    fun dismissError() { error.value = null; errorDetails.value = null }
    private val selected = MutableStateFlow<String?>(null)
    val sessionId = selected.asStateFlow()
    val sessions = chat.sessions().catch { error.value = "读取会话失败" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messages = selected.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.messages(id) }
        .catch { error.value = "读取聊天记录失败" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val executionRuns = selected.flatMapLatest { id -> if(id==null) flowOf(emptyList()) else chat.executionRuns(id) }
        .catch { error.value="读取执行记录失败" }
        .stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    suspend fun executionEvents(run: ExecutionRun): List<RecordedExecutionEvent> {
        check(selected.value==run.sessionId)
        val events=chat.executionEvents(run.sessionId,run.id)
        check(selected.value==run.sessionId)
        return events
    }
    fun executionReviewAccess(run: ExecutionRun): PageWorkspaceAccess {
        check(selected.value == run.sessionId)
        return dev.solvit.market.ExecutionChangesPageBridge(chat, run) { selected.value }
    }
    val config = MutableStateFlow(ModelConfig())
    val ready = MutableStateFlow(false)
    val modelConfigured = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val attachmentDrafts = MutableStateFlow<Map<String, List<ChatAttachment>>>(emptyMap())
    val importingAttachment = MutableStateFlow(false)
    suspend fun importAttachment(session: String, name: String, mime: String, input: java.io.InputStream) {
        check(selected.value == session && ready.value && !importingAttachment.value) { "附件选择已失效" }
        val store = attachmentStore ?: error("附件服务未启用")
        val current = attachmentDrafts.value[session].orEmpty()
        check(current.size < 8) { "附件数量超过限制" }
        importingAttachment.value = true
        var imported: ChatAttachment? = null
        try {
            val reference = store.import(session, name, mime, input) { check(selected.value == session && ready.value) { "对话已改变" } }
            imported = reference
            check(current.sumOf { it.sizeBytes } + reference.sizeBytes <= ChatAttachment.MAX_ATTACHMENT_BYTES) { "附件总大小超过限制" }
            val bytes = store.read(session, reference)
            try { withContext(Dispatchers.IO) { validateChatAttachment(reference, bytes) } }
            finally { bytes.fill(0) }
            check(selected.value == session) { "对话已改变" }
            attachmentDrafts.value = attachmentDrafts.value + (session to (current + reference))
            imported = null
        } finally {
            imported?.let { withContext(NonCancellable) { store.remove(session, it) } }
            importingAttachment.value = false
        }
    }
    fun removeDraftAttachment(session: String, attachment: ChatAttachment) {
        if (importingAttachment.value || selected.value != session) return
        val current = attachmentDrafts.value[session].orEmpty()
        if (attachment !in current) return
        attachmentDrafts.value = attachmentDrafts.value + (session to current.filterNot { it.id == attachment.id })
        viewModelScope.launch {
            try { attachmentStore?.remove(session, attachment) }
            catch (_: Exception) { error.value = "附件清理失败" }
        }
    }
    fun clearSentAttachments(session: String) { attachmentDrafts.value = attachmentDrafts.value - session }
    suspend fun readAttachment(session: String, attachment: ChatAttachment): ByteArray {
        check(selected.value == session) { "对话已改变" }
        return (attachmentStore ?: error("附件服务未启用")).read(session, attachment) { check(selected.value == session) { "对话已改变" } }
    }
    val draft = MutableStateFlow("")
    val reasoning = MutableStateFlow("")
    val liveTranscript = MutableStateFlow<List<ModelMessage>>(emptyList())
    var turnBaseline: String? = null
        private set
    var sendingAttachments: List<ChatAttachment> = emptyList()
        private set
    var sendingText: String = ""
        private set
    val branching = MutableStateFlow(false)
    val branchError = MutableStateFlow<String?>(null)
    val branchInput = MutableStateFlow<Pair<String,String>?>(null)
    val saving = MutableStateFlow(false)
    val toolActivity = MutableStateFlow<List<ModelChunk.ToolActivity>>(emptyList())
    val toolApproval = MutableStateFlow<ToolApproval?>(null)
    val replyTiming = MutableStateFlow<ReplyTiming?>(null)
    data class ToolApproval(val descriptor: ToolDescriptor, val call: ToolCall, val role: AgentRole?, val decision: CompletableDeferred<Boolean>, val resolving: MutableStateFlow<Boolean> = MutableStateFlow(false))
    fun approveTool(allow: Boolean, remember: Boolean = false) {
        val pending = toolApproval.value ?: return
        if (pending.resolving.value || pending.decision.isCompleted) return
        pending.resolving.value = true
        if (!remember) { pending.decision.complete(allow); return }
        viewModelScope.launch {
            try {
                (approvals ?: error("工具授权服务不可用")).set(pending.descriptor, pending.role, ToolPolicy.ALLOW)
                if (toolApproval.value === pending) pending.decision.complete(true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = "免询问授权保存失败，请重试或允许这一次"; pending.resolving.value = false }
        }
    }
    data class QueuedMessage(val id: String, val sessionId: String, val text: String, val attachments: List<ChatAttachment> = emptyList())
    val queuedMessages = MutableStateFlow<List<QueuedMessage>>(emptyList())
    private val pausedChats = mutableSetOf<String>()
    val queuePaused = MutableStateFlow(false)
    private fun chooseSession(id: String?) {
        selected.value=id
        queuePaused.value=id!=null && id in pausedChats
    }
    private fun pauseQueue(id: String?, paused: Boolean) {
        if(id!=null) { if(paused)pausedChats.add(id) else pausedChats.remove(id) }
        queuePaused.value=selected.value?.let(pausedChats::contains)==true
    }
    val switching = MutableStateFlow(false)
    private var generation: Job? = null
    private suspend fun createRoleSession(source: String?): ChatSession {
        val session = chat.createSession()
        try { roles?.bindNewChat(session.id, source) }
        catch (e: Exception) { withContext(NonCancellable) { runCatching { chat.deleteSession(session.id) } }; throw e }
        return session
    }


    init {
        if (profiles != null) viewModelScope.launch {
            profiles.state.collect { state -> config.value = state.selected(ModelTask.CHAT)!!.config; modelConfigured.value = state.configured }
        }
        if (profiles != null) viewModelScope.launch { profiles.loadError.collect { configurationProblem = it; if (it != null) error.value = it } }
        viewModelScope.launch {
            try { if (profiles == null) { config.value = settings.load(); modelConfigured.value = settings.isConfigured() } }
            catch (e: SettingsDecryptionException) { config.value = e.partial; configurationProblem = "API Key 无法解密；模型地址和提示词已保留，请恢复备份或重新填写密钥"; error.value = configurationProblem }
            catch (e: Exception) { configurationProblem = "模型配置无法解密，请在设置中重新保存 API Key"; error.value = configurationProblem }
            try {
                chooseSession(chat.sessions().first().firstOrNull()?.id ?: createRoleSession(null).id)
                ready.value = true
            } catch (e: Exception) { error.value = "无法打开聊天存储，请重启应用" }
        }
    }
    fun select(id: String) { if (!busy.value && !switching.value && !branching.value) { chooseSession(id); draft.value = ""; dismissError() } }
    fun newChat() {
        if (busy.value || switching.value || branching.value) return
        viewModelScope.launch {
            try {
                val session = createRoleSession(selected.value)
                chooseSession(session.id); error.value = null
            }
            catch (e: Exception) { error.value = "创建对话失败" }
        }
    }
    fun fork(point: BranchPoint, editedText: String, onForked: () -> Unit) {
        val source = selected.value ?: return
        if (busy.value || switching.value || branching.value) return
        val service = branches ?: run { branchError.value = "当前存储不支持聊天分支"; return }
        branching.value = true; branchError.value = null
        viewModelScope.launch {
            try {
                val branch = service.fork(source, point)
                branchInput.value = branch.id to editedText
                chooseSession(branch.id)
                draft.value = ""; reasoning.value = ""; liveTranscript.value = emptyList(); toolActivity.value = emptyList()
                dismissError()
                onForked()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { branchError.value = "创建分支失败，请重试；原对话已保留" }
            finally { branching.value = false }
        }
    }
    fun delete(id: String) {
        if (busy.value || switching.value || branching.value) return
        viewModelScope.launch {
            try {
                chat.deleteSession(id)
                queuedMessages.value = queuedMessages.value.filterNot { it.sessionId == id }
                pausedChats.remove(id)
                if (selected.value == id) chooseSession(chat.sessions().first().firstOrNull()?.id ?: createRoleSession(null).id)
            } catch (e: Exception) { error.value = "删除对话失败" }
        }
    }
    private suspend fun persistConfiguration(value: ModelConfig) {
        if (profiles == null) settings.save(value)
        else {
            val current = profiles.state.value.selected(ModelTask.CHAT)!!
            profiles.save(current.copy(config=value))
        }
    }
    fun saveConfig(value: ModelConfig, onSaved: () -> Unit) {
        if (saving.value) return
        value.validate()?.let { error.value = it; return }
        saving.value = true
        viewModelScope.launch {
            try { persistConfiguration(value); config.value = value; configurationProblem = null; modelConfigured.value = true; error.value = null; onSaved() }
            catch (e: Exception) { error.value = "保存模型配置失败" }
            finally { saving.value = false }
        }
    }
    suspend fun restoreConfig(value: ModelConfig) {
        check(!busy.value && !branching.value && !saving.value) { "请等待当前操作结束" }
        value.validate()?.let { error(it) }
        saving.value = true
        try { persistConfiguration(value); config.value = value; configurationProblem = null; modelConfigured.value = true; error.value = null }
        finally { saving.value = false }
    }
    var retryingUserId: String? = null
        private set
    val supportsReplyRetry get() = chat.supportsReplyRetry
    fun retryReply(replyId: String): Boolean {
        if (!chat.supportsReplyRetry || busy.value || switching.value || branching.value || !ready.value || importingAttachment.value) return false
        val id = selected.value ?: return false
        val latest = messages.value.lastOrNull() ?: return false
        if (latest.id != replyId || latest.role != Role.ASSISTANT || latest.status == MessageStatus.COMPLETE) return false
        val user = messages.value.lastOrNull { it.role == Role.USER } ?: return false
        pauseQueue(id, true)
        return startTurn(id, user.content, user.attachments, retryReplyId = replyId, retryUserId = user.id)
    }
    fun send(text: String, attachments: List<ChatAttachment> = emptyList()): Boolean {
        val id = selected.value ?: return false
        if (branching.value || !ready.value || (text.isBlank() && attachments.isEmpty())) return false
        if (!configurationAvailable() || !validAttachments(attachments)) return false
        config.value.validate()?.let { error.value = it; return false }
        if (queuedMessages.value.size >= 32) { error.value = "等待队列已满，请先发送或移除消息"; return false }
        if (busy.value || switching.value || queuedMessages.value.any { it.sessionId == id }) {
            queuedMessages.value += QueuedMessage(UUID.randomUUID().toString(), id, text.trim(), attachments.toList())
            if (!busy.value && !switching.value && !queuePaused.value) drainQueue()
            return true
        }
        pauseQueue(id,false)
        return startTurn(id, text.trim(), attachments.toList())
    }
    fun removeQueued(itemId: String) { if (!switching.value) queuedMessages.value = queuedMessages.value.filterNot { it.id == itemId } }
    fun editQueued(itemId: String, text: String): Boolean {
        if (switching.value) return false
        val item = queuedMessages.value.firstOrNull { it.id == itemId && it.sessionId == selected.value } ?: return false
        if (text.isBlank() && item.attachments.isEmpty()) return false
        queuedMessages.value = queuedMessages.value.map { if (it.id == item.id) it.copy(text = text.trim()) else it }
        return true
    }
    fun resumeQueue() { if (!switching.value) { pauseQueue(selected.value,false); drainQueue() } }
    private fun drainQueue() {
        if (busy.value || switching.value || queuePaused.value) return
        val item = queuedMessages.value.firstOrNull { it.sessionId == selected.value } ?: return
        if (startTurn(item.sessionId, item.text, item.attachments)) queuedMessages.value = queuedMessages.value.filterNot { it.id == item.id }
        else pauseQueue(item.sessionId,true)
    }
    fun sendQueuedNow(itemId: String): Boolean {
        if (switching.value || branching.value) return false
        val item = queuedMessages.value.firstOrNull { it.id == itemId && it.sessionId == selected.value } ?: return false
        switching.value = true
        val previous = generation
        viewModelScope.launch {
            try {
                previous?.cancelAndJoin()
                if (selected.value != item.sessionId) return@launch
                if (startTurn(item.sessionId, item.text, item.attachments)) {
                    queuedMessages.value = queuedMessages.value.filterNot { it.id == item.id }
                    pauseQueue(item.sessionId,false)
                }
            } finally { switching.value = false }
        }
        return true
    }
    fun interruptAndSend(text: String, attachments: List<ChatAttachment> = emptyList()): Boolean {
        val id = selected.value ?: return false
        if (switching.value || branching.value || !ready.value || (text.isBlank() && attachments.isEmpty())) return false
        if (!configurationAvailable() || !validAttachments(attachments)) return false
        config.value.validate()?.let { error.value = it; return false }
        if (queuedMessages.value.size >= 32) { error.value = "等待队列已满，请先发送或移除消息"; return false }
        val item = QueuedMessage(UUID.randomUUID().toString(), id, text.trim(), attachments.toList())
        queuedMessages.value += item
        return sendQueuedNow(item.id)
    }
    private fun validAttachments(attachments: List<ChatAttachment>): Boolean {
        if (attachments.size > 8 || attachments.map { it.id }.distinct().size != attachments.size ||
            attachments.sumOf { it.sizeBytes } > ChatAttachment.MAX_ATTACHMENT_BYTES) {
            error.value = "附件数量或总大小超过限制"; return false
        }
        return true
    }
    private fun startTurn(id: String, text: String, attachments: List<ChatAttachment> = emptyList(), retryReplyId: String? = null, retryUserId: String? = null): Boolean {
        if (selected.value != id || busy.value || branching.value || !ready.value || (text.isBlank() && attachments.isEmpty())) return false
        if (!configurationAvailable()) return false
        dismissError()
        config.value.validate()?.let { error.value = it; return false }
        draft.value = ""
        reasoning.value = ""; liveTranscript.value = emptyList()
        turnBaseline = messages.value.lastOrNull { it.sessionId == id }?.id; sendingText = text.trim(); sendingAttachments = attachments.toList()
        toolActivity.value = emptyList()
        retryingUserId = retryUserId
        busy.value = true
        val snapshot = config.value
        val started = nowMillis()
        replyTiming.value = ReplyTiming(id, started)
        fun elapsed() = (nowMillis() - started).coerceAtLeast(0)
        generation = viewModelScope.launch {
            var completed = false
            try {
                val gate = object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor) = false
                    override suspend fun authorizeCallForRole(descriptor: ToolDescriptor, call: ToolCall, role: AgentRole?): Boolean {
                        val pending = ToolApproval(descriptor, call, role, CompletableDeferred())
                        toolApproval.value = pending
                        return try { pending.decision.await() }
                        finally { if (toolApproval.value === pending) toolApproval.value = null }
                    }
                }
                coroutineScope {
                val playback = ReplyPlayback(this,displaySpeed) { chunk -> when (chunk) {
                    is ModelChunk.Text -> {
                        draft.value += chunk.value
                        replyTiming.update { it?.copy(firstVisibleMs = it.firstVisibleMs ?: elapsed()) }
                    }
                    is ModelChunk.Reasoning -> reasoning.value += chunk.value
                    is ModelChunk.Transcript -> {
                        liveTranscript.value += chunk.message
                        if (chunk.message.role == Role.ASSISTANT) { draft.value = ""; reasoning.value = "" }
                    }
                    else -> error("Unexpected presentation event")
                } }
                try {
                val response = if (retryReplyId == null) chat.sendAttachments(id, text, attachments, snapshot, gate)
                    else chat.retryLatestReply(id, retryReplyId, snapshot, gate)
                response.collect { chunk -> when (chunk) {
                    is ModelChunk.NativeTranscript -> Unit
                    is ModelChunk.Text -> {
                        if (chunk.value.isNotEmpty()) replyTiming.update { it?.copy(firstTextMs = it.firstTextMs ?: elapsed(),
                            lastTextMs = elapsed(), receivedTextEvents = it.receivedTextEvents + 1,
                            receivedCharacters = it.receivedCharacters + chunk.value.codePointCount(0, chunk.value.length)) }
                        playback.enqueue(chunk)
                    }
                    is ModelChunk.Reasoning -> {
                        if (chunk.value.isNotEmpty()) replyTiming.update { it?.copy(firstReasoningMs = it.firstReasoningMs ?: elapsed()) }
                        playback.enqueue(chunk)
                    }
                    is ModelChunk.ToolCalls -> error("Agent 暴露了未处理工具调用")
                    is ModelChunk.Transcript -> playback.enqueue(chunk)
                    is ModelChunk.ToolActivity -> toolActivity.value =
                        toolActivity.value.filterNot { it.call.id == chunk.call.id } + chunk
                    ModelChunk.Done -> replyTiming.update { it?.copy(receivedEndMs = elapsed()) }
                } }
                playback.finish()
                replyTiming.update { it?.copy(displayEndMs = elapsed(), outcome = "complete") }
                completed = true
                } finally { playback.cancel() }
                }
            } catch (e: CancellationException) { replyTiming.update { it?.copy(outcome = "cancelled") }; throw e }
            catch (e: ModelException) { replyTiming.update { it?.copy(outcome = "failed") }; error.value = e.message; errorDetails.value = e.details }
            catch (e: IOException) { replyTiming.update { it?.copy(outcome = "failed") }; error.value = "网络连接失败，请检查 API 地址和网络" }
            catch (e: Exception) { replyTiming.update { it?.copy(outcome = "failed") }; error.value = "发送失败，请检查模型配置或缩短对话后重试" }
            finally {
                busy.value = false; retryingUserId = null; draft.value = ""; reasoning.value = ""; liveTranscript.value = emptyList(); toolApproval.value = null
                if (!completed) pauseQueue(id,true)
                if (completed && !switching.value && !queuePaused.value) {
                    val finished = currentCoroutineContext()[Job]
                    viewModelScope.launch { finished?.join(); drainQueue() }
                }
            }
        }
        return true
    }
    fun stop() { pauseQueue(selected.value,true); generation?.cancel() }
}
