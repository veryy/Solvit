package dev.solvit.environment

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import dev.solvit.core.*
import dev.solvit.linux.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Native I/O provider; human controls are exposed only by a separately authorized plugin page. */
class AndroidLinuxEnvironment(context: Context, private val scope: CoroutineScope) : LinuxEnvironmentService {
    private val app = context.applicationContext
    private val native = File(app.applicationInfo.nativeLibraryDir)
    private val source = Build.SUPPORTED_ABIS.firstNotNullOfOrNull(UbuntuCatalog::forAbi)
    private val supported = source != null && File(native, "libsolvit_proot.so").isFile && File(native, "libsolvit_loader.so").isFile
    private val temporary = File(app.cacheDir, "linux").apply { check(mkdirs() || isDirectory) }
    private val workspace = File(app.filesDir, "linux-install-workspace").apply { check(mkdirs() || isDirectory) }
    private val launcher = LinuxLaunch(native, temporary)
    private val engine = ShellJobEngine(scope, ShellLauncher(ShellPaths("/system/bin/sh", "/system/bin/toybox", setsidArguments = listOf("setsid"))), workspace)
    private val http = OkHttpClient.Builder().followSslRedirects(false).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()
    private val installer = source?.let { RootfsInstallation(File(app.filesDir, "linux-environments"), it,
        RootfsDownload { catalog, target, progress -> download(catalog, target, progress) }) }
    private val initiallyInstalled = installer?.activeRoot() != null
    private val mutableState = MutableStateFlow(LinuxEnvironmentState(supported, initiallyInstalled, if (initiallyInstalled) "READY" else "IDLE"))
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var operation: Job? = null
    @Volatile private var installingRootfs = false
    init {
        installer?.let { manager -> scope.launch {
            manager.state.collect { next ->
                // Rootfs replay must not mark a later software job as already ready.
                if (!installingRootfs || next.stage !in setOf(InstallationStage.DOWNLOADING, InstallationStage.EXTRACTING, InstallationStage.INITIALIZING)) return@collect
                if (manager.state.value != next) return@collect
                mutableState.update { old -> old.copy(installed = manager.activeRoot() != null,
                    stage = next.stage.name, bytes = next.bytes, total = next.total, failure = next.details) }
            }
        } }
    }

