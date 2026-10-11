package dev.solvit.app

import android.content.Context
import dev.solvit.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Persistent grants bind to tool code, permissions, execution mode and role contents. */
class ToolApprovalStore(context: Context) : ToolPolicyService {
    private val prefs = context.getSharedPreferences("tool_approvals", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.all.mapNotNull { (key, value) ->
        runCatching { key to ToolPolicy.valueOf(value as String) }.getOrNull()
    }.toMap())
    override val policies = mutable.asStateFlow()
    override fun policy(tool: ToolDescriptor, role: AgentRole?) = policies.value[scopedToolPolicyKey(tool, role)] ?: ToolPolicy.ASK
    override fun policy(tool: ToolDescriptor) = policy(tool, null)
    override suspend fun set(tool: ToolDescriptor, policy: ToolPolicy) = set(tool, null, policy)
    override suspend fun set(tool: ToolDescriptor, role: AgentRole?, policy: ToolPolicy): Unit = withContext(Dispatchers.IO) {
        synchronized(this@ToolApprovalStore) {
            val key = scopedToolPolicyKey(tool, role)
            val editor = prefs.edit()
            if (policy == ToolPolicy.ASK) editor.remove(key) else editor.putString(key, policy.name)
            check(editor.commit()) { "工具授权保存失败" }
            mutable.value = if (policy == ToolPolicy.ASK) mutable.value - key else mutable.value + (key to policy)
        }
    }
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        synchronized(this@ToolApprovalStore) { check(prefs.edit().clear().commit()); mutable.value = emptyMap() }
    }
}
class ToolApprovalsPlugin(private val store: ToolApprovalStore) : SolvitPlugin {
    override val id = "builtin.tool-approvals"
    override val provides = setOf(ToolPolicyServiceKey)
    override suspend fun enable(context: PluginContext) { context.register(ToolPolicyServiceKey, store) }
}
