package dev.solvit.system

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class ShellPaths(val shell: String, val setsid: String, val rootPrefix: List<String> = listOf("su", "-c"),
    val setsidArguments: List<String> = emptyList())
fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

/** Dedicated process group; closing stdin stops the watchdog and its group. */
class ShellLauncher(private val paths: ShellPaths) {
    private val script = """
        exec 3<&0
        exec </dev/null
        (
          trap '' TERM HUP INT
          read -r ignored <&3
          kill -TERM -${'$'}${'$'} 2>/dev/null
          sleep 0.2
          kill -KILL -${'$'}${'$'} 2>/dev/null
        ) &
        cd -- "${'$'}3" || exit 125
        "${'$'}1" -c "${'$'}2" 3<&-
        exit ${'$'}?
    """.trimIndent()

    fun launch(request: ShellRequest, mode: ExecutionMode, cwd: File, workspace: File,
        plan: HostProcessPlan? = null): Process {
        check(plan == null || mode == ExecutionMode.APP) { "Linux 用户环境仅使用普通应用身份" }
        val selectedScript = if (plan == null) script else script.replace("\"${'$'}1\" -c \"${'$'}2\" 3<&-", "shift 3\n\"${'$'}@\" 3<&-")
        val args = listOf(paths.setsid) + paths.setsidArguments + listOf(paths.shell, "-c", selectedScript, "solvit-job", paths.shell, request.command, cwd.path) + plan?.arguments.orEmpty()
        val command = if (mode == ExecutionMode.ROOT) paths.rootPrefix + args.joinToString(" ", transform = ::shellQuote) else args
        return ProcessBuilder(command).directory(workspace).apply {
            plan?.environment?.forEach { (key, value) ->
                if (value == null) environment().remove(key) else environment()[key] = value
            }
        }.start()
    }
}

