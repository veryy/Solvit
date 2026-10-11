package dev.solvit.core

import kotlinx.coroutines.flow.StateFlow

enum class ModelTask { CHAT, TOOL_FOLLOW_UP }
/** Credentials belong to ModelConfig, whose toString always redacts them. */
data class ModelProfile(val id: String, val name: String, val config: ModelConfig) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "模型配置 ID 无效" }
        require(name.isNotBlank() && name.length <= 80) { "模型配置名称不能为空且不超过 80 字" }
        config.validate()?.let { error(it) }
    }
}
data class ModelProfilesState(val profiles: List<ModelProfile>, val routes: Map<ModelTask,String>, val configured: Boolean = true) {
    fun validate() {
        require(profiles.size in 1..32) { "请保留 1 到 32 个模型配置" }
        profiles.forEach { it.validate() }
        require(profiles.map { it.id }.toSet().size == profiles.size) { "模型配置 ID 重复" }
        require(routes[ModelTask.CHAT] != null) { "请选择聊天模型" }
        require(routes.values.all { id -> profiles.any { it.id == id } }) { "任务绑定的模型配置不存在" }
    }
    fun selected(task: ModelTask): ModelProfile? = profiles.firstOrNull { it.id == (routes[task] ?: routes[ModelTask.CHAT]) }
}
interface ModelProfilesService {
    val state: StateFlow<ModelProfilesState>
    val loadError: StateFlow<String?>
    suspend fun save(profile: ModelProfile)
    suspend fun delete(id: String)
    suspend fun assign(task: ModelTask, id: String?)
    /** Explicit backup restoration; implementations validate and replace atomically. */
    suspend fun replace(state: ModelProfilesState)
    fun resolve(task: ModelTask, fallback: ModelConfig): ModelConfig
}
val ModelProfilesServiceKey = ServiceKey("model.profiles", ModelProfilesService::class)
