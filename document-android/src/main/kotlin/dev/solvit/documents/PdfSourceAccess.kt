package dev.solvit.documents

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import dev.solvit.core.MAX_WORKSPACE_IMPORT_BYTES
import dev.solvit.core.ModelException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

/** Shared private source handling for chat documents and page-owned workspace selections. */
internal class PdfSourceAccess(private val cache: File) {
    private val reads = Mutex()
    suspend fun <T> read(size: Long, sha256: String, authorize: suspend () -> Unit,
        bytes: suspend () -> ByteArray, action: suspend (File, File) -> T): T = reads.withLock {
        withContext(Dispatchers.IO) {
            require(size in 0..MAX_WORKSPACE_IMPORT_BYTES.toLong() && sha256.matches(Regex("[a-f0-9]{64}")))
            authorize(); currentCoroutineContext().ensureActive()
            check(!Files.isSymbolicLink(cache.toPath()) &&
                cache.canonicalFile == File(cache.absoluteFile.parentFile.canonicalFile, cache.name)) { "文档缓存目录已改变" }
            check(cache.mkdirs() || cache.isDirectory) { "无法创建文档临时目录" }
            val directory = File(cache, UUID.randomUUID().toString())
            check(directory.mkdir()) { "无法创建文档临时目录" }
            try {
                val source = File(directory, "source.pdf")
                val content = bytes()
                try {
                    require(content.size.toLong() == size && content.size <= MAX_WORKSPACE_IMPORT_BYTES) { "文档大小与来源记录不一致" }
                    val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
                    require(hash == sha256) { "文档内容校验失败" }
                    require(content.copyOfRange(0, min(content.size, 1024)).toString(Charsets.US_ASCII).contains("%PDF-")) { "文件不是有效PDF" }
                    source.outputStream().use { output ->
                        var position = 0
                        while (position < content.size) {
                            authorize(); currentCoroutineContext().ensureActive()
                            val count = min(64 * 1024, content.size - position)
                            output.write(content, position, count); position += count
                        }
                    }
                } finally { content.fill(0) }
                authorize(); currentCoroutineContext().ensureActive()
                action(directory, source)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: InvalidPasswordException) { throw ModelException("PDF受密码保护，请先导出未加密副本") }
            catch (failure: ModelException) { throw failure }
            catch (_: IOException) { throw ModelException("PDF损坏或无法读取，请检查原文件") }
            finally { directory.deleteRecursively() }
        }
    }
}

internal fun readablePdf(directory: File, source: File): PDDocument {
    val document = PDDocument.load(source,
        MemoryUsageSetting.setupMixed(8L * 1024 * 1024, 64L * 1024 * 1024).setTempDir(directory))
    try {
        if (document.isEncrypted) throw ModelException("PDF受密码保护，请先导出未加密副本")
        if (!document.currentAccessPermission.canExtractContent()) throw ModelException("PDF禁止文字提取，请使用允许读取的副本")
        return document
    } catch (failure: Throwable) {
        try { document.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    }
}
