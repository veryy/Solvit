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
    rootAccess: RootAccess = AndroidRootAccess(),
    private val linux: LinuxEnvironmentService? = null,
    private val importPublisher: WorkspaceImportPublisher = WorkspaceImportPublisher { source,destination -> AndroidWorkspaceImportPublisher.publish(source,destination) }) : WorkspaceService {
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
            val s = array.getJSONObject(it); ShellSession(s.getString("id"), s.getString("workspace"), s.getString("name"),
                ShellRuntime.valueOf(s.optString("runtime", "ANDROID")))
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
            put("shells", JSONArray(value.shells.map { JSONObject().put("id", it.id).put("workspace", it.workspaceId).put("name", it.name).put("runtime", it.runtime.name) }))
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
        if(path.length !in 1..1024 || '\u0000' in path) throw ToolExecutionException(ToolFailureCode.INVALID_ARGUMENTS)
        if(File(path).isAbsolute) throw ToolExecutionException(ToolFailureCode.PATH_OUTSIDE_WORKSPACE)
        val base = directory(id).canonicalFile
        val file = File(base, path).canonicalFile
        if(file==base || !file.path.startsWith(base.path+File.separator)) throw ToolExecutionException(ToolFailureCode.PATH_OUTSIDE_WORKSPACE)
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
    private suspend fun verifyPage(workspaceId: String? = null, sessionId: String? = null) {
        currentCoroutineContext().ensureActive()
        currentCoroutineContext()[WorkspacePageAuthority]?.verify?.invoke(workspaceId, sessionId)
    }
    override suspend fun create(name: String): Workspace = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage()
        val workspace = workspace(name)
        save(state.value.copy(workspaces = state.value.workspaces + workspace)); workspace
    } }
    override suspend fun bind(sessionId: String, workspaceId: String) = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(sessionId = sessionId)
        require(sessionId.isNotBlank() && sessionId.length <= 128)
        directory(workspaceId)
        save(state.value.copy(bindings = state.value.bindings + (sessionId to workspaceId)))
    } }
    override suspend fun unbind(sessionId: String) = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(sessionId = sessionId)
        // Keep reusable workspaces and files when a conversation is deleted.
        save(state.value.copy(bindings = state.value.bindings - sessionId))
    } }
    override suspend fun createShell(workspaceId: String, name: String): ShellSession = createShell(workspaceId, name, ShellRuntime.ANDROID)
    override suspend fun createShell(workspaceId: String, name: String, runtime: ShellRuntime): ShellSession = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        directory(workspaceId)
        check(state.value.shells.count { it.workspaceId == workspaceId } < 16) { "每个工作区最多 16 个 Shell 会话" }
        if (runtime == ShellRuntime.UBUNTU) check(linux?.state?.value?.let { it.supported && it.installed } == true) { "请先完成 Ubuntu 环境安装" }
        val shell = ShellSession(UUID.randomUUID().toString(), workspaceId, name(name), runtime)
        save(state.value.copy(shells = state.value.shells + shell)); shell
    } }
    override suspend fun location(workspaceId: String): String = withContext(Dispatchers.IO) { lock.withLock { verifyPage(workspaceId); directory(workspaceId).canonicalPath } }
    override suspend fun instructionSources(workspaceId: String, authorize: suspend () -> Unit): List<WorkspaceInstructionSource> =
        withContext(Dispatchers.IO) { lock.withLock {
            WorkspaceInstructionFiles.capture(directory(workspaceId), authorize)
        } }
    override suspend fun snapshot(workspaceId: String): WorkspaceSnapshot = withContext(Dispatchers.IO) { lock.withLock {
        WorkspaceSnapshots.capture(workspaceId,directory(workspaceId))
    } }
    override suspend fun list(workspaceId: String, path: String): List<WorkspaceFile> = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
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
            if(!file.exists()) throw ToolExecutionException(ToolFailureCode.FILE_NOT_FOUND)
            if(!file.isFile) throw ToolExecutionException(ToolFailureCode.NOT_REGULAR_FILE)
            if(file.length()>MAX_TEXT) throw ToolExecutionException(ToolFailureCode.FILE_TOO_LARGE)
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count == -1) break
                    if(output.size()+count>MAX_TEXT) throw ToolExecutionException(ToolFailureCode.FILE_TOO_LARGE)
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                require(bytes.size <= MAX_TEXT); bytes.toString(Charsets.UTF_8)
            }
        }
    }
    /** File management does not silently operate on a symlink's target. */
    private fun managedEntry(workspaceId:String,path:String):File {
        val target=file(workspaceId,path)
        require(File(directory(workspaceId).canonicalFile,path).absoluteFile.normalize()==target) { "符号链接请通过终端管理。" }
        return target
    }
    override suspend fun makeDirectory(workspaceId:String,path:String)=withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        val target=managedEntry(workspaceId,path)
        check(!target.exists()) { "目标已存在。" }
        verifyPage(workspaceId)
        check(managedEntry(workspaceId,path)==target)
        java.nio.file.Files.createDirectories(target.toPath())
        Unit
    } }
    override suspend fun moveEntry(workspaceId:String,path:String,destination:String)=withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        val source=managedEntry(workspaceId,path);val target=managedEntry(workspaceId,destination)
        check(source.exists()) { "文件或目录不存在。" }
        check(!target.exists()) { "目标已存在。" }
        require(!target.path.startsWith(source.path+File.separator)) { "不能移动到自身子目录。" }
        check(target.parentFile?.isDirectory==true) { "目标父目录不存在。" }
        verifyPage(workspaceId)
        check(managedEntry(workspaceId,path)==source && managedEntry(workspaceId,destination)==target)
        java.nio.file.Files.move(source.toPath(),target.toPath())
        Unit
    } }
    override suspend fun deleteEntry(workspaceId:String,path:String)=withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        val target=managedEntry(workspaceId,path)
        check(target.exists()) { "文件或目录不存在。" }
        verifyPage(workspaceId)
        check(managedEntry(workspaceId,path)==target)
        // walkFileTree without FOLLOW_LINKS removes nested links themselves, never their targets.
        java.nio.file.Files.walkFileTree(target.toPath(),object:java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(path:java.nio.file.Path,attrs:java.nio.file.attribute.BasicFileAttributes):java.nio.file.FileVisitResult {
                java.nio.file.Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path:java.nio.file.Path,error:java.io.IOException?):java.nio.file.FileVisitResult {
                if(error!=null)throw error
                java.nio.file.Files.delete(path);return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        Unit
    } }
    override suspend fun readTextChunk(workspaceId: String, path: String, offset: Int,
        expectedSha256: String?): WorkspaceTextChunk = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        require(offset >= 0 && (offset == 0 || expectedSha256 != null)) { "后续片段需提供首次读取的文件版本。" }
        require(expectedSha256 == null || expectedSha256.matches(Regex("[a-f0-9]{64}")))
        val observed=FileObservations.capture(file(workspaceId,path))
        require(observed.kind==FileObservationKind.TEXT) { "预览只支持不超过256KiB的UTF-8文本。" }
        val content=checkNotNull(observed.captureText)
        val digest=checkNotNull(observed.sha256)
        check(expectedSha256==null || expectedSha256==digest) { "文件已变化，请重新打开预览。" }
        require(offset<=content.length && (offset==content.length || !content[offset].isLowSurrogate())) { "无效的文本分页位置。" }
        var end=minOf(offset.toLong()+4096,content.length.toLong()).toInt()
        if(end<content.length && end>offset && content[end-1].isHighSurrogate())end--
        verifyPage(workspaceId)
        WorkspaceTextChunk(content.substring(offset,end),offset,end,content.length,digest)
    } }
    override suspend fun readForEdit(workspaceId: String, path: String): WorkspaceTextFile = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        val observed = FileObservations.capture(file(workspaceId, path), 4096, 4096)
        require(observed.kind == FileObservationKind.TEXT) { "编辑器只支持不超过 4KB 的 UTF-8 文本；大文件请使用 Shell。" }
        WorkspaceTextFile(checkNotNull(observed.excerpt), checkNotNull(observed.sha256))
    } }
    override suspend fun writeForEdit(workspaceId: String, path: String, text: String, expectedSha256: String?): WorkspaceWriteEvidence = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        require(text.toByteArray(Charsets.UTF_8).size <= 4096 && '\u0000' !in text)
        val observed = FileObservations.capture(file(workspaceId, path))
        check(if (expectedSha256 == null) observed.kind == FileObservationKind.ABSENT else observed.sha256 == expectedSha256) {
            "文件已变化，请重新读取后保存。"
        }
        writeLocked(workspaceId, path, text)
    } }
    override suspend fun writeTextForEdit(workspaceId: String, path: String, text: String,
        expectedSha256: String?): WorkspaceWriteEvidence = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        require(expectedSha256 == null || expectedSha256.matches(Regex("[a-f0-9]{64}")))
        require(text.length <= MAX_FILE_CAPTURE_BYTES && '\u0000' !in text) { "只支持不超过256KiB的UTF-8文本。" }
        // String.toByteArray replaces unpaired surrogates; editing must reject them instead.
        val encoded=Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(text))
        require(encoded.remaining() <= MAX_FILE_CAPTURE_BYTES) { "文本超过256KiB。" }
        file(workspaceId,path)
        // Authorization can suspend or revoke the binding. Recheck before any filesystem mutation.
        verifyPage(workspaceId)
        writeLocked(workspaceId,path,text,requireVersion=true,expectedSha256=expectedSha256)
    } }
    override suspend fun importFile(workspaceId: String, path: String, bytes: ByteArray,
        expectedSha256: String): WorkspaceImportedFile = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        val base=directory(workspaceId).canonicalFile
        val result=WorkspaceFileImports.create(base,path,bytes,expectedSha256,importPublisher) { verifyPage(workspaceId) }
        WorkspaceImportedFile(workspaceId,path,result.first,result.second)
    } }
    override suspend fun reviewImportTarget(workspaceId:String,path:String):WorkspaceImportTarget? = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        WorkspaceFileImports.review(directory(workspaceId).canonicalFile,path) { verifyPage(workspaceId) }
    } }
    override suspend fun replaceImportedFile(workspaceId:String,path:String,bytes:ByteArray,
        sourceSha256:String,targetSha256:String):WorkspaceImportedFile = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        val result=WorkspaceFileImports.replace(directory(workspaceId).canonicalFile,path,bytes,sourceSha256,targetSha256,importPublisher) {
            verifyPage(workspaceId)
        }
        WorkspaceImportedFile(workspaceId,path,result.first,result.second,result.third)
    } }
    override suspend fun write(workspaceId: String, path: String, text: String) { writeObserved(workspaceId,path,text) }
    override suspend fun writeObserved(workspaceId: String, path: String, text: String): WorkspaceWriteEvidence = withContext(Dispatchers.IO) { lock.withLock {
        verifyPage(workspaceId)
        writeLocked(workspaceId, path, text)
    } }
    private fun writeLocked(workspaceId: String, path: String, text: String,
        requireVersion: Boolean = false, expectedSha256: String? = null): WorkspaceWriteEvidence {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if(bytes.size>MAX_TEXT) throw ToolExecutionException(ToolFailureCode.FILE_TOO_LARGE)
        val target = file(workspaceId, path)
        val before=FileObservations.capture(target)
        if(requireVersion) check(if(expectedSha256==null) before.kind==FileObservationKind.ABSENT
            else before.kind==FileObservationKind.TEXT && before.sha256==expectedSha256) {
            "文件已变化，请重新读取后保存。"
        }
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        // Recheck after creating parents, before opening the atomic file.
        check(file(workspaceId, path) == target)
        val atomic = AtomicFile(target); val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (e: Throwable) { atomic.failWrite(output); throw e }
        val after=try {
            if(file(workspaceId,path)!=target) throw ToolExecutionException(ToolFailureCode.WRITE_OUTCOME_UNKNOWN)
            FileObservations.capture(target)
        } catch(e: Exception) { throw ToolExecutionException(ToolFailureCode.WRITE_OUTCOME_UNKNOWN) }
        if(after.sha256!=FileObservations.fromBytes(bytes).sha256) throw ToolExecutionException(ToolFailureCode.WRITE_OUTCOME_UNKNOWN)
        return WorkspaceWriteEvidence(workspaceId,target.relativeTo(directory(workspaceId)).invariantSeparatorsPath,
            before,after,FileObservations.changed(before,after),true)
    }
    override suspend fun run(workspaceId: String, shellId: String, command: String, timeoutMs: Long): ShellJob {
        val shell = state.value.shells.firstOrNull { it.id == shellId && it.workspaceId == workspaceId } ?: error("Shell 会话不属于此工作区")
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
            val base = directory(workspaceId)
            val plan = if (shell.runtime == ShellRuntime.UBUNTU) {
                check(mode == ExecutionMode.APP) { "Ubuntu Shell 使用普通应用身份，请在设置切换身份" }
                checkNotNull(linux) { "Linux 环境服务不可用" }.command(base.absolutePath, command)
            } else null
            engine.start(owner, roleId, ShellRequest(command, timeoutMs = timeoutMs), mode,
                base, workspaceId, shellId, plan)
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

class WorkspacePlugin(private val androidContext: Context, private val useLinux: Boolean = false) : SolvitPlugin {
    override val id = "builtin.workspace"
    override val provides = setOf(WorkspaceServiceKey, SystemExecutionServiceKey)
    override val requires = if (useLinux) setOf(LinuxEnvironmentServiceKey) else emptySet()
    override suspend fun enable(context: PluginContext) {
        val service = AndroidWorkspaces(androidContext, context.scope, linux = if (useLinux) context.require(LinuxEnvironmentServiceKey) else null)
        context.own { service.close() }
        context.register(WorkspaceServiceKey, service)
        context.register(SystemExecutionServiceKey, service.execution)
    }
}
