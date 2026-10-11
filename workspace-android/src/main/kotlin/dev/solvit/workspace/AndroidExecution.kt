package dev.solvit.workspace

import android.content.Context
import android.os.Process
import dev.solvit.core.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit

interface RootAccess {
    fun detected(): Boolean
    suspend fun granted(): Boolean
}

/** No su process runs during passive detection. Authorization is always explicit. */
class AndroidRootAccess(private val su: String = "su", private val timeoutMs: Long = 15_000) : RootAccess {
    override fun detected(): Boolean = ((System.getenv("PATH") ?: "").split(File.pathSeparator) +
        listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/data/adb/magisk")).any {
        File(it, su).canExecute()
    }
    override suspend fun granted(): Boolean = withContext(Dispatchers.IO) {
        var process: java.lang.Process? = null
        try {
            ensureActive()
            process = ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start()
            process.outputStream.close()
            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                ensureActive()
                if (System.nanoTime() >= end) return@withContext false
            }
            ensureActive()
            // Read at most a small diagnostic amount; unexpected output is not a grant.
            val bytes = ByteArray(128)
            val count = process.inputStream.read(bytes)
            process.exitValue() == 0 && count in 1..127 && bytes.copyOf(count).toString(Charsets.UTF_8).trim() == "0"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { false }
        finally {
            process?.let { p ->
                if (p.isAlive) { p.destroy(); if (!p.waitFor(200, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
                runCatching { p.inputStream.close() }; runCatching { p.errorStream.close() }
            }
        }
    }
}

/** The mode is chosen by native UI; JavaScript cannot request elevation. */
class AndroidExecution(context: Context, private val engine: ShellJobEngine, private val rootAccess: RootAccess = AndroidRootAccess()) : SystemExecutionService {
    private val preferences = context.getSharedPreferences("system_execution", Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val state = MutableStateFlow(DeviceExecutionState(Process.myUid(), File("/system/bin/sh").canExecute(), rootAccess.detected()))
    override val device = state.asStateFlow()
    private val selections = MutableStateFlow(preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { text -> ExecutionMode.entries.firstOrNull { it.name == text }?.let { key to it } }
    }.toMap())
    override val modes = selections.asStateFlow()
    override val jobs = engine.jobs
    override suspend fun refresh() = gate.withLock {
        val detected = rootAccess.detected()
        state.value = state.value.copy(rootDetected = detected,
            rootGranted = state.value.rootGranted && detected,
            rootStatus = if (!detected) "未检测到 su；可手动请求确认" else state.value.rootStatus)
    }
    override suspend fun requestRoot() = gate.withLock { probe() }
    private suspend fun probe() {
        val granted = rootAccess.granted()
        state.value = state.value.copy(rootDetected = rootAccess.detected() || granted, rootGranted = granted,
            rootStatus = if (granted) "已验证 UID 0" else "Root 未授权、不可用或请求超时")
        if (!granted) selections.value.filterValues { it == ExecutionMode.ROOT }.keys.forEach { engine.cancelOwner(it) }
    }
    override suspend fun setMode(owner: String, mode: ExecutionMode) = gate.withLock {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,160}")))
        if (mode == ExecutionMode.ROOT) { probe(); check(state.value.rootGranted) { state.value.rootStatus } }
        // Cancel existing jobs before changing identity. Existing tool confirmations are invalidated.
        if ((selections.value[owner] ?: ExecutionMode.APP) != mode) engine.cancelOwner(owner)
        check(preferences.edit().putString(owner, mode.name).commit()) { "执行模式保存失败" }
        selections.value = selections.value + (owner to mode)
    }
    suspend fun <T> authorized(owner: String, action: suspend (ExecutionMode) -> T): T = gate.withLock {
        val mode = selections.value[owner] ?: ExecutionMode.APP
        val invocation = currentCoroutineContext()[ToolInvocationContext]
        if (invocation != null) {
            check(invocation.descriptor.pluginId == owner && "shell" in invocation.descriptor.capabilities) { "此工具没有 Shell 权限" }
            check(invocation.descriptor.executionMode == mode) { "执行模式已变化，请重新确认" }
            check(currentCoroutineContext()[AgentRoleContext]?.role?.allows(owner) != false)
        }
        if (mode == ExecutionMode.ROOT) { probe(); check(state.value.rootGranted) { state.value.rootStatus } }
        currentCoroutineContext().ensureActive()
        action(mode)
    }
    override suspend fun start(owner: String, request: ShellRequest, userInitiated: Boolean): ShellJob = authorized(owner) { mode ->
        if (userInitiated) check(owner == TERMINAL_OWNER) else ShellAuthorization.start(owner, request, mode)
        engine.start(owner, currentCoroutineContext()[AgentRoleContext]?.role?.id, request, mode)
    }
    override suspend fun read(owner: String, id: String, after: Long): ShellJob =
        engine.read(owner, currentCoroutineContext()[AgentRoleContext]?.role?.id, id, after)
    override suspend fun cancel(owner: String, id: String, userInitiated: Boolean) =
        engine.cancel(owner, currentCoroutineContext()[AgentRoleContext]?.role?.id, id, userInitiated)
    override suspend fun cancelOwner(owner: String) = engine.cancelOwner(owner)
}
