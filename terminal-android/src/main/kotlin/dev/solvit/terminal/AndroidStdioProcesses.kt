package dev.solvit.terminal

import dev.solvit.core.*
import dev.solvit.system.StdioFrames
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Bounded process ownership; the trusted marketplace bridge constructs plans and identities. */
class AndroidStdioProcesses(private val scope: CoroutineScope,
    private val idleMillis: Long = 300_000) : StdioProcessService {
    init { require(idleMillis in 100..1_800_000) }
    private class Entry(val id: String, val owner: StdioOwner, val pipe: StdioConnection,
        val authorize: suspend () -> Unit) {
        val frames = Channel<Pair<String, Int>>(64)
        val queuedBytes = AtomicLong()
        val touched = AtomicLong(System.nanoTime())
        val stopped = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        val writeLock = Mutex()
        val readLock = Mutex()
        val tasks = mutableListOf<Job>()
    }
    private val lock = Mutex()
    private val entries = linkedMapOf<String, Entry>()
    private var closed = false

    override suspend fun open(owner: StdioOwner, plan: HostProcessPlan, directory: String,
        authorize: suspend () -> Unit): String {
        require(owner.pluginId.isNotBlank() && owner.scope.isNotBlank() && owner.server.isNotBlank() && owner.configuration.isNotBlank())
        var acquired: Entry? = null
        try {
            return withContext(Dispatchers.IO) { lock.withLock {
                ensureActive(); authorize(); check(!closed && scope.isActive) { "stdio 服务已关闭" }
                entries.values.firstOrNull { it.owner == owner && !it.stopped.get() && !it.finished.get() }?.let {
                    acquired = it; it.touched.set(System.nanoTime()); return@withLock it.id
                }
                // A changed configuration in the same scope retires the previous generation first.
                val stale = entries.values.filter { it.owner.pluginId == owner.pluginId && it.owner.scope == owner.scope && it.owner.server == owner.server }
                for (entry in stale) { entries.remove(entry.id); stop(entry) }
                check(entries.size < 4) { "最多同时运行4个本地MCP进程，请先断开闲置连接" }
                val environment = plan.environment.filterValues { it != null }.mapValues { it.value!! }
                val pipe = StdioConnection.open(File(directory), plan.arguments, environment)
                val entry = Entry(UUID.randomUUID().toString(), owner, pipe, authorize)
                acquired = entry
                entries[entry.id] = entry
                try {
                    authorize(); ensureActive()
                    entry.tasks += scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { collect(entry) }
                    entry.tasks += scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                        try { while (isActive && !entry.stopped.get()) { if (entry.pipe.readStderr() == null) break } }
                        catch (_: Exception) { /* Stderr is drained, never copied into model diagnostics. */ }
                    }
                    entry.tasks += scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            while (isActive && !entry.stopped.get()) {
                                delay(250); entry.authorize()
                                check((System.nanoTime() - entry.touched.get()) / 1_000_000 < idleMillis) { "stdio 空闲连接已关闭" }
                            }
                        } catch (_: Exception) { retire(entry) }
                    }
                    check(scope.isActive)
                    entry.tasks.forEach { it.start() }
                    entry.id
                } catch (error: Throwable) { entries.remove(entry.id); stop(entry); throw error }
            } }.also { currentCoroutineContext().ensureActive(); authorize() }
        } catch (error: Throwable) {
            withContext(NonCancellable) { acquired?.let { retire(it) } }
            throw error
        }
    }

    private suspend fun collect(entry: Entry) {
        try {
            val parser = StdioFrames()
            while (currentCoroutineContext().isActive && !entry.stopped.get()) {
                val bytes = entry.pipe.readStdout()
                if (bytes == null) { parser.finish(); break }
                for (frame in parser.append(bytes)) {
                    val size = frame.toByteArray(Charsets.UTF_8).size
                    check(entry.queuedBytes.addAndGet(size.toLong()) <= 512 * 1024 && entry.frames.trySend(frame to size).isSuccess) {
                        "stdio 待处理输出超过限制"
                    }
                }
            }
            entry.frames.close()
        } catch (_: Exception) { entry.frames.close(IllegalStateException("stdio 输出中断或超出协议限制")) }
        finally {
            entry.finished.set(true)
            // Keep already queued complete frames available until the caller receives them.
            withContext(NonCancellable + Dispatchers.IO) { entry.pipe.close() }
        }
    }

    private suspend fun find(owner: StdioOwner, id: String): Entry = lock.withLock {
        checkNotNull(entries[id]) { "本地MCP连接已结束，请重新连接" }.also {
            check(it.owner == owner && !it.stopped.get()) { "本地MCP连接不属于当前聊天、角色或配置" }
        }
    }
    override suspend fun send(owner: StdioOwner, id: String, frame: String, authorize: suspend () -> Unit) {
        require(frame.isNotBlank() && '\n' !in frame && '\r' !in frame)
        val bytes = (frame + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262_144)
        val entry = find(owner, id)
        try { entry.writeLock.withLock {
            authorize(); entry.authorize(); currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { entry.pipe.write(bytes) }
            entry.touched.set(System.nanoTime()); authorize()
        } } catch (error: Throwable) { withContext(NonCancellable) { retire(entry) }; throw error }
    }
    override suspend fun receive(owner: StdioOwner, id: String, authorize: suspend () -> Unit): String {
        val entry = find(owner, id)
        try { return entry.readLock.withLock {
            authorize(); entry.authorize(); entry.touched.set(System.nanoTime())
            val (text, size) = withTimeout(25_000) { entry.frames.receive() }
            entry.queuedBytes.addAndGet(-size.toLong()); entry.touched.set(System.nanoTime())
            authorize(); text
        } } catch (error: Throwable) { withContext(NonCancellable) { retire(entry) }; throw error }
    }
    override suspend fun close(owner: StdioOwner, id: String) = withContext(NonCancellable) {
        lock.withLock {
            val entry = entries[id] ?: return@withLock
            check(entry.owner == owner) { "不能关闭其他聊天或配置的本地MCP连接" }
            entries.remove(id); stop(entry)
        }
    }
    private suspend fun retire(entry: Entry) = withContext(NonCancellable) {
        lock.withLock { if (entries[entry.id] === entry) { entries.remove(entry.id); stop(entry) } }
    }
    private suspend fun stop(entry: Entry) {
        if (!entry.stopped.compareAndSet(false, true)) return
        entry.frames.cancel(); entry.tasks.forEach { it.cancel() }
        withContext(NonCancellable + Dispatchers.IO) { entry.pipe.close() }
    }
    override suspend fun cancelOwner(pluginId: String) = withContext(NonCancellable) {
        lock.withLock { for (entry in entries.values.filter { it.owner.pluginId == pluginId }) { entries.remove(entry.id); stop(entry) } }
    }
    suspend fun shutdown() = withContext(NonCancellable) {
        lock.withLock { closed = true; entries.values.toList().forEach { stop(it) }; entries.clear() }
    }
}

class StdioProcessPlugin : SolvitPlugin {
    override val id = "builtin.process.stdio"
    override val provides = setOf(StdioProcessServiceKey)
    override suspend fun enable(context: PluginContext) {
        val service = AndroidStdioProcesses(context.scope)
        context.own { service.shutdown() }
        context.register(StdioProcessServiceKey, service)
    }
}
