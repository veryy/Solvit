package dev.solvit.workspace

import android.content.Context
import android.util.AtomicFile
import dev.solvit.core.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Native storage/process primitives; plugin tools remain JavaScript packages. */
class AndroidWorkspaces(context: Context, private val scope: CoroutineScope,
    launcher: ShellLauncher = ShellLauncher(ShellPaths("/system/bin/sh", "/system/bin/toybox", setsidArguments = listOf("setsid"))),
    rootAccess: RootAccess = AndroidRootAccess()) : WorkspaceService {
    private val root = File(context.filesDir, "workspaces").apply { check(mkdirs() || isDirectory) }
    private val prefs = context.getSharedPreferences("workspaces", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(load())
    override val state = mutableState.asStateFlow()
    private val engine = ShellJobEngine(scope, launcher, root)
    val execution = AndroidExecution(context, engine, rootAccess)
    override val jobs = engine.jobs
    private fun load(): WorkspaceState {
        val raw = prefs.getString("state", null) ?: return WorkspaceState()
        val value = JSONObject(raw)
        val workspaces = value.getJSONArray("workspaces").let { array -> (0 until array.length()).map {
            val w = array.getJSONObject(it); Workspace(w.getString("id"), w.getString("name"))
        } }
        val shells = value.getJSONArray("shells").let { array -> (0 until array.length()).map {
            val s = array.getJSONObject(it); ShellSession(s.getString("id"), s.getString("workspace"), s.getString("name"))
        } }
        val bindings = value.getJSONObject("bindings").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        check(workspaces.all { it.id.matches(ID) } && workspaces.map { it.id }.toSet().size == workspaces.size)
        check(shells.all { it.id.matches(ID) && workspaces.any { w -> w.id == it.workspaceId } })
        check(bindings.values.all { id -> workspaces.any { it.id == id } })
        return WorkspaceState(workspaces, bindings, shells)
    }
    private fun save(value: WorkspaceState) {
        val json = JSONObject().apply {
            put("workspaces", JSONArray(value.workspaces.map { JSONObject().put("id", it.id).put("name", it.name) }))
            put("shells", JSONArray(value.shells.map { JSONObject().put("id", it.id).put("workspace", it.workspaceId).put("name", it.name) }))
            put("bindings", JSONObject(value.bindings))
        }
        check(prefs.edit().putString("state", json.toString()).commit()) { "工作区配置保存失败" }
        mutableState.value = value
    }
    private fun directory(id: String): File {
        require(id.matches(ID) && state.value.workspaces.any { it.id == id }) { "工作区不存在" }
        val dir = File(root, id)
        check(dir.mkdirs() || dir.isDirectory)
        check(dir.canonicalFile == File(root.canonicalFile, id)) { "工作区目录无效" }
        return dir
    }
    private fun file(id: String, path: String): File {
        require(path.length in 1..1024 && '\u0000' !in path && !File(path).isAbsolute) { "请输入工作区内的相对路径" }
        val base = directory(id).canonicalFile
        val file = File(base, path).canonicalFile
        require(file != base && file.path.startsWith(base.path + File.separator)) { "路径超出工作区" }
        return file
    }
    private fun name(text: String): String = text.trim().also { require(it.length in 1..80 && it.none(Char::isISOControl)) { "名称需为 1–80 个字符" } }
    private fun workspace(text: String): Workspace {
        check(state.value.workspaces.size < 256) { "工作区数量已达上限" }
        val value = Workspace(UUID.randomUUID().toString(), name(text))
        File(root, value.id).apply { check(mkdirs() || isDirectory) }
        return value
    }
    override suspend fun forChat(sessionId: String): Workspace = withContext(Dispatchers.IO) { lock.withLock {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        val old = state.value
        old.bindings[sessionId]?.let { id -> return@withLock old.workspaces.first { it.id == id } }
        val workspace = workspace("对话工作区 ${old.workspaces.size + 1}")
        val shell = ShellSession(UUID.randomUUID().toString(), workspace.id, "Shell 1")
        save(old.copy(workspaces = old.workspaces + workspace, bindings = old.bindings + (sessionId to workspace.id), shells = old.shells + shell))
        workspace
    } }
    override suspend fun create(name: String): Workspace = withContext(Dispatchers.IO) { lock.withLock {
        val workspace = workspace(name)
        save(state.value.copy(workspaces = state.value.workspaces + workspace)); workspace
    } }
    override suspend fun bind(sessionId: String, workspaceId: String) = withContext(Dispatchers.IO) { lock.withLock {
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        directory(workspaceId)
        save(state.value.copy(bindings = state.value.bindings + (sessionId to workspaceId)))
    } }
    override suspend fun unbind(sessionId: String) = withContext(Dispatchers.IO) { lock.withLock {
        // Keep reusable workspaces and files when a conversation is deleted.
        save(state.value.copy(bindings = state.value.bindings - sessionId))
    } }
    override suspend fun createShell(workspaceId: String, name: String): ShellSession = withContext(Dispatchers.IO) { lock.withLock {
        directory(workspaceId)
        check(state.value.shells.count { it.workspaceId == workspaceId } < 16) { "每个工作区最多 16 个 Shell 会话" }
        val shell = ShellSession(UUID.randomUUID().toString(), workspaceId, name(name))
        save(state.value.copy(shells = state.value.shells + shell)); shell
    } }
    override suspend fun location(workspaceId: String): String = withContext(Dispatchers.IO) { directory(workspaceId).canonicalPath }
    override suspend fun list(workspaceId: String, path: String): List<WorkspaceFile> = withContext(Dispatchers.IO) { lock.withLock {
        val base = directory(workspaceId).canonicalFile
        val folder = if (path.isEmpty()) base else file(workspaceId, path)
        require(folder.isDirectory) { "目录不存在" }
        val entries = folder.listFiles() ?: error("目录无法读取")
        require(entries.size <= 1000) { "目录超过 1000 项，请使用 Shell 筛选" }
        entries.filter { it.canonicalFile.path.startsWith(base.path + File.separator) }.map {
            WorkspaceFile(it.name, it.relativeTo(base).invariantSeparatorsPath, it.isDirectory, if (it.isDirectory) 0 else it.length())
        }.sortedWith(compareBy<WorkspaceFile> { !it.directory }.thenBy { it.name })
    } }
    override suspend fun read(workspaceId: String, path: String): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = file(workspaceId, path)
            require(file.isFile && file.length() <= MAX_TEXT) { "文件不存在或超过 256 KiB" }
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    require(output.size() + count <= MAX_TEXT)
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= MAX_TEXT); bytes.toString(Charsets.UTF_8)
            }
        }
    }
    override suspend fun write(workspaceId: String, path: String, text: String) = withContext(Dispatchers.IO) { lock.withLock {
        val bytes = text.toByteArray(Charsets.UTF_8); require(bytes.size <= MAX_TEXT) { "文件超过 256 KiB" }
        val target = file(workspaceId, path)
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        // Recheck after creating parents, before opening the atomic file.
        check(file(workspaceId, path) == target)
        val atomic = AtomicFile(target); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Throwable) { atomic.failWrite(output); throw e }
    } }
    override suspend fun run(workspaceId: String, shellId: String, command: String, timeoutMs: Long): ShellJob {
        check(state.value.shells.any { it.id == shellId && it.workspaceId == workspaceId }) { "Shell 会话不属于此工作区" }
        val context = currentCoroutineContext()
        val invocation = context[ToolInvocationContext]
        val owner = invocation?.descriptor?.pluginId ?: TERMINAL_OWNER
        val roleId = context[AgentRoleContext]?.role?.id
        context.ensureActive()
        val job = execution.authorized(owner) { mode ->
            if (invocation != null) {
                val args = org.json.JSONObject(invocation.call.argumentsJson)
                check(args.opt("shellId") == shellId && args.opt("command") == command && timeoutMs == 60_000L) { "命令与确认内容不一致" }
                val conversation = context[ConversationContext] ?: error("Shell 调用缺少聊天工作区")
                check(conversation.workspaceId == workspaceId && state.value.bindings[conversation.sessionId] == workspaceId)
                invocation.claim("native.workspace.start")
            }
            engine.start(owner, roleId, ShellRequest(command, timeoutMs = timeoutMs), mode,
                directory(workspaceId), workspaceId, shellId)
        }
        try {
            val cancel: suspend () -> Unit = { engine.cancel(owner, roleId, job.id) }
            invocation?.own(job.id, cancel); context[AgentExecutionContext]?.own(job.id, cancel)
            context.ensureActive()
            return job
        } catch (e: Throwable) {
            withContext(NonCancellable) { engine.cancel(owner, roleId, job.id) }; throw e
        }
    }
    override suspend fun cancel(workspaceId: String, jobId: String) {
        val context = currentCoroutineContext()
        val job = jobs.value.firstOrNull { it.id == jobId && it.workspaceId == workspaceId } ?: error("工作区中不存在此任务")
        val invocation = context[ToolInvocationContext]
        if (invocation != null) check(job.owner == invocation.descriptor.pluginId && job.roleId == context[AgentRoleContext]?.role?.id) { "任务属于其他插件或角色" }
        engine.cancel(job.owner, job.roleId, job.id, userInitiated = invocation == null)
    }
    override suspend fun cancelOwner(owner: String) = engine.cancelOwner(owner)
    suspend fun close() = engine.close()
    private companion object {
        val ID = Regex("[a-f0-9-]{36}")
        const val MAX_TEXT = 256 * 1024
    }
}

class WorkspacePlugin(private val androidContext: Context) : SolvitPlugin {
    override val id = "builtin.workspace"
    override val provides = setOf(WorkspaceServiceKey, SystemExecutionServiceKey)
    override suspend fun enable(context: PluginContext) {
        val service = AndroidWorkspaces(androidContext, context.scope)
        context.own { service.close() }
        context.register(WorkspaceServiceKey, service)
        context.register(SystemExecutionServiceKey, service.execution)
    }
}
