package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WorkspaceViewModel(val service: WorkspaceService, private val plugins: PluginDraftService, val terminals: TerminalService) : ViewModel() {
    val state = service.state
    val jobs = service.jobs
    val terminalSessions = terminals.sessions
    val workspaceId = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val fileText = MutableStateFlow("")
    val location = MutableStateFlow("")
    val directory = MutableStateFlow("")
    val files = MutableStateFlow<List<WorkspaceFile>>(emptyList())
    val drafts = plugins.drafts
    val preview = MutableStateFlow<Pair<PluginDraft, String>?>(null)
    fun review(draft: PluginDraft) = operation { preview.value = draft to plugins.payload(draft.id) }
    fun install(draft: PluginDraft) = operation { plugins.install(draft.id, draft.sha256); preview.value = null }
    private var chatId: String? = null
    private var selection: Job? = null
    fun selectChat(id: String?) {
        if (id == chatId) return
        selection?.cancel(); chatId = id; workspaceId.value = null; fileText.value = ""; error.value = null
        if (id != null) selection = viewModelScope.launch {
            try { val value = service.forChat(id); if (chatId == id) { workspaceId.value = value.id; loadDirectory(value.id, "") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = "无法打开工作区" }
        }
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "操作失败" }
            finally { busy.value = false }
        }
    }
    fun bind(id: String) {
        val chat = chatId ?: return
        operation { service.bind(chat, id); if (chatId == chat) { workspaceId.value = id; fileText.value = ""; loadDirectory(id, "") } }
    }
    fun create(name: String) {
        val chat = chatId ?: return
        operation { val value = service.create(name); service.bind(chat, value.id); if (chatId == chat) { workspaceId.value = value.id; fileText.value = ""; loadDirectory(value.id, "") } }
    }
    fun createShell() {
        val id = workspaceId.value ?: return
        operation { service.createShell(id, "Shell ${state.value.shells.count { it.workspaceId == id } + 1}") }
    }
    fun run(shellId: String, command: String) {
        val id = workspaceId.value ?: return
        operation { service.run(id, shellId, command) }
    }
    fun stop(job: ShellJob) = operation { service.cancel(job.workspaceId ?: return@operation, job.id) }
    fun send(shellId: String, terminalId: String?, text: String) {
        val workspace = workspaceId.value ?: return
        operation {
            val session = terminalId?.let { terminals.status(workspace, it) }?.takeIf { it.active }
                ?: terminals.open(workspace, shellId)
            terminals.input(workspace, session.id, text + "\n")
        }
    }
    fun interrupt(session: TerminalSession) = operation { terminals.interrupt(session.workspaceId, session.id) }
    fun closeTerminal(session: TerminalSession) = operation { terminals.close(session.workspaceId, session.id) }
    fun read(path: String) {
        val id = workspaceId.value ?: return
        operation { val text = service.read(id, path); if (workspaceId.value == id) fileText.value = text }
    }
    private suspend fun loadDirectory(id: String, path: String) {
        val absolute = service.location(id)
        val entries = service.list(id, path)
        if (workspaceId.value == id) { location.value = absolute; directory.value = path; files.value = entries }
    }
    fun browse(path: String) {
        val id = workspaceId.value ?: return
        operation { loadDirectory(id, path) }
    }
    fun write(path: String) {
        val id = workspaceId.value ?: return
        val text = fileText.value
        operation { service.write(id, path, text); loadDirectory(id, directory.value) }
    }
}
