package dev.solvit.git

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Durable host facts for installing an already downloaded clone. Construction and
 * inventory never replay network or workspace writes. No operation recursively deletes
 * the workspace; only a registered private transaction can be retired.
 * Both publishers MUST atomically refuse an existing destination without a fallback.
 * Android forbids app hard links, so complete file inodes move from staging instead. */
internal class CloneInstallTransactions(private val base: File,
    private val publishWorktreeFile: (File, File) -> Unit,
    private val publishMetadata: (File, File) -> Unit) {
    data class Record(val id: String, val workspaceId: String, val rootPath: String,
        val rootKey: String, val treeKey: String, val endpoint: String, val branch: String,
        val phase: String, val createdAt: Long, val metadataKey: String = "",
        val manifestSha256: String = "", val head: String = "")
    data class Entry(val path: String, val directory: Boolean, val key: String,
        val bytes: Long, val sha256: String, val executable: Boolean)
    data class Observation(val record: Record, val status: String)
    data class Inventory(val records: List<Observation>, val incomplete: Boolean, val unreadable: Int)
    data class Review(val record: Record, val fingerprint: String, val canResume: Boolean,
        val canRestart: Boolean, val files: Int, val bytes: Long, val installedFiles: Int,
        val problem: String?)
    companion object {
        private const val MARKER = "solvit-clone-origin"
        private const val MAX_MANIFEST = 32 * 1024 * 1024
        private val phases = setOf("DOWNLOADING", "READY", "INSTALLING", "PUBLISHING", "COMMITTED", "DISCARDING")
        private val locks = mutableMapOf<String, Mutex>()
    }
    /** Shared by different page/service instances. Reads do not wait behind a download. */
    suspend fun <T> exclusive(workspaceId: String, block: suspend () -> T): T {
        val key = base.absolutePath + "/" + workspaceId
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        check(lock.tryLock()) { "此工作区已有克隆操作，请等待结束" }
        try { return block() } finally { lock.unlock() }
    }
    private suspend fun boundary(checkpoint: suspend () -> Unit) {
        currentCoroutineContext().ensureActive(); checkpoint()
    }
    private fun attrs(file: File): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: java.nio.file.NoSuchFileException) { null }
    private fun key(file: File) = checkNotNull(attrs(file)?.fileKey()?.toString()) { "文件身份不可用" }
    private fun directory(file: File) {
        check(file.canonicalFile == file.absoluteFile && attrs(file)?.isDirectory == true) { "克隆目录已变化" }
    }
    private fun id(value: String) { require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) }
    private fun container(value: String): File {
        id(value); directory(base)
        return File(base, value).also(::directory)
    }
    fun tree(record: Record): File = File(container(record.id), "tree").also {
        directory(it); check(key(it) == record.treeKey) { "克隆暂存目录身份已变化" }
    }
    private fun encoded(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    private fun decoded(text: String) = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getDecoder().decode(text))).toString()
    private fun readText(file: File, max: Int): String {
        check(attrs(file)?.isRegularFile == true)
        return Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(max + 1)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded metadata */ }
            require(buffer.position() <= max) { "克隆记录超过读取上限" }
            buffer.flip(); Charsets.UTF_8.newDecoder().decode(buffer).toString()
        }
    }
    private fun writeText(file: File, text: String) {
        val temp = File(file.parentFile, "${UUID.randomUUID()}.tmp")
        check(temp.createNewFile())
        try {
            FileOutputStream(temp).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp.toPath()) }
    }
    private fun persist(record: Record) {
        require(record.phase in phases)
        val fields = listOf("SOLVIT_CLONE_1", record.id, encoded(record.workspaceId), encoded(record.rootPath),
            encoded(record.rootKey), encoded(record.treeKey), encoded(record.endpoint), encoded(record.branch),
            record.phase, record.createdAt.toString(), encoded(record.metadataKey), record.manifestSha256, record.head)
        writeText(File(container(record.id), "record"), fields.joinToString("\n", postfix = "\n"))
    }
    fun read(transactionId: String): Record {
        val text = readText(File(container(transactionId), "record"), 16 * 1024)
        require(text.endsWith('\n'))
        val fields = text.removeSuffix("\n").split('\n')
        require(fields.size == 13 && fields[0] == "SOLVIT_CLONE_1" && fields[1] == transactionId)
        val r = Record(transactionId, decoded(fields[2]), decoded(fields[3]), decoded(fields[4]), decoded(fields[5]),
            decoded(fields[6]), decoded(fields[7]), fields[8], fields[9].toLong(), decoded(fields[10]), fields[11], fields[12])
        require(r.workspaceId.length in 1..256 && r.workspaceId.none(Char::isISOControl))
        require(File(r.rootPath).isAbsolute && r.rootPath.length <= 4096)
        require(r.rootKey.length in 1..1024 && r.treeKey.length in 1..1024 && r.metadataKey.length <= 1024)
        require(r.phase in phases && r.createdAt >= 0)
        require(r.endpoint.length <= 2048 && dev.solvit.core.GitRemoteEndpoint.parse(r.endpoint).url == r.endpoint)
        require(r.branch.length in 1..256 && r.branch.none(Char::isISOControl))
        if (r.phase !in setOf("DOWNLOADING", "DISCARDING")) require(r.metadataKey.isNotEmpty() && r.manifestSha256.matches(Regex("[a-f0-9]{64}")) && r.head.matches(Regex("[a-f0-9]{40}")))
        return r
    }
    fun begin(workspaceId: String, root: File, endpoint: String, branch: String): Record {
        require(workspaceId.length in 1..256 && workspaceId.none(Char::isISOControl))
        require(endpoint.length <= 2048 && dev.solvit.core.GitRemoteEndpoint.parse(endpoint).url == endpoint)
        require(branch.length in 1..256 && branch.none(Char::isISOControl))
        directory(root)
        check(root.listFiles()?.isEmpty() == true) { "克隆需要空工作目录，已有文件不会被覆盖" }
        check(base.mkdirs() || base.isDirectory); directory(base)
        check(!root.toPath().startsWith(base.toPath()) && !base.toPath().startsWith(root.toPath()))
        val pending = inventory(workspaceId)
        check(!pending.incomplete && pending.unreadable == 0 && pending.records.isEmpty()) { "请先处理上次克隆记录" }
        val transactionId = UUID.randomUUID().toString()
        val folder = File(base, transactionId); Files.createDirectory(folder.toPath())
        val staged = File(folder, "tree"); Files.createDirectory(staged.toPath())
        val record = Record(transactionId, workspaceId, root.absolutePath, key(root), key(staged), endpoint,
            branch, "DOWNLOADING", System.currentTimeMillis())
        persist(record)
        return read(record.id)
    }
    private fun root(record: Record): File = File(record.rootPath).also {
        directory(it); check(key(it) == record.rootKey) { "工作区根目录身份已变化，文件均保留" }
    }
    private fun relative(path: String) {
        require(path.length in 1..4096 && '\\' !in path && path.none(Char::isISOControl))
        require(path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it != ".git" })
    }
    private fun child(root: File, path: String): File {
        relative(path)
        return File(root, path).also { check(it.canonicalFile == it.absoluteFile) { "克隆文件路径已变化" } }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun digest(file: File, expectedKey: String, expectedBytes: Long, checkpoint: suspend () -> Unit): String {
        val before = attrs(file)
        check(before?.isRegularFile == true && before.fileKey()?.toString() == expectedKey && before.size() == expectedBytes) { "克隆文件身份或大小已变化" }
        require(expectedBytes in 0..32L * 1024 * 1024)
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var count = 0L
        try {
            Files.newByteChannel(file.toPath(), setOf(READ, NOFOLLOW_LINKS)).use { input ->
                while (true) {
                    boundary(checkpoint); val size = input.read(ByteBuffer.wrap(buffer)); if (size < 0) break
                    count += size; check(count <= expectedBytes) { "克隆文件内容增长" }; hash.update(buffer, 0, size)
                }
            }
            boundary(checkpoint); val after = attrs(file)
            check(count == expectedBytes && after?.isRegularFile == true && after.fileKey()?.toString() == expectedKey && after.size() == count) { "克隆文件在读取时变化" }
            return hash.digest().joinToString("") { "%02x".format(it) }
        } finally { buffer.fill(0) }
    }
    /** The complete reviewed plan is synced before the first workspace entry exists. */
    suspend fun seal(transactionId: String, head: String, checkpoint: suspend () -> Unit = {}): Record {
        val record = read(transactionId); check(record.phase == "DOWNLOADING"); require(head.matches(Regex("[a-f0-9]{40}")))
        root(record); val staged = tree(record); val metadata = File(staged, ".git"); directory(metadata)
        val entries = mutableListOf<Entry>(); var total = 0L; var estimatedManifest = 32L
        val paths = mutableListOf<java.nio.file.Path>(); val coroutine = currentCoroutineContext()
        Files.walkFileTree(staged.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            private fun capture(path: java.nio.file.Path, a: BasicFileAttributes) {
                coroutine.ensureActive(); check(paths.size < 100_000) { "克隆文件数超过上限" }
                estimatedManifest += (staged.toPath().relativize(path).toString().toByteArray(Charsets.UTF_8).size +
                    checkNotNull(a.fileKey()?.toString()).toByteArray(Charsets.UTF_8).size) * 4L / 3L + 160
                check(estimatedManifest <= MAX_MANIFEST) { "克隆审阅清单超过上限" }; paths.add(path)
            }
            override fun preVisitDirectory(dir: java.nio.file.Path, a: BasicFileAttributes): java.nio.file.FileVisitResult {
                if (dir == metadata.toPath()) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                if (dir != staged.toPath()) capture(dir, a)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: java.nio.file.Path, a: BasicFileAttributes): java.nio.file.FileVisitResult {
                capture(file, a); return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        for (path in paths.sortedBy { staged.toPath().relativize(it).joinToString("/") }) {
                boundary(checkpoint); val name = staged.toPath().relativize(path).joinToString("/")
                val file = child(staged, name); val a = checkNotNull(attrs(file))
                check(a.isRegularFile || a.isDirectory) { "克隆暂不支持符号链接或特殊文件" }
                check(entries.size < 100_000) { "克隆文件数超过上限" }
                if (a.isRegularFile) total += a.size()
                check(total <= 256L * 1024 * 1024) { "克隆工作树超过大小上限" }
                val fileKey = checkNotNull(a.fileKey()?.toString())
                entries += Entry(name, a.isDirectory, fileKey, if (a.isDirectory) 0 else a.size(),
                    if (a.isDirectory) "-" else digest(file, fileKey, a.size(), checkpoint), file.canExecute())
        }
        val manifest = (listOf("SOLVIT_CLONE_FILES_1") + entries.map {
            listOf(if (it.directory) "D" else "F", encoded(it.path), encoded(it.key), it.bytes.toString(), it.sha256, it.executable.toString()).joinToString("\t")
        }).joinToString("\n", postfix = "\n")
        require(manifest.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST)
        val marker = File(metadata, MARKER); check(marker.createNewFile())
        FileOutputStream(marker).use { it.write(record.id.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        writeText(File(container(record.id), "manifest"), manifest)
        boundary(checkpoint)
        val ready = record.copy(phase = "READY", metadataKey = key(metadata), manifestSha256 = sha(manifest.toByteArray(Charsets.UTF_8)), head = head)
        persist(ready); return ready
    }
    private fun manifest(record: Record): List<Entry> {
        val text = readText(File(container(record.id), "manifest"), MAX_MANIFEST)
        check(sha(text.toByteArray(Charsets.UTF_8)) == record.manifestSha256) { "克隆审阅清单已变化" }
        require(text.endsWith('\n')); val lines = text.removeSuffix("\n").split('\n')
        require(lines.first() == "SOLVIT_CLONE_FILES_1" && lines.size <= 100_001)
        var total = 0L
        val entries = lines.drop(1).map { line ->
            val p = line.split('\t'); require(p.size == 6 && p[0] in setOf("D", "F"))
            val e = Entry(decoded(p[1]), p[0] == "D", decoded(p[2]), p[3].toLong(), p[4], p[5].toBooleanStrict())
            relative(e.path); require(e.key.length in 1..1024 && e.bytes in 0..32L * 1024 * 1024)
            require(if (e.directory) e.bytes == 0L && e.sha256 == "-" else e.sha256.matches(Regex("[a-f0-9]{64}")))
            total += e.bytes; require(total <= 256L * 1024 * 1024); e
        }
        require(entries.map { it.path }.let { it == it.sorted() && it.toSet().size == it.size })
        return entries
    }
    private fun published(record: Record): Boolean {
        val target = File(root(record), ".git")
        if (attrs(target)?.isDirectory != true || target.canonicalFile != target.absoluteFile || key(target) != record.metadataKey) return false
        val staged = File(container(record.id), "tree")
        if (attrs(staged) != null) { directory(staged); check(key(staged) == record.treeKey) }
        else check(record.phase in setOf("COMMITTED", "DISCARDING"))
        return readText(File(target, MARKER), 64) == record.id && attrs(File(staged, ".git")) == null
    }
    /** May read large files but never mutates the workspace. Missing is not matching. */
    private suspend fun validate(record: Record, entries: List<Entry>, checkpoint: suspend () -> Unit) {
        val staged = tree(record); val destination = root(record); val planned = entries.associateBy { it.path }
        Files.walk(destination.toPath()).use { paths ->
            val iterator = paths.filter { it != destination.toPath() }.iterator(); var visited = 0
            while (iterator.hasNext()) {
                boundary(checkpoint); check(++visited <= 100_000)
                val path = iterator.next(); val name = destination.toPath().relativize(path).joinToString("/")
                val entry = checkNotNull(planned[name]) { "工作区出现清单外文件，请先检查：$name" }
                val file = child(destination, name); val a = checkNotNull(attrs(file))
                if (entry.directory) check(a.isDirectory) { "克隆目录同名冲突：$name" }
                else check(a.isRegularFile && a.fileKey()?.toString() == entry.key && file.canExecute() == entry.executable &&
                    digest(file, entry.key, entry.bytes, checkpoint) == entry.sha256) { "克隆文件已被修改或替换：$name" }
            }
        }
        for (entry in entries) {
            boundary(checkpoint); val source = child(staged, entry.path); val a = attrs(source)
            if (a == null && !entry.directory && record.phase in setOf("INSTALLING", "PUBLISHING")) {
                // A no-replace rename is already an executed fact. Missing source is
                // accepted only with this exact complete inode at its recorded target.
                val target = child(destination, entry.path); val installed = attrs(target)
                check(installed?.isRegularFile == true && installed.fileKey()?.toString() == entry.key &&
                    target.canExecute() == entry.executable && digest(target, entry.key, entry.bytes, checkpoint) == entry.sha256) {
                    "克隆文件既不在暂存也没有完整发布：${entry.path}"
                }
                continue
            }
            checkNotNull(a) { "克隆暂存文件缺失：${entry.path}" }
            check(a.fileKey()?.toString() == entry.key) { "克隆暂存身份已变化：${entry.path}" }
            if (entry.directory) check(a.isDirectory)
            else check(a.isRegularFile && source.canExecute() == entry.executable &&
                digest(source, entry.key, entry.bytes, checkpoint) == entry.sha256) { "克隆暂存内容已变化：${entry.path}" }
        }
    }
    /** Read-only bounded inspection. A failed inspection is never advertised as resumable.
     * Error text deliberately excludes absolute private paths and underlying IO messages. */
    suspend fun review(transactionId: String, checkpoint: suspend () -> Unit = {}): Review {
        val record = read(transactionId)
        val fingerprint = sha(readText(File(container(record.id), "record"), 16 * 1024).toByteArray(Charsets.UTF_8))
        var files = 0; var bytes = 0L; var installed = 0
        var resume = false; var restart = false; var problem: String? = null
        try {
            boundary(checkpoint)
            when {
                record.phase == "DISCARDING" -> problem = "私有暂存清理曾中断，可确认后继续关闭记录"
                record.phase == "DOWNLOADING" -> {
                    tree(record)
                    restart = root(record).listFiles()?.isEmpty() == true
                    problem = if (restart) "下载未完成，需要重新确认联网下载" else "工作目录已有文件，不能重新克隆；可保留文件并放弃暂存"
                }
                record.phase in setOf("PUBLISHING", "COMMITTED") && published(record) -> resume = true
                record.phase == "COMMITTED" -> problem = "已发布仓库身份变化，文件与记录均保留"
                else -> {
                    val entries = manifest(record)
                    files = entries.count { !it.directory }; bytes = entries.sumOf { it.bytes }
                    validate(record, entries, checkpoint)
                    val metadata = File(tree(record), ".git")
                    directory(metadata)
                    check(key(metadata) == record.metadataKey && readText(File(metadata, MARKER), 64) == record.id)
                    check(attrs(File(root(record), ".git")) == null)
                    installed = entries.count { !it.directory && attrs(child(root(record), it.path)) != null }
                    resume = true
                }
            }
            boundary(checkpoint)
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { problem = "工作区、暂存或审阅清单已变化，不能继续安装；请检查文件或保留文件并放弃暂存" }
        boundary(checkpoint)
        check(read(record.id) == record) { "克隆记录在审阅期间变化，请重新审阅" }
        return Review(record, fingerprint, resume, restart, files, bytes, installed, problem)
    }
    suspend fun install(transactionId: String, retireOnSuccess: Boolean = true, checkpoint: suspend () -> Unit = {}) {
        var record = read(transactionId); check(record.phase !in setOf("DOWNLOADING", "DISCARDING")) { "下载未完成或记录正在关闭，请确认后重新下载" }
        boundary(checkpoint)
        if (record.phase in setOf("PUBLISHING", "COMMITTED") && published(record)) {
            // The rename is an already executed fact. Never recheck/rewrite user worktree
            // edits made after it; only retire this operation's private copy and record.
            persist(record.copy(phase = "COMMITTED")); boundary(checkpoint)
            if (retireOnSuccess) retire(record.id)
            return
        }
        check(record.phase != "COMMITTED") { "已发布仓库身份变化，保留文件待检查" }
        val entries = manifest(record); validate(record, entries, checkpoint)
        val staged = tree(record); val destination = root(record); val metadata = File(staged, ".git")
        directory(metadata); check(key(metadata) == record.metadataKey && readText(File(metadata, MARKER), 64) == record.id)
        check(attrs(File(destination, ".git")) == null) { "工作区.git已存在，不会覆盖" }
        record = record.copy(phase = "INSTALLING"); persist(record)
        for (entry in entries) {
            boundary(checkpoint); root(record)
            val source = child(staged, entry.path); val target = child(destination, entry.path)
            if (attrs(target) != null) continue // Validated above; final validation repeats.
            if (entry.directory) Files.createDirectory(target.toPath())
            else publishWorktreeFile(source, target) // Atomic complete inode, no replacement.
            boundary(checkpoint)
        }
        validate(record, entries, checkpoint); boundary(checkpoint)
        persist(record.copy(phase = "PUBLISHING"))
        publishMetadata(metadata, File(destination, ".git"))
        boundary(checkpoint) // External kill here must not replay installation on restart.
        check(published(record)) { "Git发布身份变化，文件与记录保留" }
        persist(record.copy(phase = "COMMITTED")); boundary(checkpoint)
        if (retireOnSuccess) retire(record.id)
    }
    /** Explicitly abandon/acknowledge; removes only the registered private directory.
     * Already moved workspace files retain their inode and bytes. */
    fun retire(transactionId: String) {
        val record = read(transactionId); val folder = container(record.id)
        val staged = File(folder, "tree")
        if (attrs(staged) != null) { directory(staged); check(key(staged) == record.treeKey) }
        else check(record.phase in setOf("COMMITTED", "DISCARDING"))
        val known = setOf("tree", "record", "manifest")
        check(folder.listFiles()?.all { it.name in known || it.name.matches(Regex("[a-f0-9-]{36}\\.tmp")) } == true)
        persist(record.copy(phase = "DISCARDING"))
        // Delete tree first and intent last. A kill during private cleanup still leaves
        // provenance for remaining entries and cannot delete an unregistered directory.
        if (attrs(staged) != null) {
            Files.walkFileTree(staged.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                override fun visitFile(file: java.nio.file.Path, a: BasicFileAttributes): java.nio.file.FileVisitResult {
                    Files.delete(file); return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir); return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
        folder.listFiles().orEmpty().filter { it.name != "record" }.forEach { Files.delete(it.toPath()) }
        Files.delete(File(folder, "record").toPath()); Files.delete(folder.toPath())
    }
    fun inventory(workspaceId: String): Inventory {
        if (attrs(base) == null) return Inventory(emptyList(), false, 0)
        directory(base); val records = mutableListOf<Observation>(); var visited = 0; var unreadable = 0; var incomplete = false
        Files.newDirectoryStream(base.toPath()).use { paths ->
            for (path in paths) {
                if (++visited > 1000) { incomplete = true; break }
                try {
                    val record = read(path.fileName.toString())
                    if (record.workspaceId != workspaceId) continue
                    val status = try {
                        root(record)
                        when {
                            record.phase in setOf("PUBLISHING", "COMMITTED", "DISCARDING") && record.metadataKey.isNotEmpty() && published(record) -> "PUBLISHED"
                            record.phase == "DISCARDING" -> "CLEANUP_INTERRUPTED"
                            record.phase == "DOWNLOADING" -> "DOWNLOAD_INTERRUPTED"
                            record.phase == "COMMITTED" -> "UNCONFIRMED"
                            else -> "INSTALL_INTERRUPTED"
                        }
                    } catch (_: Exception) { "UNAVAILABLE" }
                    records += Observation(record, status)
                } catch (failure: CancellationException) { throw failure }
                catch (_: Exception) { unreadable++ }
            }
        }
        return Inventory(records.sortedByDescending { it.record.createdAt }, incomplete, unreadable)
    }
}
