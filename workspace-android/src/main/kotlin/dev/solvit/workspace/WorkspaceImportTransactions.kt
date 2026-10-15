package dev.solvit.workspace

import dev.solvit.core.WorkspaceImportRecovery
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Write-ahead records live outside the Shell workspace. Recovery never replays an import.
 * This survives process death, not a promise of power-loss durability on every filesystem.
 */
internal class WorkspaceImportTransactions(private val root: File, private val ledger: File) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceImportTransactions> {
        private val idPattern = Regex("[a-f0-9-]{36}")
        private const val RECORD_HEADER = "SOLVIT_IMPORT_TRANSACTION_1"
        private const val NOTICE_HEADER = "SOLVIT_IMPORT_RECOVERY_1"
    }
    internal data class Entry(val id: String, val path: String, val backup: String,
        val originalKey: String, val phase: String = "PREPARED", val staged: String? = null, val stagedKey: String? = null)

    private fun identity(file: File): String? =
        if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        else null
    private fun directory() {
        check(ledger.mkdirs() || ledger.isDirectory)
        check(ledger.canonicalFile == ledger.absoluteFile && Files.isDirectory(ledger.toPath(), NOFOLLOW_LINKS))
    }
    private fun safe(relative: String): File {
        val parts = relative.split('/')
        require(relative.length in 1..1024 && !File(relative).isAbsolute &&
            parts.none { it.isEmpty() || it in setOf(".", "..", ".git") || it.any(Char::isISOControl) })
        check(root.canonicalFile == root.absoluteFile && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        val file = File(root, relative)
        // Check parents separately: the final name may be a concurrent symlink, which must survive.
        check(file.parentFile!!.canonicalFile == file.parentFile!!.absoluteFile &&
            Files.isDirectory(file.parentFile!!.toPath(), NOFOLLOW_LINKS))
        return file
    }
    private fun paths(entry: Entry): Pair<File, File> {
        val target = safe(entry.path); val backup = safe(entry.backup)
        require(!target.name.startsWith(".solvit-import-") && target != backup && target.parentFile == backup.parentFile &&
            backup.name.matches(Regex("solvit-backup-[a-f0-9-]{36}")))
        entry.staged?.let {
            val staged = safe(it)
            require(staged.parentFile == target.parentFile && staged.name.matches(Regex("\\.solvit-import-[a-zA-Z0-9-]+\\.partial")))
        }
        return target to backup
    }
    private fun encoded(value: String?): String = value?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) } ?: "-"
    private fun decoded(value: String): String? = if (value == "-") null else String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    private fun file(id: String, suffix: String): File {
        require(idPattern.matches(id)); return File(ledger, "$id.$suffix")
    }
    private fun write(target: File, lines: List<String>) {
        directory()
        val temporary = File(ledger, "${UUID.randomUUID()}.tmp")
        check(temporary.createNewFile())
        try {
            FileOutputStream(temporary).use { out ->
                out.write(lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)); out.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }
    private fun store(entry: Entry) {
        paths(entry)
        write(file(entry.id, "transaction"), listOf(RECORD_HEADER, entry.phase, encoded(entry.path),
            encoded(entry.backup), encoded(entry.originalKey), encoded(entry.staged), encoded(entry.stagedKey)))
    }
    private fun read(record: File): Entry? = runCatching {
        if (!Files.isRegularFile(record.toPath(), NOFOLLOW_LINKS) || record.length() > 16_384) return@runCatching null
        val lines = Files.readAllLines(record.toPath(), Charsets.UTF_8)
        require(lines.size == 7 && lines[0] == RECORD_HEADER && lines[1] in setOf("PREPARED", "PUBLISHING", "ROLLBACK", "COMMITTED"))
        Entry(record.name.removeSuffix(".transaction"), checkNotNull(decoded(lines[2])), checkNotNull(decoded(lines[3])),
            checkNotNull(decoded(lines[4])).also { require(it.length in 1..1024) }, lines[1], decoded(lines[5]), decoded(lines[6]))
            .also { require((it.staged == null) == (it.stagedKey == null)); paths(it) }
    }.getOrNull()
    fun begin(target: File, backup: File): WorkspaceImportTransaction {
        val entry = Entry(UUID.randomUUID().toString(), target.relativeTo(root).invariantSeparatorsPath,
            backup.relativeTo(root).invariantSeparatorsPath, checkNotNull(identity(target)))
        store(entry) // Must finish before the original name is moved.
        return WorkspaceImportTransaction(this, entry)
    }
    internal fun publishing(entry: Entry, staged: File): Entry = entry.copy(phase = "PUBLISHING",
        staged = staged.relativeTo(root).invariantSeparatorsPath, stagedKey = checkNotNull(identity(staged))).also(::store)
    internal fun phase(entry: Entry, phase: String): Entry = entry.copy(phase = phase).also(::store)
    internal fun ownsBackup(entry: Entry): Boolean = paths(entry).second.let { identity(it) == entry.originalKey }
    internal fun forget(entry: Entry) { Files.deleteIfExists(file(entry.id, "transaction").toPath()) }
    internal fun retain(entry: Entry, status: String) {
        // Persist the report before retiring the replayable record. If the app dies between
        // these operations, the notice prevents another attempt after a user changes a name.
        write(file(entry.id, "notice"), listOf(NOTICE_HEADER, status, encoded(entry.path), encoded(entry.backup)))
        forget(entry)
    }
    fun recover(active: Set<String>, publisher: WorkspaceImportPublisher) {
        if (!Files.exists(ledger.toPath(), NOFOLLOW_LINKS)) return
        directory()
        Files.newDirectoryStream(ledger.toPath()).use { records ->
            var visited = 0
            for (path in records) {
                if (++visited > 20_000) break
                val record = path.toFile()
                if (!record.name.endsWith(".transaction") || !idPattern.matches(record.name.removeSuffix(".transaction")) || record.absolutePath in active) continue
                val entry = read(record) ?: continue
                if (Files.exists(file(entry.id, "notice").toPath(), NOFOLLOW_LINKS)) { forget(entry); continue }
                val (target, backup) = paths(entry)
                val targetKey = identity(target)
                val status = when {
                    entry.phase == "COMMITTED" || (entry.stagedKey != null && targetKey == entry.stagedKey) -> "PUBLISHED"
                    targetKey == entry.originalKey -> "ORIGINAL_AVAILABLE"
                    Files.exists(target.toPath(), NOFOLLOW_LINKS) -> "CONFLICT"
                    identity(backup) != entry.originalKey -> "REVIEW_REQUIRED"
                    // A vanished staging name after a publication intent could mean the new
                    // file was published, then removed by Shell. Do not resurrect old data.
                    entry.phase == "PUBLISHING" && (entry.staged == null || identity(safe(entry.staged)) != entry.stagedKey) -> "REVIEW_REQUIRED"
                    else -> {
                        try {
                            publisher.publish(backup, target)
                            check(identity(target) == entry.originalKey)
                            "ORIGINAL_AVAILABLE"
                        } catch (_: Exception) { "REVIEW_REQUIRED" }
                    }
                }
                retain(entry, status)
            }
        }
    }
    fun notices(): List<WorkspaceImportRecovery> {
        if (!Files.exists(ledger.toPath(), NOFOLLOW_LINKS)) return emptyList()
        directory()
        val result = mutableListOf<Pair<Long, WorkspaceImportRecovery>>()
        Files.newDirectoryStream(ledger.toPath()).use { records ->
            var visited = 0
            for (path in records) {
                if (++visited > 20_000) break
                val id = path.fileName.toString().removeSuffix(".notice")
                if (!path.fileName.toString().endsWith(".notice") || !idPattern.matches(id) ||
                    !Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > 16_384) continue
                runCatching {
                    val lines = Files.readAllLines(path, Charsets.UTF_8)
                    require(lines.size == 4 && lines[0] == NOTICE_HEADER && lines[1] in setOf("PUBLISHED", "ORIGINAL_AVAILABLE", "CONFLICT", "REVIEW_REQUIRED"))
                    val target = checkNotNull(decoded(lines[2])); val backup = checkNotNull(decoded(lines[3]))
                    safe(target); safe(backup)
                    result += Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() to
                        WorkspaceImportRecovery(id, target, backup.takeUnless { lines[1] == "ORIGINAL_AVAILABLE" }, lines[1])
                }
            }
        }
        return result.sortedByDescending { it.first }.take(100).map { it.second }
    }
    fun dismiss(id: String) {
        directory()
        check(Files.isRegularFile(file(id, "notice").toPath(), NOFOLLOW_LINKS)) { "恢复提示不存在" }
        // A crash after writing a notice may have left its original transaction record.
        Files.deleteIfExists(file(id, "transaction").toPath())
        Files.delete(file(id, "notice").toPath())
    }
    internal fun recordPath(entry: Entry): String = file(entry.id, "transaction").absolutePath
}

internal class WorkspaceImportTransaction(private val journal: WorkspaceImportTransactions,
    private var entry: WorkspaceImportTransactions.Entry) {
    val recordPath: String get() = journal.recordPath(entry)
    fun publishing(staged: File) { entry = journal.publishing(entry, staged) }
    fun committed() { entry = journal.phase(entry, "COMMITTED") }
    fun rollingBack() { entry = journal.phase(entry, "ROLLBACK") }
    fun ownsBackup(): Boolean = journal.ownsBackup(entry)
    fun forget() = journal.forget(entry)
    fun retain(status:String="REVIEW_REQUIRED") = journal.retain(entry, status)
}