/** Job lifetime belongs to the enabled plugin, not to the JS evaluation. */
class ShellJobEngine(
    private val scope: CoroutineScope,
    private val launcher: ShellLauncher,
    private val workspace: File,
    private val outputLimit: Int = 64 * 1024,
) {
    private class Record(var snapshot: ShellJob) {
        lateinit var task: Job
        @Volatile var process: Process? = null
        val cleaned = AtomicBoolean(false)
    }
    private val monitor = Any()
    private val records = linkedMapOf<String, Record>()
    private val mutableJobs = MutableStateFlow<List<ShellJob>>(emptyList())
    val jobs = mutableJobs.asStateFlow()
    private fun publish() { mutableJobs.value = records.values.map { it.snapshot }.reversed() }
    private fun update(record: Record, change: (ShellJob) -> ShellJob) = synchronized(monitor) {
        record.snapshot = change(record.snapshot); publish()
    }
    private fun active(stage: ShellStage) = stage in setOf(ShellStage.STARTING, ShellStage.RUNNING)

    fun start(owner: String, roleId: String?, request: ShellRequest, mode: ExecutionMode,
        workspaceDirectory: File = workspace, workspaceId: String? = null, shellSessionId: String? = null,
        plan: HostProcessPlan? = null): ShellJob {
        request.validate()
        check(scope.isActive) { "系统执行服务已关闭" }
        val base = workspaceDirectory.canonicalFile.also { check(it.isDirectory) { "工作区无法打开" } }
        val cwd = if (request.cwd.isEmpty()) base else File(request.cwd).let { if (it.isAbsolute) it else File(base, request.cwd) }.canonicalFile
        if (mode == ExecutionMode.APP) {
            require(cwd == base || cwd.path.startsWith(base.path + File.separator)) { "普通模式工作目录需在 Solvit 工作区内" }
            require(cwd.isDirectory) { "工作目录不存在" }
        }
        val record = Record(ShellJob(UUID.randomUUID().toString(), owner, roleId, request.command, cwd.path, mode, ShellStage.STARTING,
            workspaceId = workspaceId, shellSessionId = shellSessionId))
        synchronized(monitor) {
            check(records.values.count { active(it.snapshot.stage) } < 8) { "运行中的 Shell 任务已达上限" }
            while (records.size >= 32) {
                val expired = records.entries.firstOrNull { !active(it.value.snapshot.stage) } ?: error("任务数量已达上限")
                records.remove(expired.key)
            }
            record.task = scope.launch(start = CoroutineStart.LAZY) { run(record, request, mode, cwd, base, plan) }
            record.task.invokeOnCompletion { cause ->
                if (cause is CancellationException) update(record) {
                    if (active(it.stage)) it.copy(stage = ShellStage.CANCELLED) else it
                }
            }
            records[record.snapshot.id] = record
            publish()
        }
        record.task.start()
        return synchronized(monitor) { record.snapshot }
    }

    private suspend fun run(record: Record, request: ShellRequest, mode: ExecutionMode, cwd: File, base: File, plan: HostProcessPlan?) {
        try {
            withTimeout(request.timeoutMs) {
                withContext(Dispatchers.IO) {
                    ensureActive()
                    // Store inside the IO block: cancellation cannot discard a
                    // newly started process before finally has its reference.
                    record.process = launcher.launch(request, mode, cwd, base, plan)
                }
                val process = record.process!!
                update(record) { it.copy(stage = ShellStage.RUNNING) }
                coroutineScope {
                    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { cleanup(record) }
                    }
                    try {
                        val stdout = launch(Dispatchers.IO) { pump(record, process, process.inputStream, "stdout") }
                        val stderr = launch(Dispatchers.IO) { pump(record, process, process.errorStream, "stderr") }
                        val code = withContext(Dispatchers.IO) { process.waitFor() }
                        withContext(Dispatchers.IO) { process.outputStream.close() }
                        stdout.join(); stderr.join()
                        update(record) { it.copy(stage = if (code == 0) ShellStage.COMPLETED else ShellStage.FAILED, exitCode = code) }
                    } finally { watcher.cancelAndJoin() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            update(record) { it.copy(stage = ShellStage.TIMED_OUT, failure = "Shell 任务超时") }
        } catch (e: CancellationException) {
            update(record) { it.copy(stage = ShellStage.CANCELLED) }; throw e
        } catch (e: Exception) {
            update(record) { it.copy(stage = ShellStage.FAILED, failure = "Shell 进程启动或执行失败") }
        } finally { cleanup(record) }
    }

    private suspend fun pump(record: Record, process: Process, stream: InputStream, kind: String) {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        var carry = ByteArray(0)
        while (true) {
            currentCoroutineContext().ensureActive()
            val available = stream.available()
            val done = available == 0 && !process.isAlive
            if (available == 0 && !done) { delay(20); continue }
            val bytes = ByteArray(minOf(available, 4096))
            val count = if (bytes.isEmpty()) 0 else stream.read(bytes)
            val input = ByteBuffer.wrap(carry + bytes.take(maxOf(0, count)).toByteArray())
            val chars = CharBuffer.allocate(input.remaining() + 2)
            decoder.decode(input, chars, done)
            carry = ByteArray(input.remaining()).also { input.get(it) }
            if (done) decoder.flush(chars)
            chars.flip()
            val text = chars.toString()
            if (text.isNotEmpty()) append(record, kind, text)
            if (done) return
        }
    }
    private fun append(record: Record, kind: String, text: String) = update(record) { snapshot ->
        val chunks = (snapshot.output + ShellOutput(snapshot.nextSequence, kind, text)).toMutableList()
        var bytes = chunks.sumOf { it.text.toByteArray(Charsets.UTF_8).size }
        var dropped = false
        while (chunks.size > 256 || bytes > outputLimit) {
            bytes -= chunks.removeAt(0).text.toByteArray(Charsets.UTF_8).size; dropped = true
        }
        snapshot.copy(output = chunks, nextSequence = snapshot.nextSequence + 1, truncated = snapshot.truncated || dropped)
    }
    private suspend fun cleanup(record: Record) = withContext(NonCancellable + Dispatchers.IO) {
        val process = record.process ?: return@withContext
        if (!record.cleaned.compareAndSet(false, true)) return@withContext
        // EOF activates the process-group watchdog for both app and root jobs.
        runCatching { process.outputStream.close() }
        if (!process.waitFor(750, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
        runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }
    }
    fun read(owner: String, roleId: String?, id: String, after: Long): ShellJob = synchronized(monitor) {
        require(after >= -1)
        val job = records[id]?.snapshot ?: error("Shell 任务不存在或已从历史清理")
        check(job.owner == owner && job.roleId == roleId) { "此任务不属于当前插件和角色" }
        job.copy(output = job.output.filter { it.sequence > after })
    }
    suspend fun cancel(owner: String, roleId: String?, id: String, userInitiated: Boolean = false) {
        val record = synchronized(monitor) {
            val r = records[id] ?: error("Shell 任务不存在")
            check(r.snapshot.owner == owner && (userInitiated || r.snapshot.roleId == roleId)) { "此任务不属于当前插件和角色" }; r
        }
        record.task.cancelAndJoin()
    }
    suspend fun cancelOwner(owner: String) {
        val tasks = synchronized(monitor) { records.values.filter { it.snapshot.owner == owner }.map { it.task } }
        tasks.forEach { it.cancel() }; tasks.joinAll()
    }
    suspend fun close() {
        val tasks = synchronized(monitor) { records.values.map { it.task } }
        tasks.forEach { it.cancel() }; tasks.joinAll()
    }
}
