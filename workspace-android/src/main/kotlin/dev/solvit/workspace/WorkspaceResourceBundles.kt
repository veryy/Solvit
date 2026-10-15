package dev.solvit.workspace

import dev.solvit.core.WorkspaceResourceBundle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Generic text-resource publication. Skill interpretation and execution stay in plugins/tools. */
internal object WorkspaceResourceBundles {
    private fun utf8(text: String): ByteArray {
        require('\u0000' !in text) { "资源包含无效文本" }
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    private fun validate(files: Map<String, String>): Map<String, ByteArray> {
        require(files.size in 1..40) { "资源需包含1至40个文本文件" }
        var total = 0
        return files.toSortedMap().mapValues { (path, text) ->
            val parts = path.split('/')
            require(path.length in 1..160 && parts.size <= 6 &&
                parts.none { it.isEmpty() || it == "." || it == ".." } &&
                path.none { it.isISOControl() || it == '\\' } &&
                files.keys.none { other -> other != path && other.startsWith("$path/") }) { "资源路径无效或冲突" }
            val bytes = utf8(text)
            require(bytes.size <= 32 * 1024) { "单个资源超过32KiB" }
            total += bytes.size
            require(total <= 220 * 1024) { "资源总量超过220KiB" }
            bytes
        }
    }
    private fun identity(file: File): Any {
        require(file.absoluteFile == file.canonicalFile && Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) { "资源目录身份无效" }
        return checkNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
    }
    private fun removePrivateTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
    suspend fun prepare(base: File, cache: File, name: String, files: Map<String, String>,
        publish: (File, File) -> Unit, authorize: suspend () -> Unit): WorkspaceResourceBundle {
        require(name.length in 1..64 && name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) { "资源名称无效" }
        val encoded = validate(files)
        authorize(); currentCoroutineContext().ensureActive()
        val rootIdentity = identity(base)
        check(cache.mkdirs() || cache.isDirectory)
        val cacheIdentity = identity(cache)
        // The OS may reclaim abandoned pre-publication cache after process death.
        // Never collect or replace previously published workspace copies here.
        check((cache.list()?.size ?: error("暂存目录无法读取")) < 128) { "资源暂存缓存已满" }
        val staging = Files.createTempDirectory(cache.toPath(), "resource-").toFile()
        val destination = File(base, ".solvit-resource-$name-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            for ((path, bytes) in encoded) {
                authorize(); currentCoroutineContext().ensureActive()
                check(identity(base) == rootIdentity && identity(cache) == cacheIdentity) { "工作区或暂存目录已变化" }
                val target = File(staging, path)
                check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                check(target.canonicalFile == target.absoluteFile)
                Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW).use { it.write(bytes) }
                val nameBytes = path.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(8).putInt(nameBytes.size).putInt(bytes.size).array())
                digest.update(nameBytes); digest.update(bytes)
            }
            authorize(); currentCoroutineContext().ensureActive()
            check(identity(base) == rootIdentity && identity(cache) == cacheIdentity) { "工作区或暂存目录已变化" }
            publish(staging, destination) // Production implementation pins parents and never overwrites.
            // Once published it belongs to the workspace. Cancellation must not delete user modifications.
            authorize(); currentCoroutineContext().ensureActive()
            check(identity(base) == rootIdentity) { "资源已发布，但工作区目录发生变化" }
            for ((path, bytes) in encoded) {
                val target = File(destination, path)
                check(target.absoluteFile == target.canonicalFile && Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS))
                val observed = ByteArray(bytes.size + 1)
                val size = target.inputStream().use { input ->
                    var size = 0
                    while (size < observed.size) {
                        val read = input.read(observed, size, observed.size - size)
                        if (read < 0) break
                        size += read
                    }
                    size
                }
                check(size == bytes.size && observed.copyOf(size).contentEquals(bytes)) { "已发布资源发生变化，请重新查看工作区" }
            }
            return WorkspaceResourceBundle(destination.name, encoded.size, encoded.values.sumOf { it.size.toLong() },
                digest.digest().joinToString("") { "%02x".format(it) })
        } finally {
            encoded.values.forEach { it.fill(0) }
            // Private staging only: published files are never deleted or silently refreshed.
            if (runCatching { identity(cache) == cacheIdentity }.getOrDefault(false)) removePrivateTree(staging.toPath())
        }
    }
}
