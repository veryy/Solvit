package dev.solvit.workspace

import dev.solvit.core.MAX_WORKSPACE_IMPORT_BYTES
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption.READ
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.UUID

/** Provenance lives outside the workspace and outlives the replacement transaction.
 * A matching filename alone never makes a file a managed backup. These observations do
 * not lock Shell writers, prove unchanged contents, or authorize destructive operations. */
internal class WorkspaceImportBackups(private val root: File, private val ledger: File) {
    internal data class Record(val id: String, val path: String, val backupPath: String,
        val originalKey: String, val originalBytes: Long, val originalSha256: String, val createdAt: Long)
    internal data class Observation(val record: Record, val status: String, val currentBytes: Long?, val pending: Boolean)
    internal data class Inventory(val observations: List<Observation>, val logicalBytes: Long,
        val incomplete: Boolean, val unreadableRecords: Int)

    companion object {
        private const val HEADER = "SOLVIT_IMPORT_BACKUP_1"
        private const val MAX_RECORDS = 20_000
        private val ids = Regex("[a-f0-9-]{36}")
        private val lock = Any()
    }
    private fun attributes(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: java.nio.file.NoSuchFileException) { null }
    private fun key(file: File): String? = attributes(file)?.takeIf { it.isRegularFile }?.fileKey()?.toString()
    private fun directory(create: Boolean) {
        if (create) check(ledger.mkdirs() || ledger.isDirectory)
        check(ledger.canonicalFile == ledger.absoluteFile && Files.isDirectory(ledger.toPath(), NOFOLLOW_LINKS)) {
            "备份登记目录无效"
        }
    }
    private fun recordFile(id: String): File {
        require(ids.matches(id)); return File(ledger, "$id.backup")
    }
    private fun relative(value: String): File {
        val parts = value.split('/')
        require(value.length in 1..1024 && !File(value).isAbsolute &&
            parts.none { it.isEmpty() || it in setOf(".", "..", ".git") || it.any(Char::isISOControl) })
        return File(root, value)
    }
    private fun validate(record: Record) {
        require(ids.matches(record.id) && record.originalBytes in 0..MAX_WORKSPACE_IMPORT_BYTES.toLong() &&
            record.originalSha256.matches(Regex("[a-f0-9]{64}")) && record.originalKey.length in 1..1024)
        val target = relative(record.path); val backup = relative(record.backupPath)
        require(target != backup && target.parentFile == backup.parentFile &&
            !target.name.startsWith(".solvit-import-") && backup.name.matches(Regex("solvit-backup-[a-f0-9-]{36}")))
    }
    private fun safeBackup(record: Record): File {
        validate(record)
        check(root.canonicalFile == root.absoluteFile && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        val backup = relative(record.backupPath)
        check(backup.parentFile!!.canonicalFile == backup.parentFile!!.absoluteFile &&
            Files.isDirectory(backup.parentFile!!.toPath(), NOFOLLOW_LINKS)) { "备份所在目录已变化" }
        return backup
    }
    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    private fun read(file: File): Record {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 16_384)
        val text = Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(16_385)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded metadata */ }
            require(buffer.position() <= 16_384)
            buffer.flip(); Charsets.UTF_8.newDecoder().decode(buffer).toString()
        }
        require(text.endsWith('\n'))
        val lines = text.removeSuffix("\n").split('\n')
        require(lines.size == 7 && lines[0] == HEADER)
        return Record(file.name.removeSuffix(".backup"), decode(lines[1]), decode(lines[2]), decode(lines[3]),
            lines[4].toLong(), lines[5], lines[6].toLong()).also(::validate)
    }
    /** Must finish before the original name is moved. The digest is the reviewed original,
     * not a claim that an open Shell descriptor can no longer change the retained inode. */
    fun register(id: String, target: File, backup: File, originalKey: String, sha256: String, reviewedBytes: Long) = synchronized(lock) {
        val record = Record(id, target.relativeTo(root).invariantSeparatorsPath,
            backup.relativeTo(root).invariantSeparatorsPath, originalKey, reviewedBytes, sha256, System.currentTimeMillis())
        validate(record); safeBackup(record); directory(create = true)
        check(target.canonicalFile == target.absoluteFile && key(target) == originalKey && target.length() == reviewedBytes) { "原文件身份已变化，尚未移动" }
        val destination = recordFile(id)
        check(!Files.exists(destination.toPath(), NOFOLLOW_LINKS)) { "备份登记已存在" }
        val temporary = File(ledger, "${UUID.randomUUID()}.tmp")
        check(temporary.createNewFile())
        try {
            FileOutputStream(temporary).use { out ->
                out.write(listOf(HEADER, encode(record.path), encode(record.backupPath), encode(originalKey),
                    record.originalBytes.toString(), sha256, record.createdAt.toString()).joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    /** Only removes metadata after rollback actually restored the original. */
    fun forgetIfRestored(id: String, target: File, backup: File, originalKey: String) = synchronized(lock) {
        if (!Files.exists(ledger.toPath(), NOFOLLOW_LINKS)) return@synchronized
        directory(create = false)
        val file = recordFile(id)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return@synchronized
        val record = read(file)
        check(record.path == target.relativeTo(root).invariantSeparatorsPath &&
            record.backupPath == backup.relativeTo(root).invariantSeparatorsPath && record.originalKey == originalKey)
        safeBackup(record)
        if (!Files.exists(backup.toPath(), NOFOLLOW_LINKS) && key(target) == originalKey) Files.delete(file.toPath())
    }
    internal fun validateCleanupRecord(record:Record) = validate(record)
    internal fun registeredOrNull(id:String):Record? = synchronized(lock) {
        directory(create=false)
        val file=recordFile(id)
        if(Files.exists(file.toPath(),NOFOLLOW_LINKS))read(file).also { safeBackup(it) } else null
    }
    internal fun registered(id:String):Record = synchronized(lock) {
        directory(create=false)
        read(recordFile(id)).also { safeBackup(it) }
    }
    internal fun forgetDeleted(record:Record) = synchronized(lock) {
        directory(create=false)
        val file=recordFile(record.id)
        if(Files.exists(file.toPath(),NOFOLLOW_LINKS)) {
            check(read(file)==record) { "备份登记已变化" }
            Files.delete(file.toPath())
        }
    }
    internal fun location(record:Record):File = safeBackup(record)
    private fun cleanupPending(id:String)=Files.exists(File(ledger,"backup-cleanup/$id.intent").toPath(),NOFOLLOW_LINKS)
    /** Read one currently owned backup for an explicit review/copy. Never follows a new
     * inode at the old name, never reads an active replacement, and never removes a file. */
    fun readOwned(id: String, checkpoint: () -> Unit): Pair<Record, ByteArray> {
        checkpoint(); directory(create = false)
        val record = read(recordFile(id))
        val pending = File(ledger, "${record.id}.transaction")
        check(!Files.exists(pending.toPath(), NOFOLLOW_LINKS) && !cleanupPending(id)) { "该备份仍有未完成事务，请等待恢复或导入结束" }
        val source = safeBackup(record)
        val attrs = checkNotNull(attributes(source)) { "备份文件已不存在" }
        check(attrs.isRegularFile && attrs.fileKey()?.toString() == record.originalKey) { "备份身份已变化，不能作为登记备份读取" }
        require(attrs.size() in 0..MAX_WORKSPACE_IMPORT_BYTES.toLong()) { "备份超过20MiB，请使用文件管理或Shell处理" }
        val bytes = ByteArray(attrs.size().toInt())
        try {
            Files.newByteChannel(source.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    checkpoint(); check(channel.read(buffer) >= 0) { "备份内容已变化，请重新审阅" }
                }
                checkpoint()
                check(channel.read(ByteBuffer.allocate(1)) == -1) { "备份内容已变化，请重新审阅" }
            }
            checkpoint()
            val after = attributes(safeBackup(record))
            check(after?.isRegularFile == true && after.fileKey()?.toString() == record.originalKey &&
                after.size() == bytes.size.toLong() && !Files.exists(pending.toPath(), NOFOLLOW_LINKS) && !cleanupPending(id)) {
                "备份内容或身份已变化，请重新审阅"
            }
            return record to bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
    fun inventory(checkpoint: () -> Unit = {}): Inventory = synchronized(lock) {
        checkpoint()
        if (!Files.exists(ledger.toPath(), NOFOLLOW_LINKS)) return@synchronized Inventory(emptyList(), 0, false, 0)
        directory(create = false)
        val result = mutableListOf<Observation>()
        var visited = 0; var unreadable = 0; var incomplete = false; var logicalBytes = 0L
        // Space is the sum of unique retained inodes' logical lengths, not allocated blocks
        // or a prediction of how many bytes deletion would free (hardlinks/open FDs matter).
        val counted = mutableSetOf<String>()
        Files.newDirectoryStream(ledger.toPath()).use { entries ->
            for (path in entries) {
                checkpoint()
                if (++visited > MAX_RECORDS) { incomplete = true; break }
                if (!path.fileName.toString().matches(Regex("[a-f0-9-]{36}\\.backup"))) continue
                val record = try { read(path.toFile()) } catch (_: Exception) { unreadable++; continue }
                val pending = Files.exists(File(ledger, "${record.id}.transaction").toPath(), NOFOLLOW_LINKS) || cleanupPending(record.id)
                val observation = try {
                    val attrs = attributes(safeBackup(record))
                    when {
                        attrs == null -> Observation(record, "MISSING", null, pending)
                        !attrs.isRegularFile || attrs.fileKey()?.toString() != record.originalKey -> Observation(record, "CHANGED_IDENTITY", null, pending)
                        else -> Observation(record, "PRESENT", attrs.size(), pending)
                    }
                } catch (_: Exception) { Observation(record, "UNAVAILABLE", null, pending) }
                if (observation.status == "PRESENT" && counted.add(record.originalKey)) {
                    logicalBytes = Math.addExact(logicalBytes, checkNotNull(observation.currentBytes))
                }
                result += observation
            }
        }
        Inventory(result.sortedWith(compareByDescending<Observation> { it.record.createdAt }.thenBy { it.record.id }),
            logicalBytes, incomplete, unreadable)
    }
}
