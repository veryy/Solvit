package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val chat: ChatService, private val settings: SettingsStore, private val roles: RoleService? = null,
    private val catalog: ModelCatalogService? = null, private val branches: ChatBranchService? = null, private val approvals: ToolPolicyService? = null, private val profiles: ModelProfilesService? = null,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) : ViewModel() {
    suspend fun discoverModels(candidate: ModelConfig): List<AvailableModel> =
        (catalog ?: error("模型列表服务不可用")).models(candidate)
    val error = MutableStateFlow<String?>(null)
    val errorDetails = MutableStateFlow<ModelErrorDetails?>(null)
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
    val config = MutableStateFlow(ModelConfig())
    val ready = MutableStateFlow(false)
    val modelConfigured = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val draft = MutableStateFlow("")
    val reasoning = MutableStateFlow("")
    val liveTranscript = MutableStateFlow<List<ModelMessage>>(emptyList())
    var turnBaseline: String? = null
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
    data class ToolApproval(val descriptor: ToolDescriptor, val call: ToolCall, val role: AgentRole?, val decision: CompletableDeferred<Boolean>)
    fun approveTool(allow: Boolean, remember: Boolean = false) {
        val pending = toolApproval.value ?: return
        if (!remember) { pending.decision.complete(allow); return }
        viewModelScope.launch {
            try {
                (approvals ?: error("工具授权服务不可用")).set(pending.descriptor, pending.role, ToolPolicy.ALLOW)
                if (toolApproval.value === pending) pending.decision.complete(true)
            } catch (e: Exception) { error.value = "免询问授权保存失败，请重试或允许这一次" }
        }
    }
    private var generation: Job? = null

    init {
        if (profiles != null) viewModelScope.launch {
            profiles.state.collect { state -> config.value = state.selected(ModelTask.CHAT)!!.config; modelConfigured.value = state.configured }
        }
        if (profiles != null) viewModelScope.launch { profiles.loadError.collect { if (it != null) error.value = it } }
        viewModelScope.launch {
            try { if (profiles == null) config.value = settings.load() }
            catch (e: SettingsDecryptionException) { config.value = e.partial; error.value = "API Key 无法解密；模型地址和提示词已保留，请恢复备份或重新填写密钥" }
            catch (e: Exception) { error.value = "模型配置无法解密，请在设置中重新保存 API Key" }
            if (profiles == null) modelConfigured.value = settings.isConfigured()
            try {
                selected.value = chat.sessions().first().firstOrNull()?.id ?: chat.createSession().id
                ready.value = true
            } catch (e: Exception) { error.value = "无法打开聊天存储，请重启应用" }
        }
    }
    fun select(id: String) { if (!busy.value && !branching.value) { selected.value = id; draft.value = ""; dismissError() } }
    fun newChat() {
        if (busy.value || branching.value) return
        viewModelScope.launch {
            try {
                val role = selected.value?.let { roles?.selected(it) }
                val session = chat.createSession()
                if (role != null) roles?.select(session.id, role.id)
                selected.value = session.id; error.value = null
            }
            catch (e: Exception) { error.value = "创建对话失败" }
        }
    }
    fun fork(point: BranchPoint, editedText: String, onForked: () -> Unit) {
        val source = selected.value ?: return
        if (busy.value || branching.value) return
        val service = branches ?: run { branchError.value = "当前存储不支持聊天分支"; return }
        branching.value = true; branchError.value = null
        viewModelScope.launch {
            try {
                val branch = service.fork(source, point)
                branchInput.value = branch.id to editedText
                selected.value = branch.id
                draft.value = ""; reasoning.value = ""; liveTranscript.value = emptyList(); toolActivity.value = emptyList()
                dismissError()
                onForked()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { branchError.value = "创建分支失败，请重试；原对话已保留" }
            finally { branching.value = false }
        }
    }
    fun delete(id: String) {
        if (busy.value || branching.value) return
        viewModelScope.launch {
            try {
                chat.deleteSession(id)
                if (selected.value == id) selected.value = chat.sessions().first().firstOrNull()?.id ?: chat.createSession().id
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
            try { persistConfiguration(value); config.value = value; modelConfigured.value = true; error.value = null; onSaved() }
            catch (e: Exception) { error.value = "保存模型配置失败" }
            finally { saving.value = false }
        }
    }
    suspend fun restoreConfig(value: ModelConfig) {
        check(!busy.value && !branching.value && !saving.value) { "请等待当前操作结束" }
        value.validate()?.let { error(it) }
        saving.value = true
        try { persistConfiguration(value); config.value = value; modelConfigured.value = true; error.value = null }
        finally { saving.value = false }
    }
    // [DEBUG-role-send] Temporary callback-boundary probe; no user text is recorded.
    internal var sendAttemptCount = 0
        private set
    fun send(text: String): Boolean {
        sendAttemptCount++
        val id = selected.value ?: return false
        if (busy.value || branching.value || !ready.value || text.isBlank()) return false
        dismissError()
        config.value.validate()?.let { error.value = it; return false }
        draft.value = ""
        reasoning.value = ""; liveTranscript.value = emptyList()
        turnBaseline = messages.value.lastOrNull { it.sessionId == id }?.id; sendingText = text.trim()
        toolActivity.value = emptyList()
        busy.value = true
        val snapshot = config.value
        val started = nowMillis()
        replyTiming.value = ReplyTiming(id, started)
        fun elapsed() = (nowMillis() - started).coerceAtLeast(0)
        generation = viewModelScope.launch {
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
                val playback = ReplyPlayback(this) { chunk -> when (chunk) {
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
                chat.send(id, text, snapshot, gate).collect { chunk -> when (chunk) {
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
                } finally { playback.cancel() }
                }
            } catch (e: CancellationException) { replyTiming.update { it?.copy(outcome = "cancelled") }; throw e }
            catch (e: ModelException) { replyTiming.update { it?.copy(outcome = "failed") }; error.value = e.message; errorDetails.value = e.details }
            catch (e: IOException) { replyTiming.update { it?.copy(outcome = "failed") }; error.value = "网络连接失败，请检查 API 地址和网络" }
            catch (e: Exception) { replyTiming.update { it?.copy(outcome = "failed") }; error.value = "发送失败，请检查模型配置或缩短对话后重试" }
            finally { busy.value = false; draft.value = ""; reasoning.value = ""; liveTranscript.value = emptyList(); toolApproval.value = null }
        }
        return true
    }
    fun stop() { generation?.cancel() }
}
