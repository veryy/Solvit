package dev.solvit.system

import dev.solvit.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

object ShellAuthorization {
    fun parse(arguments: JsonObject): ShellRequest {
        require(arguments.keys.all { it in setOf("command", "cwd", "timeoutMs") }) { "Shell 参数包含未知字段" }
        fun string(key: String, default: String? = null): String {
            val value = arguments[key] ?: return default ?: error("缺少 $key")
            require(value is JsonPrimitive && value.isString) { "$key 需为文本" }
            return value.content
        }
        val timeout = arguments["timeoutMs"]?.let {
            require(it is JsonPrimitive && !it.isString && it.longOrNull != null) { "timeoutMs 需为整数" }
            it.long
        } ?: 60_000
        return ShellRequest(string("command"), string("cwd", ""), timeout).also { it.validate() }
    }
    suspend fun invocation(owner: String, mode: ExecutionMode, policies: ToolPolicyService? = null): ToolInvocationContext {
        val context = currentCoroutineContext()
        context.ensureActive()
        val invocation = context[ToolInvocationContext] ?: error("Shell 只能由已授权的工具调用")
        check(invocation.descriptor.pluginId == owner && "shell" in invocation.descriptor.capabilities) { "此工具没有 Shell 权限" }
        check(context[AgentRoleContext]?.role?.allows(owner) != false) { "当前角色未绑定此插件" }
        check(invocation.descriptor.executionMode == mode) { "执行模式已变化，请重新确认" }
        check(policies?.policy(invocation.descriptor) != ToolPolicy.DENY) { "工具已被禁止" }
        return invocation
    }
    suspend fun start(owner: String, request: ShellRequest, mode: ExecutionMode, policies: ToolPolicyService? = null) {
        val invocation = invocation(owner, mode, policies)
        val approved = parse(Json.parseToJsonElement(invocation.call.argumentsJson).jsonObject)
        check(approved == request) { "插件不能修改已授权的命令、目录或时限" }
        invocation.claim("shell.start")
    }
    suspend fun job(owner: String, id: String, after: Long?, mode: ExecutionMode, policies: ToolPolicyService? = null) {
        val invocation = invocation(owner, mode, policies)
        val input = Json.parseToJsonElement(invocation.call.argumentsJson).jsonObject
        check(input["id"]?.jsonPrimitive?.contentOrNull == id) { "插件不能访问未经确认的任务" }
        if (after != null) {
            require(after >= -1)
            check((input["after"]?.jsonPrimitive?.longOrNull ?: -1) == after) { "插件不能修改输出游标" }
        } else invocation.claim("shell.cancel")
    }
}
