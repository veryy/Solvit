package dev.solvit.linux

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** Installs only into a new private staging directory; never modifies an active rootfs. */
class RootfsArchive(private val maxBytes: Long = 512L * 1024 * 1024, private val maxEntries: Int = 100_000) {
    data class Progress(val entries: Int, val bytes: Long)
    suspend fun extract(archive: File, expectedSha256: String, prefix: String, destination: File,
        progress: (Progress) -> Unit = {}) {
        require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
        require(prefix.matches(Regex("[a-zA-Z0-9_-]+")))
        require(archive.isFile && archive.length() in 1..128L * 1024 * 1024)
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { context.ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expectedSha256) { "Linux 归档校验失败" }
        val root = destination.toPath().toAbsolutePath().normalize()
        check(!Files.exists(root, NOFOLLOW_LINKS)) { "安装目录已存在" }
        Files.createDirectory(root)
        var succeeded = false
        try {
            val links = mutableListOf<Pair<Path, Path>>()
            val seen = hashSetOf<String>()
            var entries = 0; var written = 0L
            fun child(name: String): Path {
                require(name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && '\u0000' !in name)
                require(name.split('/').none { it == ".." }) { "归档路径越界" }
                return root.resolve(name).normalize().also { require(it.startsWith(root) && it != root) }
            }
            XZInputStream(archive.inputStream().buffered(), 64 * 1024).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        context.ensureActive()
                        val entry = tar.nextEntry ?: break
                        check(++entries <= maxEntries) { "归档文件数量过多" }
                        val name = entry.name.removeSuffix("/")
                        if (name == prefix) { require(entry.isDirectory); continue }
                        require(name.startsWith("$prefix/")) { "归档根目录不匹配" }
                        val relative = name.removePrefix("$prefix/")
                        require(seen.add(relative)) { "重复归档路径" }
                        val path = child(relative)
                        Files.createDirectories(path.parent)
                        when {
                            entry.isDirectory -> Files.createDirectories(path)
                            entry.isSymbolicLink -> {
                                require(entry.linkName.isNotEmpty() && '\u0000' !in entry.linkName && '\\' !in entry.linkName)
                                val target = Path.of(entry.linkName)
                                // Absolute links are guest paths; create them only AFTER all writes.
                                if (!target.isAbsolute) require(path.parent.resolve(target).normalize().startsWith(root)) { "链接路径越界" }
                                links.add(path to target)
                            }
                            entry.isFile -> {
                                require(entry.size >= 0 && entry.size <= maxBytes - written) { "归档解包容量过大" }
                                check(!Files.exists(path, NOFOLLOW_LINKS))
                                Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                                    val buffer = ByteArray(64 * 1024); var remaining = entry.size
                                    while (remaining > 0) {
                                        context.ensureActive()
                                        val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                        check(count > 0) { "归档内容不完整" }
                                        output.write(buffer, 0, count); remaining -= count; written += count
                                    }
                                }
                                // Guest ownership/setuid is not copied into the Android host.
                                val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                                if (entry.mode and 0b001001001 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
                                Files.setPosixFilePermissions(path, permissions)
                            }
                            (entry.isCharacterDevice || entry.isBlockDevice) && relative.startsWith("dev/") -> Unit
                            else -> error("不支持的 Linux 归档条目")
                        }
                        progress(Progress(entries, written))
                    }
                }
            }
            // A link preceding a nested write cannot turn that write into a host escape.
            for ((path, target) in links) {
                context.ensureActive()
                check(!Files.exists(path, NOFOLLOW_LINKS)) { "链接与归档文件冲突" }
                Files.createSymbolicLink(path, target)
            }
            context.ensureActive()
            check(Files.isRegularFile(root.resolve("usr/bin/bash"), NOFOLLOW_LINKS) &&
                Files.isRegularFile(root.resolve("etc/os-release"), NOFOLLOW_LINKS)) { "归档缺少 Ubuntu 基础文件" }
            succeeded = true
        } finally {
            // Files.walk does not follow guest symlinks, including absolute links.
            if (!succeeded) Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
