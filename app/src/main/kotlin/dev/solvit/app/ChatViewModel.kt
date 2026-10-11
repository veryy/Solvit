package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val chat: ChatService, private val settings: SettingsStore) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    private val selected = MutableStateFlow<String?>(null)
    val sessionId = selected.asStateFlow()
    val sessions = chat.sessions().catch { error.value = "读取会话失败" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val messages = selected.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.messages(id) }
        .catch { error.value = "读取聊天记录失败" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val config = MutableStateFlow(ModelConfig())
    val ready = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val draft = MutableStateFlow("")
    val saving = MutableStateFlow(false)
    private var generation: Job? = null

    init {
        viewModelScope.launch {
            try { config.value = settings.load() }
            catch (e: Exception) { error.value = "模型配置无法解密，请在设置中重新保存 API Key" }
            try {
                selected.value = chat.sessions().first().firstOrNull()?.id ?: chat.createSession().id
                ready.value = true
            } catch (e: Exception) { error.value = "无法打开聊天存储，请重启应用" }
        }
    }
    fun select(id: String) { if (!busy.value) { selected.value = id; draft.value = ""; error.value = null } }
    fun newChat() {
        if (busy.value) return
        viewModelScope.launch {
            try { selected.value = chat.createSession().id; error.value = null }
            catch (e: Exception) { error.value = "创建对话失败" }
        }
    }
    fun delete(id: String) {
        if (busy.value) return
        viewModelScope.launch {
            try {
                chat.deleteSession(id)
                if (selected.value == id) selected.value = chat.sessions().first().firstOrNull()?.id ?: chat.createSession().id
            } catch (e: Exception) { error.value = "删除对话失败" }
        }
    }
    fun saveConfig(value: ModelConfig, onSaved: () -> Unit) {
        if (saving.value) return
        value.validate()?.let { error.value = it; return }
        saving.value = true
        viewModelScope.launch {
            try { settings.save(value); config.value = value; error.value = null; onSaved() }
            catch (e: Exception) { error.value = "保存模型配置失败" }
            finally { saving.value = false }
        }
    }
    fun send(text: String): Boolean {
        val id = selected.value ?: return false
        if (busy.value || !ready.value || text.isBlank()) return false
        config.value.validate()?.let { error.value = it; return false }
        error.value = null
        draft.value = ""
        busy.value = true
        val snapshot = config.value
        generation = viewModelScope.launch {
            try {
                chat.send(id, text, snapshot).collect { chunk -> when (chunk) {
                    is ModelChunk.Text -> draft.value += chunk.value
                    ModelChunk.Done -> draft.value = ""
                } }
            } catch (e: CancellationException) { throw e }
            catch (e: ModelException) { error.value = e.message }
            catch (e: IOException) { error.value = "网络连接失败，请检查 API 地址和网络" }
            catch (e: Exception) { error.value = "发送失败，请检查模型配置或缩短对话后重试" }
            finally { busy.value = false; draft.value = "" }
        }
        return true
    }
    fun stop() { generation?.cancel() }
}
