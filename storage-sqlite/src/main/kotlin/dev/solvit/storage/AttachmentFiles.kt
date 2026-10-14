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
        require(UUID.fromString(sessionId).toString() == sessionId) { "对话标识无效" }
        check(directory.canonicalFile == root && !Files.isSymbolicLink(directory.toPath())) { "附件目录已改变" }
        val target = File(root, sessionId)
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
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
