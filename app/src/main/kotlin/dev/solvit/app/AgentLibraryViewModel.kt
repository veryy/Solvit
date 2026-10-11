package dev.solvit.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.ByteArrayOutputStream

class AgentLibraryViewModel(
    private val roleService: RoleService,
    private val library: SkillLibraryService,
    marketplace: MarketplaceService,
) : ViewModel() {
    val applied = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val roles = roleService.roles().catch { error.value = "读取角色失败，已有角色数据未被重置" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val installed = marketplace.installed
    val activeRole = MutableStateFlow<AgentRole?>(null)
    val missingRole = MutableStateFlow(false)
    val skills = MutableStateFlow<List<SkillSummary>>(emptyList())
    val content = MutableStateFlow<SkillContent?>(null)
    val pendingImport = MutableStateFlow<SkillBundle?>(null)
    private var sessionId: String? = null

    init {
        viewModelScope.launch { roles.collect { refreshSelection() } }
        viewModelScope.launch {
            installed.map { list -> list.any { it.id == "dev.solvit.skills" && it.enabled } }.distinctUntilChanged().collect { enabled ->
                if (enabled) refreshSkills() else { skills.value = emptyList(); content.value = null }
            }
        }
    }
    fun setSession(id: String?) {
        sessionId = id
        applied.value = null
        activeRole.value = null; missingRole.value = false
        viewModelScope.launch { refreshSelection() }
    }
    private suspend fun refreshSelection() {
        val id = sessionId ?: return
        try {
            val role = roleService.selected(id)
            if (sessionId == id) { activeRole.value = role; missingRole.value = false }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (sessionId == id) { activeRole.value = null; missingRole.value = true; error.value = "此对话的角色无法读取，请重新选择角色" }
        }
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "角色或技能操作失败" }
            finally { busy.value = false }
        }
    }
    fun selectRole(roleId: String?) = operation {
        val id = sessionId ?: error("请先创建对话")
        roleService.select(id, roleId); refreshSelection()
        applied.value = "已应用${activeRole.value?.name ?: "默认角色"}：从下一条消息生效，新对话会继承。已有消息保留。"
    }
    fun saveRole(role: AgentRole, onSaved: () -> Unit) = operation {
        roleService.save(role); refreshSelection(); onSaved()
    }
    fun deleteRole(id: String) = operation { roleService.delete(id); refreshSelection() }
    fun refreshSkills() = operation { skills.value = library.list() }
    fun loadSkill(name: String) = operation { content.value = library.load(name) }
    fun prepareImport(read: suspend () -> ByteArray) = operation {
        val bytes = read()
        pendingImport.value = withContext(Dispatchers.IO) { library.preview(bytes) }
    }
    fun confirmImport(replace: Boolean) = operation {
        val bundle = pendingImport.value ?: return@operation
        library.importSkill(bundle, replace); pendingImport.value = null; skills.value = library.list()
    }
    fun removeSkill(name: String) = operation { library.remove(name); skills.value = library.list(); content.value = null }
    fun exportSkill(name: String, onReady: (String) -> Unit) = operation { onReady(library.export(name)) }
    fun writeExport(write: suspend () -> Unit) = operation { write() }

    companion object {
        fun readImport(stream: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count == -1) return output.toByteArray()
                require(output.size() + count <= 512 * 1024) { "技能导入文件超过 512 KiB" }
                output.write(buffer, 0, count)
            }
        }
    }
}
