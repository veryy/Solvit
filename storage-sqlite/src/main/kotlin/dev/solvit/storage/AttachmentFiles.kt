package dev.solvit.storage

import dev.solvit.core.ChatAttachment
import dev.solvit.core.ChatAttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Store under filesDir, not cacheDir: reopening a conversation must retain its bytes. */
class AttachmentFiles(private val directory: File) : ChatAttachmentStore {
    private val lock = Mutex()
    private val root: File
    init {
        check(!Files.isSymbolicLink(directory.toPath())) { "附件目录无效" }
        check(directory.isDirectory || directory.mkdirs()) { "无法创建附件目录" }
        root = directory.canonicalFile
    }
    private fun session(sessionId: String, create: Boolean): File {
        require(sessionId.isNotBlank() && sessionId.length <= 4096) { "对话标识无效" }
        // Older stores can contain non-UUID session IDs. Never use those IDs as paths.
        val key = runCatching { UUID.fromString(sessionId).toString().takeIf { it == sessionId } }.getOrNull()
            ?: ("legacy-" + MessageDigest.getInstance("SHA-256").digest(sessionId.toByteArray(Charsets.UTF_8)).hex())
        check(directory.canonicalFile == root && !Files.isSymbolicLink(directory.toPath())) { "附件目录已改变" }
        val target = File(root, key)
        check(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile == target.absoluteFile) { "附件目录无效" }
        if (create) check(target.isDirectory || target.mkdir()) { "无法创建附件目录" }
        return target
    }
    private fun blob(sessionId: String, attachment: ChatAttachment): File {
        val folder = session(sessionId, false)
        val file = File(folder, attachment.id + ".blob")
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.canonicalFile.parentFile == folder) { "附件文件不存在或已改变" }
        return file
    }
    override suspend fun import(sessionId: String, name: String, mimeType: String, input: InputStream,
        authorize: suspend () -> Unit): ChatAttachment = withContext(Dispatchers.IO) {
        lock.withLock {
            authorize(); coroutineContext.ensureActive()
            val id = UUID.randomUUID().toString()
            // Validate metadata before creating a directory or reading an untrusted stream.
            ChatAttachment(id, name, mimeType, 0, "0".repeat(64))
            val folder = session(sessionId, true)
            val temporary = File(folder, "$id.partial")
            val target = File(folder, "$id.blob")
            check(temporary.createNewFile()) { "无法创建附件文件" }
            var installed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) continue
                        size += count
                        require(size <= ChatAttachment.MAX_ATTACHMENT_BYTES) { "附件超过大小限制" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
                authorize(); coroutineContext.ensureActive()
                check(session(sessionId, false) == folder) { "附件目录已改变" }
                Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
                installed = true
                authorize(); coroutineContext.ensureActive()
                ChatAttachment(id, name, mimeType, size, digest.digest().hex())
            } catch (failure: Throwable) {
                // Only remove this import's private files. Never delete a selected source.
                if (installed && target.canonicalFile.parentFile == folder) target.delete()
                throw failure
            } finally { temporary.delete() }
        }
    }
    override suspend fun read(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock {
            authorize(); coroutineContext.ensureActive()
            val file = blob(sessionId, attachment)
            check(file.length() == attachment.sizeBytes) { "附件内容已改变" }
            // Read at most the declared size + one byte even if another writer grows the file.
            val bytes = file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var size = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) continue
                    size += count
                    check(size <= attachment.sizeBytes) { "附件内容已改变" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size.toLong() == attachment.sizeBytes && MessageDigest.getInstance("SHA-256").digest(bytes).hex() == attachment.sha256) { "附件内容已改变" }
            authorize(); coroutineContext.ensureActive()
            check(blob(sessionId, attachment) == file) { "附件文件已改变" }
            bytes
        }
    }
    override suspend fun remove(sessionId: String, attachment: ChatAttachment,
        authorize: suspend () -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            authorize(); coroutineContext.ensureActive()
            val file = blob(sessionId, attachment)
            check(file.delete()) { "无法删除附件" }
        }
    }
    /** Called only on the SQLite IO transaction thread for a newly created branch. */
    internal fun copyForBranch(sourceSessionId: String, targetSessionId: String, attachments: List<ChatAttachment>) {
        if (attachments.isEmpty()) return
        val target = session(targetSessionId, false)
        check(!target.exists()) { "分支附件目录已存在" }
        check(target.mkdir()) { "无法创建分支附件目录" }
        try {
            attachments.distinctBy { it.id }.forEach { attachment ->
                val source = blob(sourceSessionId, attachment)
                val destination = File(target, attachment.id + ".blob")
                check(destination.createNewFile()) { "分支附件文件已存在" }
                source.inputStream().use { input -> FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) continue
                        copied += count
                        check(copied <= attachment.sizeBytes) { "分支附件内容已改变" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                check(Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS) && destination.length() == attachment.sizeBytes) { "分支附件内容已改变" }
                val hash = MessageDigest.getInstance("SHA-256")
                destination.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) continue
                        size += count
                        check(size <= attachment.sizeBytes) { "分支附件内容已改变" }
                        hash.update(buffer, 0, count)
                    }
                    check(size == attachment.sizeBytes && hash.digest().hex() == attachment.sha256) { "分支附件内容已改变" }
                }
            }
        } catch (failure: Throwable) {
            // This directory was exclusively created above; links are unlinked, not followed.
            target.listFiles().orEmpty().forEach { it.delete() }; target.delete()
            throw failure
        }
    }
    internal fun deleteSessionFiles(sessionId: String) {
        val folder = session(sessionId, false)
        if (!folder.exists()) return
        // The flat attachment directory never contains child directories.
        val files = folder.listFiles() ?: error("无法读取附件目录")
        check(files.all { !Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }) { "附件目录包含未知子目录" }
        files.forEach { check(it.delete()) { "无法删除附件文件" } }
        check(folder.delete()) { "无法删除附件目录" }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
