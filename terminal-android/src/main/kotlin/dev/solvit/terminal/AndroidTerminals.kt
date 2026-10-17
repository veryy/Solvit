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

/** Native PTY primitive; terminal rendering belongs to the offline HTML plugin. */
class AndroidTerminals(private val scope: CoroutineScope, private val workspace: WorkspaceService,
    private val execution: AndroidExecution, private val linux: LinuxEnvironmentService? = null) : TerminalService {
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
    private data class Identity(val owner: String, val role: String?, val chat: String?, val human: Boolean, val controlledIds: Set<String> = emptySet())
    private suspend fun verify(context: kotlin.coroutines.CoroutineContext, workspaceId: String,
        operation: String, id: String, text: String? = null, claim: Boolean = true): Identity {
        context[TerminalPageAuthority]?.let { page ->
            page.verify(workspaceId)
            check(workspace.state.value.bindings[page.chatId] == workspaceId) { "终端页面的工作区绑定已改变" }
            return Identity(page.owner, null, page.chatId, true, page.controlledIds)
        }
        val invocation = context[ToolInvocationContext] ?: return Identity(TERMINAL_OWNER, null, null, true)
        val conversation = context[ConversationContext] ?: error("终端调用缺少对话工作区")
        check(conversation.workspaceId == workspaceId && workspace.state.value.bindings[conversation.sessionId] == workspaceId)
        val args = JSONObject(invocation.call.argumentsJson)
        check(args.opt(if (operation == "open") "shellId" else "id") == id &&
            (text == null || args.opt("text") == text)) { "终端参数与确认内容不一致" }
        if (claim && operation != "status") invocation.claim("native.terminal.$operation")
        return Identity(invocation.descriptor.pluginId, context[AgentRoleContext]?.role?.id, conversation.sessionId, false)
    }
    override suspend fun open(workspaceId: String, shellId: String): TerminalSession {
        val context = currentCoroutineContext()
        val identity = verify(context, workspaceId, "open", shellId)
        val owner = identity.owner; val role = identity.role
        var acquired: Entry? = null
        try {
        val result = execution.authorized(owner) { mode ->
            check(mode == ExecutionMode.APP) { "交互式 PTY 暂不支持 Root；请使用已提供的普通 Root 命令工具，或在设置切换普通应用身份" }
            val shell = workspace.state.value.shells.firstOrNull { it.id == shellId && it.workspaceId == workspaceId } ?: error("Shell 不属于此工作区")
            val directory = File(workspace.location(workspaceId))
            withContext(Dispatchers.IO) { lock.withLock {
                verify(context, workspaceId, "open", shellId, claim = false)
                check(!stopped)
                val existing = synchronized(snapshots) { entries.values.firstOrNull {
                    it.state.active && it.state.owner == owner && it.state.roleId == role &&
                        it.state.chatId == identity.chat && it.state.human == identity.human &&
                        it.state.workspaceId == workspaceId && it.state.shellId == shellId && it.state.mode == mode
                } }
                if (existing != null) { acquired = existing; return@withLock existing.state }
                synchronized(snapshots) {
                    check(entries.values.count { it.state.active } < 8) { "最多同时运行 8 个交互终端" }
                    while (entries.size >= 16) entries.entries.firstOrNull { !it.value.state.active }?.let { entries.remove(it.key) } ?: error("终端历史已满")
                }
                val plan = if (shell.runtime == ShellRuntime.UBUNTU) checkNotNull(linux) { "Linux 环境服务不可用" }.terminal(directory.absolutePath) else null
                val pty = if (plan == null) PtyConnection.open(directory) else PtyConnection.open(directory, command = plan.arguments, environment = plan.environment)
                val entry = Entry(pty, TerminalSession(UUID.randomUUID().toString(), owner, role, workspaceId, shellId, mode, chatId = identity.chat, human = identity.human))
                acquired = entry
                synchronized(snapshots) { entries[entry.state.id] = entry; publish() }
                entry.reader = scope.launch(Dispatchers.IO) { collect(entry) }
                entry.state
            } }
        }
        verify(context, workspaceId, "open", shellId, claim = false)
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
            val pendingOutput = StringBuilder()
            var lastPublication = System.nanoTime()
            fun flushOutput() {
                if (pendingOutput.isEmpty()) return
                val text = pendingOutput.toString(); pendingOutput.setLength(0)
                update(entry) { old ->
                    val output = old.output + text
                    var drop = (output.length - 65_536).coerceAtLeast(0)
                    if (drop > 0 && drop < output.length && output[drop].isLowSurrogate() && output[drop - 1].isHighSurrogate()) drop++
                    old.copy(output = output.substring(drop), outputOffset = old.outputOffset + drop,
                        revision = old.revision + 1, truncated = old.truncated || drop > 0)
                }
                lastPublication = System.nanoTime()
            }
            val input = object : InputStream() {
                private var pending = byteArrayOf(); private var offset = 0
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
                override fun read(bytes: ByteArray, start: Int, length: Int): Int {
                    if (length == 0) return 0
                    while (offset == pending.size) {
                        if (!entry.state.active || entry.reader?.isActive == false) return -1
                        pending = entry.pty.read() ?: return -1; offset = 0
                        if (pending.isEmpty()) { flushOutput(); Thread.yield() }
                    }
                    val count = minOf(length, pending.size - offset)
                    pending.copyInto(bytes, start, offset, offset + count); offset += count; return count
                }
            }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            val reader = InputStreamReader(input, decoder)
            val buffer = CharArray(4096)
            var pendingHigh = ""
            while (currentCoroutineContext().isActive) {
                val count = reader.read(buffer); if (count < 0) break
                var text = pendingHigh + String(buffer, 0, count)
                pendingHigh = ""
                if (text.lastOrNull()?.isHighSurrogate() == true) {
                    pendingHigh = text.takeLast(1); text = text.dropLast(1)
                }
                if (text.isEmpty()) continue
                pendingOutput.append(text)
                if (pendingOutput.length >= 4096 || System.nanoTime() - lastPublication >= 32_000_000) {
                    flushOutput(); yield()
                }
            }
            flushOutput()

            // PTY EOF can precede waitpid's final outcome. Do not publish a closed
            // session or close its handle until the real child result is available.
            // delay keeps this wait cancellable by close, revocation and shutdown.
            var exitCode = entry.pty.exitCode()
            while (exitCode == null) {
                delay(10)
                exitCode = entry.pty.exitCode()
            }
            update(entry) { it.copy(active = false, exitCode = exitCode, revision = it.revision + 1) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (entry.state.active) update(entry) { it.copy(active = false, failure = e.message ?: "终端读取失败", revision = it.revision + 1) }
        } finally { entry.pty.close() }
    }
    private fun find(workspaceId: String, id: String, identity: Identity, nativeUser: Boolean): Entry = synchronized(snapshots) {
        val entry = entries[id] ?: error("终端不存在或历史已回收")
        check(entry.state.workspaceId == workspaceId)
        check(nativeUser || entry.state.chatId == identity.chat && (
            entry.state.owner == identity.owner && entry.state.roleId == identity.role && entry.state.human == identity.human ||
            identity.human && id in identity.controlledIds)) { "终端属于其他聊天、插件或角色" }
        entry
    }
    override suspend fun status(workspaceId: String, id: String): TerminalSession {
        val context = currentCoroutineContext(); val identity = verify(context, workspaceId, "status", id)
        return find(workspaceId, id, identity, context[ToolInvocationContext] == null && context[TerminalPageAuthority] == null).state
    }
    private suspend fun act(workspaceId: String, id: String, operation: String, text: String? = null,
        dimensions: Pair<Int, Int>? = null, block: (PtyConnection) -> Unit): TerminalSession {
        val context = currentCoroutineContext(); val identity = verify(context, workspaceId, operation, id, text)
        return execution.authorized(identity.owner) { mode -> withContext(Dispatchers.IO) { lock.withLock {
            verify(context, workspaceId, operation, id, text, claim = false)
            val entry = find(workspaceId, id, identity, context[ToolInvocationContext] == null && context[TerminalPageAuthority] == null)
            check(entry.state.active && entry.state.mode == mode) { "终端已关闭或执行身份已变化" }
            block(entry.pty)
            dimensions?.let { (rows, columns) -> update(entry) { it.copy(rows = rows, columns = columns, revision = it.revision + 1) } }
            own(context, id); entry.state
        } } }
    }
    override suspend fun input(workspaceId: String, id: String, text: String): TerminalSession {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..32_768 && '\u0000' !in text) { "终端输入需为 1–32768 字节" }
        return act(workspaceId, id, "input", text) { it.write(bytes) }
    }
    override suspend fun resize(workspaceId: String, id: String, rows: Int, columns: Int): TerminalSession {
        require(rows in 2..300 && columns in 2..500)
        check(currentCoroutineContext()[ToolInvocationContext] == null) { "模型工具尚未开放终端尺寸调整" }
        return act(workspaceId, id, "resize", dimensions = rows to columns) { it.resize(rows, columns) }
    }
    override suspend fun interrupt(workspaceId: String, id: String) = act(workspaceId, id, "interrupt") { it.interrupt() }
    private suspend fun stop(entry: Entry) = withContext(Dispatchers.IO + NonCancellable) {
        update(entry) { it.copy(active = false, revision = it.revision + 1) }
        entry.pty.close(); entry.reader?.cancelAndJoin()
    }
    override suspend fun close(workspaceId: String, id: String) {
        val context = currentCoroutineContext(); val identity = verify(context, workspaceId, "close", id)
        lock.withLock { verify(context, workspaceId, "close", id, claim = false); stop(find(workspaceId, id, identity, context[ToolInvocationContext] == null && context[TerminalPageAuthority] == null)) }
    }
    override suspend fun cancelOwner(owner: String) = lock.withLock {
        synchronized(snapshots) { entries.values.filter { it.state.owner == owner && it.state.active } }.forEach { stop(it) }
    }
    suspend fun shutdown() = lock.withLock {
        stopped = true
        synchronized(snapshots) { entries.values.toList() }.forEach { stop(it) }
    }
}

class TerminalPlugin(private val useLinux: Boolean = false) : SolvitPlugin {
    override val id = "builtin.pty"
    override val requires = setOf(WorkspaceServiceKey, SystemExecutionServiceKey) + if (useLinux) setOf(LinuxEnvironmentServiceKey) else emptySet()
    override val provides = setOf(TerminalServiceKey)
    override suspend fun enable(context: PluginContext) {
        val execution = context.require(SystemExecutionServiceKey) as? AndroidExecution ?: error("缺少 Android 执行身份服务")
        val service = AndroidTerminals(context.scope, context.require(WorkspaceServiceKey), execution,
            if (useLinux) context.require(LinuxEnvironmentServiceKey) else null)
        val removeCleanup = execution.onIdentityInvalidated { service.cancelOwner(it) }
        context.own { removeCleanup(); service.shutdown() }
        context.register(TerminalServiceKey, service)
    }
}
