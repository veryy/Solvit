package dev.solvit.linux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

data class RootfsSource(val url: String, val sha256: String, val archivePrefix: String) {
    init {
        require(url.startsWith("https://") && sha256.matches(Regex("[a-f0-9]{64}")))
        require(archivePrefix.matches(Regex("[a-zA-Z0-9_-]+")))
    }
}

/** Catalog is host-owned. Plugin content never supplies a rootfs URL/hash or installation path. */
object UbuntuCatalog {
    fun forAbi(abi: String): RootfsSource? = when (abi) {
        "arm64-v8a" -> RootfsSource(
            "https://github.com/termux/proot-distro/releases/download/v4.17.3/ubuntu-noble-aarch64-pd-v4.17.3.tar.xz",
            "81ac0fb0d16ded12ab11cede62f67b875ff56f9fa1aa9eb786415c3ec5c477d2", "ubuntu-noble-aarch64")
        "x86_64" -> RootfsSource(
            "https://github.com/termux/proot-distro/releases/download/v4.17.3/ubuntu-noble-x86_64-pd-v4.17.3.tar.xz",
            "1680d024bf47d2414a36759af528ca3569a73b70682daa3d9693ba63157cb5a8", "ubuntu-noble-x86_64")
        else -> null
    }
}

enum class InstallationStage { IDLE, DOWNLOADING, EXTRACTING, INITIALIZING, READY, CANCELLED, FAILED }
data class InstallationStatus(val stage: InstallationStage, val bytes: Long = 0,
    val total: Long? = null, val details: String? = null, val hasActiveEnvironment: Boolean = false)
fun interface RootfsDownload {
    /** Must stream with cancellation and enforce the 128 MiB archive bound. */
    suspend fun download(source: RootfsSource, destination: File, progress: (Long, Long?) -> Unit)
}

/**
 * Never renames a rootfs: dpkg's PRoot .l2s backing files contain absolute host paths.
 * Activation replaces only a small pointer, after trusted host initialization succeeds.
 */
class RootfsInstallation(private val directory: File, private val source: RootfsSource,
    private val download: RootfsDownload, private val minimumFreeBytes: Long = 768L * 1024 * 1024) {
    private val mutex = Mutex()
    private val status = MutableStateFlow(InstallationStatus(InstallationStage.IDLE))
    val state = status.asStateFlow()
    private val pointer get() = File(directory, "active")
    private fun deleteTree(root: File) {
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
    /** Discard only our interrupted candidates; never follow guest links or remove a ready root. */
    private suspend fun recoverInterrupted() {
        val active = activeRoot()
        directory.listFiles().orEmpty().forEach { entry ->
            currentCoroutineContext().ensureActive()
            if (entry == active) return@forEach
            when {
                entry.name.matches(Regex("root-[a-f0-9-]{36}\\.(tar\\.xz|active)")) &&
                    !Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS) -> Files.deleteIfExists(entry.toPath())
                entry.name.matches(Regex("root-[a-f0-9-]{36}")) -> {
                    val marker = File(entry, ".solvit-ready").toPath()
                    if (!Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS) ||
                        !Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) != 64L) deleteTree(entry)
                }
            }
        }
    }

    /** Host use only; never serialize this private path to a plugin. */
    fun activeRoot(): File? {
        if (!Files.isRegularFile(pointer.toPath(), NOFOLLOW_LINKS) || pointer.length() > 256) return null
        val id = pointer.readText().trim()
        if (!id.matches(Regex("root-[a-f0-9-]{36}"))) return null
        val candidate = File(directory, id)
        if (!Files.isDirectory(candidate.toPath(), NOFOLLOW_LINKS)) return null
        val marker = File(candidate, ".solvit-ready")
        return candidate.takeIf {
            Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) && marker.length() == 64L &&
                marker.readText() == source.sha256 &&
                Files.isRegularFile(File(candidate, "usr/bin/bash").toPath(), NOFOLLOW_LINKS)
        }
    }

    suspend fun install(initialize: suspend (File) -> Unit): File = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建 Linux 私有目录" }
            check(!Files.isSymbolicLink(directory.toPath()))
            recoverInterrupted()
            val old = activeRoot()
            check(directory.usableSpace >= minimumFreeBytes) { "Linux 安装至少需要 ${minimumFreeBytes / (1024 * 1024)} MiB 可用空间" }
            val id = "root-${UUID.randomUUID()}"
            val root = File(directory, id)
            val archive = File(directory, "$id.tar.xz")
            val next = File(directory, "$id.active")
            var activated = false
            fun update(stage: InstallationStage, bytes: Long = 0, total: Long? = null, details: String? = null) {
                status.value = InstallationStatus(stage, bytes, total, details, old != null)
            }
            try {
                update(InstallationStage.DOWNLOADING)
                download.download(source, archive) { bytes, total -> update(InstallationStage.DOWNLOADING, bytes, total) }
                currentCoroutineContext().ensureActive()
                update(InstallationStage.EXTRACTING)
                RootfsArchive().extract(archive, source.sha256, source.archivePrefix, root) {
                    update(InstallationStage.EXTRACTING, it.bytes)
                }
                update(InstallationStage.INITIALIZING)
                initialize(root)
                currentCoroutineContext().ensureActive()
                // Mark and force file contents before publishing; an interrupted candidate stays inactive.
                File(root, ".solvit-ready").outputStream().use {
                    it.write(source.sha256.toByteArray(Charsets.UTF_8)); it.fd.sync()
                }
                next.outputStream().use { it.write(id.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(next.toPath(), pointer.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                activated = true
                status.value = InstallationStatus(InstallationStage.READY, hasActiveEnvironment = true)
                root
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                update(InstallationStage.CANCELLED); throw cancelled
            } catch (failure: Exception) {
                update(InstallationStage.FAILED, details = failure.message?.take(4096) ?: failure.javaClass.simpleName)
                throw failure
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    Files.deleteIfExists(archive.toPath()); Files.deleteIfExists(next.toPath())
                    if (!activated) deleteTree(root)
                }
            }
        }
    }
}
