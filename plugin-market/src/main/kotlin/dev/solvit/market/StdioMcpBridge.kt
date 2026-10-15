package dev.solvit.market

import dev.solvit.core.*
import dev.solvit.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Authority seam: commands come from saved private settings; model arguments select names only. */
class StdioMcpBridge(private val processes: StdioProcessService, private val linux: LinuxEnvironmentService,
    private val workspaces: WorkspaceService, private val roles: RoleService?,
    private val configuration: suspend (ScriptManifest) -> String?,
    private val verifyPlugin: suspend (ScriptManifest) -> Unit,
    private val mode: (String) -> ExecutionMode = { ExecutionMode.APP }) {
    private data class Review(val plugin: String, val page: String, val server: String, val fingerprint: String,
        val scope: String, val issued: Long)
    private val reviewLock = Any()
    private val reviews = linkedMapOf<String, Review>()
    private data class Authority(val ownerScope: String, val conversation: ConversationContext, val role: AgentRole?,
        val page: ScriptPageExecutionContext?, val invocation: ToolInvocationContext?)

    private suspend fun authority(manifest: ScriptManifest, context: CoroutineContext, optional: Boolean = false): Authority? {
        check(manifest.apiVersion >= 30 && "process.stdio" in manifest.nativeServices && "environment.linux" in manifest.nativeServices &&
            manifest.permissions.containsAll(setOf("workspace", "shell", "network"))) { "本地MCP需要API30、进程/Linux服务及工作区/Shell/网络授权" }
        verifyPlugin(manifest)
        check(mode(manifest.id) == ExecutionMode.APP) { "本地MCP仅支持普通应用身份，请切换执行模式" }
        val page = context[ScriptPageExecutionContext]
        val invocation = context[ToolInvocationContext]
        check(page == null || page.pluginId == manifest.id)
        check(invocation == null || invocation.descriptor.pluginId == manifest.id)
        check(page?.pluginId == manifest.id || invocation?.descriptor?.pluginId == manifest.id) { "本地MCP需要当前页面或工具授权" }
        val conversation = if (page != null) runCatching { page.stdioConversation() }.getOrElse {
            if (optional && it !is CancellationException) return null else throw it
        } else context[ConversationContext] ?: if (optional) return null else error("请先为当前聊天绑定工作区")
        val role = if (page == null) context[AgentRoleContext]?.role else null
        check(role?.allows(manifest.id) != false) { "当前角色未绑定MCP插件" }
        check(workspaces.state.value.bindings[conversation.sessionId] == conversation.workspaceId) { "当前聊天工作区已变化" }
        val scope = privateDigest(buildJsonObject {
            put("plugin", manifest.id); put("chat", conversation.sessionId); put("workspace", conversation.workspaceId)
            put("role", role?.let { buildJsonObject {
                put("id", it.id); put("name", it.name); put("prompt", it.prompt)
                put("plugins", JsonArray(it.pluginIds.sorted().map(::JsonPrimitive)))
                put("skills", JsonArray(it.skillIds.sorted().map(::JsonPrimitive)))
            } } ?: JsonNull)
            page?.let { put("page", it.stdioScope) }
        }.toString())
        return Authority(scope, conversation, role, page, invocation)
    }

    suspend fun call(manifest: ScriptManifest, operation: String, arguments: JsonObject): JsonElement {
        try { return execute(manifest, operation, arguments) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: ToolExecutionException) { throw known }
        catch (_: Exception) { throw ToolExecutionException(ToolFailureCode.LOCAL_MCP_DISCONNECTED) }
    }
    private suspend fun execute(manifest: ScriptManifest, operation: String, arguments: JsonObject): JsonElement {
        val context = currentCoroutineContext()
        if (operation == "stdio.scope") {
            require(arguments.isEmpty())
            return authority(manifest, context, optional = true)?.ownerScope?.let(::JsonPrimitive) ?: JsonNull
        }
        val access = checkNotNull(authority(manifest, context))
        fun string(key: String) = arguments.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        val server = string("server")
        val encoded = checkNotNull(configuration(manifest)) { "请先保存本地MCP连接配置" }
        val profile = LocalMcpConfiguration.select(encoded, server)
        if (access.invocation != null) {
            val approved = Json.parseToJsonElement(access.invocation.call.argumentsJson).jsonObject
            val selected = approved["server"]?.jsonPrimitive?.also { require(it.isString) }?.content
            if (operation != "stdio.close" || selected != null) {
                val actual = LocalMcpConfiguration.select(encoded, selected)
                check(actual.name == profile.name) { "本地MCP服务器与工具确认内容不一致" }
            }
        }
        val owner = StdioOwner(manifest.id, access.ownerScope, profile.name, profile.fingerprint)
        val directory = File(workspaces.location(access.conversation.workspaceId)).canonicalFile
        val identity = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        check(identity != null && directory.isDirectory)
        suspend fun authorize() {
            currentCoroutineContext().ensureActive()
            verifyPlugin(manifest)
            check(mode(manifest.id) == ExecutionMode.APP)
            access.page?.let { page ->
                page.ensureAuthorized()
                val current = page.stdioConversation()
                check(current.sessionId == access.conversation.sessionId && current.workspaceId == access.conversation.workspaceId)
            }
            check(workspaces.state.value.bindings[access.conversation.sessionId] == access.conversation.workspaceId) { "MCP工作区绑定已变化" }
            if (access.page == null && roles != null) check(roles.selected(access.conversation.sessionId) == access.role) { "MCP角色已变化" }
            check(LocalMcpConfiguration.select(checkNotNull(configuration(manifest)), profile.name).fingerprint == profile.fingerprint) { "MCP连接配置已变化" }
            check(File(workspaces.location(access.conversation.workspaceId)).canonicalFile == directory &&
                Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == identity) { "MCP工作区目录已变化" }
        }
        authorize()
        if (operation == "stdio.review") {
            require(arguments.keys == setOf("server") && access.page != null)
            val token = UUID.randomUUID().toString()
            synchronized(reviewLock) {
                val now = System.nanoTime()
                reviews.entries.removeAll { (now - it.value.issued) / 1_000_000 > 120_000 }
                while (reviews.size >= 64) reviews.remove(reviews.keys.first())
                reviews[token] = Review(manifest.id, access.page.stdioScope, profile.name, profile.fingerprint, access.ownerScope, now)
            }
            return buildJsonObject { put("reviewId", token); put("command", profile.command); put("argumentCount", profile.arguments.size); put("environmentCount", profile.environment.size) }
        }
        if (operation == "stdio.open") {
            require(arguments.keys == setOf("server"))
            access.page?.let { page ->
                val token = page.consumeStdioStart(server)
                val review = synchronized(reviewLock) { reviews.remove(token) }
                check(review != null && review.plugin == manifest.id && review.page == page.stdioScope && review.server == server &&
                    review.fingerprint == profile.fingerprint && review.scope == access.ownerScope &&
                    (System.nanoTime() - review.issued) / 1_000_000 <= 120_000) { "MCP启动审阅已变化或过期，请重新查看" }
            }
            val cwd = if (profile.cwd.isEmpty()) directory else File(directory, profile.cwd)
            val plan = try { linux.process(directory.path, profile.command, profile.arguments, profile.environment, cwd.path) }
                catch (_: Exception) { throw ToolExecutionException(ToolFailureCode.LOCAL_MCP_ENVIRONMENT) }
            var acquired: String? = null
            try {
                val id = processes.open(owner, plan, directory.path, ::authorize)
                acquired = id
                val cleanup: suspend () -> Unit = { processes.close(owner, id) }
                access.page?.ownStdio("stdio:$id", cleanup)
                access.invocation?.ownIfAbsent("stdio:$id", cleanup)
                context[AgentExecutionContext]?.ownIfAbsent("stdio:$id", cleanup)
                authorize(); currentCoroutineContext().ensureActive()
                return JsonPrimitive(id)
            } catch (error: Throwable) {
                withContext(NonCancellable) { acquired?.let { processes.close(owner, it) } }
                throw error
            }
        }
        val id = string("id")
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        try {
            return when (operation) {
                "stdio.close" -> { require(arguments.keys == setOf("server", "id")); processes.close(owner, id); JsonNull }
                "stdio.receive" -> {
                    require(arguments.keys == setOf("server", "id"))
                    Json.parseToJsonElement(processes.receive(owner, id, ::authorize)).jsonObject
                }
                "stdio.send" -> {
                    require(arguments.keys == setOf("server", "id", "frame"))
                    val frame = arguments.getValue("frame").jsonObject
                    authorizeFrame(frame, access.invocation, access.page != null)
                    processes.send(owner, id, frame.toString(), ::authorize); JsonNull
                }
                else -> error("未知的本地MCP操作")
            }
        } catch (error: Throwable) {
            // A failed or cancelled exchange is not retried on a possibly desynchronized pipe.
            withContext(NonCancellable) { runCatching { processes.close(owner, id) } }
            throw error
        }
    }

    private fun authorizeFrame(frame: JsonObject, invocation: ToolInvocationContext?, human: Boolean) {
        require(frame["jsonrpc"] == JsonPrimitive("2.0"))
        val method = frame["method"]?.jsonPrimitive?.also { require(it.isString) }?.content
        if (method == null) {
            require(frame.keys.all { it in setOf("jsonrpc", "id", "result", "error") } && frame["id"] != null &&
                ((frame["result"] == null) != (frame["error"] == null)))
            return
        }
        require(method in setOf("initialize", "notifications/initialized", "tools/list", "tools/call", "ping")) { "此MCP客户端能力未开放" }
        if (method == "tools/call") {
            check(!human && invocation != null) { "页面连接测试不能执行MCP工具" }
            val approved = Json.parseToJsonElement(invocation.call.argumentsJson).jsonObject
            val params = frame.getValue("params").jsonObject
            require(params.keys == setOf("name", "arguments") && params["name"] == approved["name"] && params["arguments"] == approved["arguments"] &&
                approved["name"]?.jsonPrimitive?.isString == true && approved["arguments"] is JsonObject) { "MCP工具与确认的名称或参数不一致" }
            invocation.claim("stdio.tools/call")
        }
    }
}