    private suspend fun download(catalog: RootfsSource, target: File, progress: (Long, Long?) -> Unit) = coroutineScope {
        val call = http.newCall(Request.Builder().url(catalog.url).build())
        val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    check(response.isSuccessful) { "运行环境下载失败：HTTP ${response.code}" }
                    check(response.request.url.isHttps) { "运行环境下载必须使用 HTTPS" }
                    val body = checkNotNull(response.body)
                    val total = body.contentLength().takeIf { it > 0 }
                    val limit = 128L * 1024 * 1024
                    check(total == null || total <= limit) { "运行环境归档超过下载容量限制" }
                    body.byteStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var written = 0L
                        while (true) {
                            ensureActive(); val count = input.read(buffer); if (count < 0) break
                            check(count <= limit - written) { "运行环境归档超过下载容量限制" }
                            output.write(buffer, 0, count); written += count; progress(written, total)
                        }
                        check(total == null || written == total) { "运行环境下载不完整" }
                    } }
                }
            }
        } finally { cancelWatcher.cancelAndJoin() }
    }

    private fun plan(root: File, command: String): HostProcessPlan = launcher.command(root, workspace, command).let {
        HostProcessPlan(it.arguments, it.environment)
    }
    private fun configureDns(root: File) {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val dns = connectivity.activeNetwork?.let(connectivity::getLinkProperties)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }.filter { '%' !in it && it.matches(Regex("[0-9a-fA-F:.]+")) }
        check(dns.isNotEmpty()) { "未获取到当前网络的 DNS，请连接网络后重试" }
        // The catalog has a regular resolv.conf; never follow a guest absolute symlink on the host.
        val target = File(root, "etc/resolv.conf").toPath()
        check(java.nio.file.Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        target.toFile().writeText(dns.joinToString("\n", postfix = "\n") { "nameserver $it" })
    }

    private suspend fun run(root: File, command: String) {
        configureDns(root)
        val started = engine.start("builtin.linux", null, ShellRequest(command, timeoutMs = 600_000),
            ExecutionMode.APP, plan = plan(root, command))
        try {
            val ended = engine.jobs.map { jobs -> jobs.first { it.id == started.id } }
                .onEach { job -> mutableState.update { it.copy(output = job.output.joinToString("") { chunk -> chunk.text }.takeLast(16_000)) } }
                .first { it.stage !in setOf(ShellStage.STARTING, ShellStage.RUNNING) }
            check(ended.stage == ShellStage.COMPLETED && ended.exitCode == 0) {
                "Linux 命令${ended.stage}，退出码 ${ended.exitCode ?: "未知"}；${ended.failure.orEmpty()}"
            }
        } finally { withContext(NonCancellable) { engine.cancel("builtin.linux", null, started.id) } }
    }

    private suspend fun begin(stage: String, rootInstallation: Boolean = false, action: suspend () -> Unit): Unit = mutex.withLock {
        check(supported) { "当前架构尚未提供 Linux 运行环境" }
        check(operation?.isActive != true) { "已有环境安装任务正在进行" }
        installingRootfs = rootInstallation
        mutableState.update { it.copy(stage = stage, output = "", failure = null) }
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try { action(); mutableState.update { it.copy(installed = installer?.activeRoot() != null, stage = "READY") } }
            catch (e: CancellationException) { mutableState.update { it.copy(stage = "CANCELLED") }; throw e }
            catch (e: Exception) { mutableState.update { it.copy(stage = "FAILED", failure = e.message?.take(4096) ?: "Linux 环境操作失败") } }
            finally { installingRootfs = false }
        }
        operation = task
        task.invokeOnCompletion { error ->
            if (operation === task && error is CancellationException) mutableState.update {
                if (it.stage in setOf("DOWNLOADING", "EXTRACTING", "INITIALIZING", "INSTALLING_SOFTWARE")) it.copy(stage = "CANCELLED") else it
            }
        }
        task.start()
        Unit
    }
    private suspend fun installNode(root: File) {
        val catalog = checkNotNull(Build.SUPPORTED_ABIS.firstNotNullOfOrNull(NodeCatalog::forAbi))
        // A host-owned download is checksum-verified before extraction inside the guest.
        val archive = File(workspace, ".solvit-node-${java.util.UUID.randomUUID()}.tar.xz")
        try {
            download(catalog, archive) { bytes, total -> mutableState.update { it.copy(bytes = bytes, total = total) } }
            withContext(Dispatchers.IO) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                archive.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive(); val count = input.read(buffer); if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == catalog.sha256) { "Node.js 归档校验失败" }
            }
            // Publish bin links only after the versioned installation executes successfully.
            run(root, """
                set -eu
                mkdir -p /opt /usr/local/bin
                if [ ! -f /opt/${catalog.archivePrefix}/.solvit-ready ]; then
                    rm -rf /opt/${catalog.archivePrefix}
                    tar -xJf /workspace/${archive.name} -C /opt --no-same-owner
                    test "$(/opt/${catalog.archivePrefix}/bin/node --version)" = "v${NodeCatalog.VERSION}"
                    /opt/${catalog.archivePrefix}/bin/node /opt/${catalog.archivePrefix}/lib/node_modules/npm/bin/npm-cli.js --version
                    touch /opt/${catalog.archivePrefix}/.solvit-ready
                fi
                for executable in node npm npx corepack; do
                    if [ -e /opt/${catalog.archivePrefix}/bin/${'$'}executable ]; then
                        ln -sfn /opt/${catalog.archivePrefix}/bin/${'$'}executable /usr/local/bin/${'$'}executable
                    fi
                done
                node --version && npm --version
            """.trimIndent())
        } finally { withContext(NonCancellable + Dispatchers.IO) { archive.delete() } }
    }
    override suspend fun install() = begin("DOWNLOADING", rootInstallation = true) {
        checkNotNull(installer).install { root ->
            run(root, "apt-get -o Acquire::Retries=2 update && apt-get -o Acquire::Retries=2 install -y --no-install-recommends python3 python3-venv ca-certificates xz-utils libstdc++6 && python3 --version")
            installNode(root)
        }
    }
    override suspend fun installSoftware(software: LinuxSoftware) = begin("INSTALLING_SOFTWARE") {
        val root = checkNotNull(installer?.activeRoot()) { "请先安装 Ubuntu 环境" }
        val packages = when (software) {
            LinuxSoftware.PYTHON -> "python3 python3-venv python3-pip"
            LinuxSoftware.NODE -> "ca-certificates xz-utils libstdc++6"
            LinuxSoftware.JAVA -> "openjdk-17-jdk-headless"
        }
        run(root, "dpkg --configure -a && apt-get -f install -y --no-install-recommends && apt-get -o Acquire::Retries=2 update && apt-get -o Acquire::Retries=2 install -y --no-install-recommends $packages")
        when (software) {
            LinuxSoftware.PYTHON -> run(root, "python3 --version && python3 -m pip --version")
            LinuxSoftware.NODE -> installNode(root)
            LinuxSoftware.JAVA -> run(root, "java -version")
        }
    }
    override suspend fun cancel() = mutex.withLock { operation?.cancelAndJoin(); Unit }
    private fun root() = checkNotNull(installer?.activeRoot()) { "请先在环境插件中完成 Ubuntu 安装" }
    override fun command(workspaceDirectory: String, command: String, cwd: String): HostProcessPlan =
        launcher.command(root(), File(workspaceDirectory), command, File(cwd)).let { HostProcessPlan(it.arguments, it.environment) }
    override fun terminal(workspaceDirectory: String): HostProcessPlan =
        launcher.terminal(root(), File(workspaceDirectory)).let { HostProcessPlan(it.arguments, it.environment) }
    suspend fun close() { cancel(); engine.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}

class LinuxEnvironmentPlugin(private val androidContext: Context) : SolvitPlugin {
    override val id = "builtin.linux"
    override val provides = setOf(LinuxEnvironmentServiceKey)
    override suspend fun enable(context: PluginContext) {
        val service = AndroidLinuxEnvironment(androidContext, context.scope)
        context.own { service.close() }
        context.register(LinuxEnvironmentServiceKey, service)
    }
}
