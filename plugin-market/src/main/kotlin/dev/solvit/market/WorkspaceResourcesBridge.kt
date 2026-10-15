package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.ScriptManifest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Generic plugin text snapshots; paths and publication are owned by the host. */
class WorkspaceResourcesBridge(private val workspaces: WorkspaceService, private val validate: suspend () -> Unit) {
    suspend fun prepare(manifest: ScriptManifest, arguments: JsonObject): JsonObject {
        check(manifest.apiVersion >= 31 && "workspace.resources" in manifest.nativeServices && "workspace" in manifest.permissions)
        val context = currentCoroutineContext()
        val invocation = context[ToolInvocationContext] ?: error("资源准备必须通过聊天工具授权")
        val conversation = context[ConversationContext] ?: error("请先绑定当前对话的工作区")
        require(arguments.keys == setOf("name", "files")) { "资源准备参数无效" }
        val name = arguments.getValue("name").jsonPrimitive.also { require(it.isString) }.content
        // The model selects only a resource name. File bodies come from the authorized
        // plugin implementation, never from extra model arguments or a target path.
        val approved = buildJsonObject { put("name", name) }
        check(Json.parseToJsonElement(invocation.call.argumentsJson) == approved) { "资源名称与用户确认内容不一致" }
        val files = arguments.getValue("files").jsonObject.mapValues { (_, value) ->
            value.jsonPrimitive.also { require(it.isString) }.content
        }
        suspend fun authorize() {
            context.ensureActive(); validate()
            check(invocation.descriptor.pluginId == manifest.id && context[AgentRoleContext]?.role?.allows(manifest.id) != false)
            check(workspaces.state.value.bindings[conversation.sessionId] == conversation.workspaceId) { "对话工作区已变化" }
        }
        authorize(); invocation.claim("workspace.prepareResources")
        val result = workspaces.prepareResources(conversation.workspaceId, name, files, ::authorize)
        authorize()
        return buildJsonObject {
            put("path", result.path); put("files", result.files); put("bytes", result.bytes); put("sourceSha256", result.sourceSha256)
        }
    }
}
