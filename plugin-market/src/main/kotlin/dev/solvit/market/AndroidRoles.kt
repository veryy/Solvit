package dev.solvit.market

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Replaceable native persistence; roles do not carry model or plugin credentials. */
class AndroidRoleService(context: Context) : RoleService {
    private val prefs = context.getSharedPreferences("agent_roles", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val revision = MutableStateFlow(0L)
    private fun read(): List<AgentRole> = prefs.getString("roles", null)?.let { text ->
        Json.parseToJsonElement(text).jsonArray.map { item ->
            val o = item.jsonObject
            AgentRole(o.getValue("id").jsonPrimitive.content, o.getValue("name").jsonPrimitive.content,
                o.getValue("prompt").jsonPrimitive.content,
                o.getValue("plugins").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                o.getValue("skills").jsonArray.map { it.jsonPrimitive.content }.toSet()).also { it.validate() }
        }.also { list -> check(list.size <= 64 && list.map { it.id }.toSet().size == list.size) }
    }.orEmpty()
    private fun write(roles: List<AgentRole>) {
        require(roles.size <= 64) { "角色数量已达上限" }
        val json = JsonArray(roles.map { role -> buildJsonObject {
            put("id", role.id); put("name", role.name); put("prompt", role.prompt)
            put("plugins", JsonArray(role.pluginIds.sorted().map(::JsonPrimitive)))
            put("skills", JsonArray(role.skillIds.sorted().map(::JsonPrimitive)))
        } }).toString()
        check(prefs.edit().putString("roles", json).commit()) { "角色保存失败" }
        revision.value++
    }
    override fun roles(): Flow<List<AgentRole>> = revision.map { withContext(Dispatchers.IO) { mutex.withLock { read() } } }.distinctUntilChanged()
    override suspend fun save(role: AgentRole) = withContext(Dispatchers.IO) {
        role.validate()
        mutex.withLock { write(read().filterNot { it.id == role.id } + role.copy(pluginIds = role.pluginIds.toSet(), skillIds = role.skillIds.toSet())) }
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        // Keep stale selections blocked; deleting a restricted role must never
        // silently switch its conversations to the unrestricted default.
        mutex.withLock { write(read().filterNot { it.id == id }) }
    }
    private fun resolve(roleId: String?): AgentRole? = roleId?.let { id ->
        read().firstOrNull { it.id == id } ?: error("已选择的角色已删除，请重新选择角色")
    }
    override suspend fun selected(sessionId: String): AgentRole? = withContext(Dispatchers.IO) {
        mutex.withLock { resolve(prefs.getString("session.$sessionId", null)) }
    }
    override suspend fun forNewChat(sourceSessionId: String?): AgentRole? = withContext(Dispatchers.IO) {
        mutex.withLock {
            resolve(if (prefs.getBoolean("new_chat_default_set", false)) prefs.getString("new_chat_default", null)
                else sourceSessionId?.let { prefs.getString("session.$it", null) })
        }
    }
    override suspend fun bindNewChat(sessionId: String, sourceSessionId: String?): Unit = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        mutex.withLock {
            val roleId=if(prefs.getBoolean("new_chat_default_set",false))prefs.getString("new_chat_default",null)
                else sourceSessionId?.let { prefs.getString("session.$it",null) }
            // Preserve stale identities so the chat can open for explicit reselection,
            // while selected() continues to reject it before any model/tool request.
            check(prefs.edit().apply {
                if(roleId==null)remove("session.$sessionId") else putString("session.$sessionId",roleId)
            }.commit()) { "角色选择保存失败" }
            revision.value++
            Unit
        }
    }
    override suspend fun select(sessionId: String, roleId: String?) = choose(sessionId, roleId, false)
    override suspend fun selectForNewChats(sessionId: String, roleId: String?) = choose(sessionId, roleId, true)
    private suspend fun choose(sessionId: String, roleId: String?, saveDefault: Boolean): Unit = withContext(Dispatchers.IO) {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        mutex.withLock {
            require(roleId == null || read().any { it.id == roleId }) { "角色不存在" }
            check(prefs.edit().apply {
                if (roleId == null) remove("session.$sessionId") else putString("session.$sessionId", roleId)
                if (saveDefault) {
                    putBoolean("new_chat_default_set", true)
                    if (roleId == null) remove("new_chat_default") else putString("new_chat_default", roleId)
                }
            }.commit()) { "角色选择保存失败" }
            revision.value++
            Unit
        }
    }

}

class RolesPlugin(private val androidContext: Context) : SolvitPlugin {
    override val id = "builtin.roles"
    override val provides = setOf(RoleServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(RoleServiceKey, AndroidRoleService(androidContext)) }
}
