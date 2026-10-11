package dev.solvit.terminal

import dev.solvit.core.*
import dev.solvit.workspace.AndroidExecution
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Original PTY backend. It exposes bounded transcripts, not a full VT screen emulator. */
class AndroidTerminals(private val scope: CoroutineScope, private val workspace: WorkspaceService,
    private val execution: AndroidExecution) : TerminalService {
    private class Entry(val pty: PtyConnection, @Volatile var state: TerminalSession) { @Volatile var reader: Job? = null }
    private val lock = Mutex()
    private val snapshots = Any()
    private val entries = linkedMapOf<String, Entry>()
    private val mutableSessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    override val sessions = mutableSessions.asStateFlow()
    private var stopped = false
    private fun publish() { mutableSessions.value = entries.values.map { it.state } }
    private fun update(entry: Entry, transform: (TerminalSession) -> TerminalSession) = synchronized(snapshots) {
        entry.state = transform(entry.state); publish()
    }
    private fun verify(context: kotlin.coroutines.CoroutineContext, workspaceId: String,
        operation: String, id: String, text: String? = null): Pair<String, String?> {
        val invocation = context[ToolInvocationContext] ?: return TERMINAL_OWNER to null
        val conversation = context[ConversationContext] ?: error("终端调用缺少对话工作区")
        check(conversation.workspaceId == workspaceId && workspace.state.value.bindings[conversation.sessionId] == workspaceId)
        val args = JSONObject(invocation.call.argumentsJson)
        check(args.opt(if (operation == "open") "shellId" else "id") == id &&
            (text == null || args.opt("text") == text)) { "终端参数与确认内容不一致" }
        if (operation != "status") invocation.claim("native.terminal.$operation")
        return invocation.descriptor.pluginId to context[AgentRoleContext]?.role?.id
    }
    override suspend fun open(workspaceId: String, shellId: String): TerminalSession {
        val context = currentCoroutineContext()
        val (owner, role) = verify(context, workspaceId, "open", shellId)
        var acquired: Entry? = null
        try {
        val result = execution.authorized(owner) { mode ->
            check(mode == ExecutionMode.APP) { "交互式 PTY 暂不支持 Root；请使用已提供的普通 Root 命令工具，或在设置切换普通应用身份" }
            check(workspace.state.value.shells.any { it.id == shellId && it.workspaceId == workspaceId }) { "Shell 不属于此工作区" }
            val directory = File(workspace.location(workspaceId))
            withContext(Dispatchers.IO) { lock.withLock {
                check(!stopped)
                val existing = synchronized(snapshots) { entries.values.firstOrNull {
                    it.state.active && it.state.owner == owner && it.state.roleId == role &&
                        it.state.workspaceId == workspaceId && it.state.shellId == shellId && it.state.mode == mode
                } }
                if (existing != null) { acquired = existing; return@withLock existing.state }
                synchronized(snapshots) {
                    check(entries.values.count { it.state.active } < 8) { "最多同时运行 8 个交互终端" }
                    while (entries.size >= 16) entries.entries.firstOrNull { !it.value.state.active }?.let { entries.remove(it.key) } ?: error("终端历史已满")
                }
                val pty = PtyConnection.open(directory)
                val entry = Entry(pty, TerminalSession(UUID.randomUUID().toString(), owner, role, workspaceId, shellId, mode))
                acquired = entry
                synchronized(snapshots) { entries[entry.state.id] = entry; publish() }
                entry.reader = scope.launch(Dispatchers.IO) { collect(entry) }
                entry.state
            } }
        }
        own(context, result.id)
        context.ensureActive()
        return result
        } catch (e: Throwable) {
            withContext(NonCancellable) { acquired?.let { entry -> lock.withLock { stop(entry) } } }
            throw e
        }
    }
    private fun own(context: kotlin.coroutines.CoroutineContext, id: String) {
        val cleanup: suspend () -> Unit = { lock.withLock { synchronized(snapshots) { entries[id] }?.let { stop(it) } } }
        context[ToolInvocationContext]?.own("pty:$id", cleanup)
        context[AgentExecutionContext]?.ownIfAbsent("pty:$id", cleanup)
    }
    private suspend fun collect(entry: Entry) {
        try {
            val input = object : InputStream() {
                private var pending = byteArrayOf(); private var offset = 0
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
                override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                    if (length == 0) return 0
                    while (offset == pending.size) {
                        if (!entry.state.active || entry.reader?.isActive == false) return -1
                        pending = entry.pty.read() ?: return -1; offset = 0
                        if (pending.isEmpty()) Thread.yield()
                    }
                    val count = minOf(length, pending.size - offset)
                    pending.copyInto(bytes, start, offset, offset + count); offset += count; return count
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            val reader = InputStreamReader(input, decoder)
            val buffer = CharArray(4096)
            while (currentCoroutineContext().isActive) {
                val count = reader.read(buffer); if (count < 0) break
                val text = String(buffer, 0, count)
                update(entry) { old -> val output = old.output + text
                    old.copy(output = output.takeLast(65_536), revision = old.revision + 1, truncated = old.truncated || output.length > 65_536)
                }
                yield()
            }
            update(entry) { it.copy(active = false, exitCode = entry.pty.exitCode(), revision = it.revision + 1) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (entry.state.active) update(entry) { it.copy(active = false, failure = e.message ?: "终端读取失败", revision = it.revision + 1) }
        } finally { entry.pty.close() }
    }
    private fun find(workspaceId: String, id: String, owner: String, role: String?, nativeUser: Boolean): Entry = synchronized(snapshots) {
        val entry = entries[id] ?: error("终端不存在或历史已回收")
        check(entry.state.workspaceId == workspaceId)
        check(nativeUser || entry.state.owner == owner && entry.state.roleId == role) { "终端属于其他插件或角色" }
        entry
    }
    override suspend fun status(workspaceId: String, id: String): TerminalSession {
        val context = currentCoroutineContext(); val (owner, role) = verify(context, workspaceId, "status", id)
        return find(workspaceId, id, owner, role, context[ToolInvocationContext] == null).state
    }
    private suspend fun act(workspaceId: String, id: String, operation: String, text: String? = null,
        block: (PtyConnection) -> Unit): TerminalSession {
        val context = currentCoroutineContext(); val (owner, role) = verify(context, workspaceId, operation, id, text)
        return execution.authorized(owner) { mode -> withContext(Dispatchers.IO) { lock.withLock {
            val entry = find(workspaceId, id, owner, role, context[ToolInvocationContext] == null)
            check(entry.state.active && entry.state.mode == mode) { "终端已关闭或执行身份已变化" }
            block(entry.pty); own(context, id); entry.state
        } } }
    }
    override suspend fun input(workspaceId: String, id: String, text: String): TerminalSession {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..32_768 && '\u0000' !in text) { "终端输入需为 1–32768 字节" }
        return act(workspaceId, id, "input", text) { it.write(bytes) }
    }
    override suspend fun interrupt(workspaceId: String, id: String) = act(workspaceId, id, "interrupt") { it.interrupt() }
    private suspend fun stop(entry: Entry) = withContext(Dispatchers.IO + NonCancellable) {
        update(entry) { it.copy(active = false, revision = it.revision + 1) }
        entry.pty.close(); entry.reader?.cancelAndJoin()
    }
    override suspend fun close(workspaceId: String, id: String) {
        val context = currentCoroutineContext(); val (owner, role) = verify(context, workspaceId, "close", id)
        lock.withLock { stop(find(workspaceId, id, owner, role, context[ToolInvocationContext] == null)) }
    }
    override suspend fun cancelOwner(owner: String) = lock.withLock {
        synchronized(snapshots) { entries.values.filter { it.state.owner == owner && it.state.active } }.forEach { stop(it) }
    }
    suspend fun shutdown() = lock.withLock {
        stopped = true
        synchronized(snapshots) { entries.values.toList() }.forEach { stop(it) }
    }
}

class TerminalPlugin : SolvitPlugin {
    override val id = "builtin.pty"
    override val requires = setOf(WorkspaceServiceKey, SystemExecutionServiceKey)
    override val provides = setOf(TerminalServiceKey)
    override suspend fun enable(context: PluginContext) {
        val execution = context.require(SystemExecutionServiceKey) as? AndroidExecution ?: error("缺少 Android 执行身份服务")
        val service = AndroidTerminals(context.scope, context.require(WorkspaceServiceKey), execution)
        val removeCleanup = execution.onIdentityInvalidated { service.cancelOwner(it) }
        context.own { removeCleanup(); service.shutdown() }
        context.register(TerminalServiceKey, service)
    }
}
