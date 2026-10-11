package dev.solvit.app

import dev.solvit.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native credential persistence provides a replaceable service; UI and Agent consume its contract. */
class SettingsModelProfiles(private val settings: SettingsStore) : ModelProfilesService {
    private val mutable = MutableStateFlow(ModelProfilesState(listOf(ModelProfile("default","默认配置",ModelConfig())),mapOf(ModelTask.CHAT to "default"),false))
    override val state = mutable.asStateFlow()
    private val failure = MutableStateFlow<String?>(null)
    override val loadError = failure.asStateFlow()
    private val lock = Mutex()
    suspend fun initialize() = lock.withLock {
        try { mutable.value = settings.loadProfiles(); failure.value = null }
        catch (e: SettingsDecryptionException) {
            mutable.value = ModelProfilesState(listOf(ModelProfile("default","默认配置",e.partial)),mapOf(ModelTask.CHAT to "default"),false)
            failure.value = "模型配置无法解密；已有数据未修改，请导入配置备份恢复。"
        } catch (e: Exception) { failure.value = "模型配置读取失败；已有数据未修改，请导入配置备份恢复。" }
    }
    private suspend fun change(transform: (ModelProfilesState) -> ModelProfilesState) = lock.withLock {
        check(failure.value == null) { failure.value.orEmpty() }
        val next = transform(mutable.value).let { it.copy(profiles=it.profiles.toList(),routes=it.routes.toMap()) }.also { it.validate() }
        withContext(NonCancellable) { settings.saveProfiles(next); mutable.value = next }
    }
    override suspend fun save(profile: ModelProfile) = change { previous ->
        profile.validate()
        if (!previous.configured) ModelProfilesState(listOf(profile), mapOf(ModelTask.CHAT to profile.id),true)
        else previous.copy(profiles=if (previous.profiles.any { it.id == profile.id }) previous.profiles.map { if (it.id == profile.id) profile else it } else previous.profiles + profile,configured=true)
    }
    override suspend fun delete(id: String) = change { previous ->
        require(previous.profiles.any { it.id == id })
        require(id !in previous.routes.values) { "此配置仍用于任务，请先切换任务模型" }
        previous.copy(profiles=previous.profiles.filterNot { it.id == id })
    }
    override suspend fun assign(task: ModelTask, id: String?) = change { previous ->
        require(id != null || task != ModelTask.CHAT) { "聊天任务必须选择模型" }
        previous.copy(routes=if (id == null) previous.routes - task else previous.routes + (task to id))
    }
    override suspend fun replace(state: ModelProfilesState) = lock.withLock {
        state.validate(); withContext(NonCancellable) { settings.saveProfiles(state); mutable.value = state; failure.value = null }
    }
    override fun resolve(task: ModelTask, fallback: ModelConfig): ModelConfig {
        check(failure.value == null) { failure.value.orEmpty() }
        val id = state.value.routes[task] ?: return fallback
        return state.value.profiles.firstOrNull { it.id == id }?.config ?: fallback
    }
}
class ModelProfilesPlugin(private val profiles: ModelProfilesService) : SolvitPlugin {
    override val id = "builtin.model.profiles"
    override val provides = setOf(ModelProfilesServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(ModelProfilesServiceKey, profiles) }
}
