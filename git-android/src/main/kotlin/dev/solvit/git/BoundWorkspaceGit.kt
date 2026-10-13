package dev.solvit.git

import dev.solvit.core.WorkspacePageAuthority
import dev.solvit.core.WorkspaceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilter
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.nio.file.Files

/** All paths come from the captured host binding; a JS request cannot choose a different root. */
class BoundWorkspaceGit(
    private val workspaces: WorkspaceService,
    private val chatId: String,
    private val activeChat: () -> String?,
    private val authorizeWrite: suspend () -> Unit = { error("Git写入尚未授权") },
    private val authorize: suspend () -> Unit,
) {
    private val binding = checkNotNull(workspaces.state.value.bindings[chatId]) { "此对话尚未绑定工作区" }
    private val mutex = Mutex()
    private var closed = false
    data class Entry(val path: String, val staged: String?, val working: String?, val conflict: Boolean)
    data class Status(val branch: String?, val head: String?, val entries: List<Entry>, val total: Int)
    data class Revision(val head: String?, val indexSha256: String?, val branch: String?)
    data class Diff(val path: String, val staged: Boolean, val patch: String, val revision: Revision,
        val workingSha256: String?, val workingExecutable: Boolean?)
    data class Commit(val id: String, val message: String, val author: String, val timeSeconds: Int)

    private suspend fun verify() {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Git页面已关闭" }
        authorize()
        check(activeChat() == chatId && workspaces.state.value.bindings[chatId] == binding) {
            "对话或工作区已变化，请重新打开Git页面"
        }
    }
    private suspend fun resolveRoot(): File = withContext(WorkspacePageAuthority { target, session ->
        verify()
        check(target == null || target == binding)
        check(session == null || session == chatId)
    }) { File(workspaces.location(binding)).canonicalFile }

    /** Explicit host-approved creation only. Reads never initialize a missing repository. */
    suspend fun initialize(): Revision = withContext(Dispatchers.IO) {
        mutex.withLock {
            verifyWrite()
            val root = resolveRoot()
            val metadata = File(root, ".git")
            check(!Files.exists(metadata.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "工作区已存在Git元数据，不能覆盖初始化"
            }
            FileRepositoryBuilder().setWorkTree(root).setGitDir(metadata)
                .setInitialBranch("main").setFS(WorkspaceGitFileSystem()).build().use { repository ->
                    verifyWrite()
                    check(!Files.exists(metadata.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        "Git元数据在授权期间出现，初始化已取消"
                    }
                    repository.create(false)
                    check(repository.workTree.canonicalFile == root && repository.fullBranch == "refs/heads/main")
                    verify()
                    Git(repository).use { revision(it) }
                }
        }
    }

    private suspend fun <T> access(block: suspend (Git) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            verify()
            val root = resolveRoot()
            validateRepository(root)
            verify()
            val result = FileRepositoryBuilder().setWorkTree(root).setGitDir(File(root, ".git"))
                .setFS(WorkspaceGitFileSystem()).setMustExist(true).build().use { repository ->
                    check(repository.workTree.canonicalFile == root && !repository.isBare)
                    Git(repository).use { git -> block(git) }
                }
            verify() // Permission revocation or rebinding must also prevent returning already-read data.
            result
        }
    }
    private fun validateRepository(root: File) {
        val metadata = File(root, ".git")
        check(metadata.isDirectory && metadata.canonicalFile == metadata.absoluteFile) {
            "工作区没有本地Git仓库，或.git指向外部目录"
        }
        // JGit must not read objects/configuration through links or shared-worktree pointers.
        check(!File(metadata, "commondir").exists() && !File(metadata, "objects/info/alternates").exists()) {
            "暂不支持共享或外部Git对象目录"
        }
        var count = 0
        Files.walk(metadata.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                check(++count <= 100_000) { "Git仓库元数据超过当前检查上限" }
                check(!Files.isSymbolicLink(iterator.next())) { "Git元数据不能包含符号链接" }
            }
        }
        val file = File(metadata, "config")
        check(file.length() <= 64 * 1024) { "Git配置过大" }
        val config = Config().apply { fromText(file.readText(Charsets.UTF_8)) }
        check(config.sections.none { it.equals("include", true) || it.equals("includeIf", true) || it.equals("filter", true) }) {
            "Git配置不能读取外部配置或自动执行过滤器"
        }
        check(config.getString("core", null, "worktree") == null &&
            !config.getBoolean("core", null, "bare", false) &&
            !config.getBoolean("extensions", null, "worktreeConfig", false)) { "Git工作树配置不受支持" }
    }
    suspend fun status(offset: Int = 0, limit: Int = 100): Status {
        require(offset in 0..100_000 && limit in 1..100)
        return access { git ->
            val status = git.status().call()
            val paths = (status.added + status.changed + status.removed + status.modified +
                status.missing + status.untracked + status.conflicting).sorted()
            val entries = paths.drop(offset).take(limit).map { path -> Entry(path,
                when (path) { in status.added -> "added"; in status.changed -> "modified"; in status.removed -> "deleted"; else -> null },
                when (path) { in status.untracked -> "untracked"; in status.modified -> "modified"; in status.missing -> "deleted"; else -> null },
                path in status.conflicting) }
            Status(git.repository.fullBranch, git.repository.resolve(Constants.HEAD)?.name, entries, paths.size)
        }
    }
    private fun checkedPath(root: File, path: String): File {
        require(path.length in 1..1024 && '\\' !in path && '\u0000' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) {
            "Git文件路径必须是工作区内相对文件路径"
        }
        val file = File(root, path).absoluteFile
        check(file.canonicalFile == file && (!file.exists() || file.isFile)) {
            "Git差异不能读取目录或符号链接目标"
        }
        return file
    }
    private fun digest(file: File): String? {
        if (!file.exists()) return null
        check(file.isFile && file.length() <= 8L * 1024 * 1024) { "Git文件超过当前版本检查上限" }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= 8L * 1024 * 1024) { "Git文件在读取中超过上限" }
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun revision(git: Git) = Revision(git.repository.resolve(Constants.HEAD)?.name,
        digest(File(git.repository.directory, "index")), git.repository.fullBranch)

    /** Read a real HEAD/index or index/worktree patch without changing either tree. */
    suspend fun diff(path: String, staged: Boolean): Diff = access { git ->
        val file = checkedPath(git.repository.workTree.canonicalFile, path)
        val before = revision(git)
        val workingBefore = if (staged) null else digest(file)
        val executableBefore = if (staged || !file.exists()) null else file.canExecute()
        val output = object : ByteArrayOutputStream() {
            override fun write(value: Int) {
                check(size() < 256 * 1024) { "Git差异超过256KiB，请缩小文件范围" }
                super.write(value)
            }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                check(length <= 256 * 1024 - size()) { "Git差异超过256KiB，请缩小文件范围" }
                super.write(value, offset, length)
            }
        }
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setPathFilter(PathFilter.create(path)) // Literal filenames, including [ and *.
            formatter.setContext(3)
            formatter.setBinaryFileThreshold(256 * 1024)
            val index = DirCacheIterator(git.repository.readDirCache())
            val entries = if (staged) {
                val tree = git.repository.resolve("HEAD^{tree}")
                val head = if (tree == null) EmptyTreeIterator() else CanonicalTreeParser().apply {
                    git.repository.newObjectReader().use { reader -> reset(reader, tree) }
                }
                formatter.scan(head, index)
            } else formatter.scan(index, FileTreeIterator(git.repository))
            formatter.format(entries); formatter.flush()
        }
        check(before == revision(git) && (staged || workingBefore == digest(file) && executableBefore == if (file.exists()) file.canExecute() else null)) {
            "Git文件或暂存区在读取中变化，请刷新差异"
        }
        val patch = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        Diff(path, staged, patch, before, workingBefore, executableBefore)
    }

    suspend fun history(limit: Int = 30): List<Commit> {
        require(limit in 1..100)
        return access { git ->
            if (git.repository.resolve(Constants.HEAD) == null) emptyList()
            else git.log().setMaxCount(limit).call().map { commit ->
                Commit(commit.name, commit.fullMessage.take(16_384), commit.authorIdent.name.take(256), commit.commitTime)
            }
        }
    }
    private suspend fun verifyWrite() {
        verify()
        authorizeWrite()
        verify()
    }
    /** Stage the exact reviewed file version; does not stage unrelated paths. */
    suspend fun stage(review: Diff): Revision = access { git ->
        require(!review.staged) { "请先审阅工作树差异" }
        val file = checkedPath(git.repository.workTree.canonicalFile, review.path)
        check(revision(git) == review.revision && digest(file) == review.workingSha256 &&
            (if (file.exists()) file.canExecute() else null) == review.workingExecutable) {
            "文件或暂存区已变化，请重新审阅后暂存"
        }
        verifyWrite()
        check(revision(git) == review.revision && digest(file) == review.workingSha256 &&
            (if (file.exists()) file.canExecute() else null) == review.workingExecutable) {
            "文件或暂存区在授权期间变化，请刷新"
        }
        val content = if (file.exists()) file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count <= 8 * 1024 * 1024 - output.size()) { "Git文件超过暂存上限" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } else null
        val capturedSha = content?.let { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } }
        check(capturedSha == review.workingSha256) { "文件在准备暂存时变化，请重新审阅" }
        val status = git.status().call()
        check(review.path in (status.untracked + status.modified + status.missing + status.conflicting)) {
            "所选文件没有可暂存的变化，或已被忽略"
        }
        val cache = git.repository.lockDirCache()
        try {
            check(revision(git) == review.revision) { "HEAD或暂存区在获取索引锁时变化" }
            verifyWrite()
            check(revision(git) == review.revision) { "HEAD或暂存区在授权时变化" }
            val builder = cache.builder()
            for (index in 0 until cache.entryCount) {
                val entry = cache.getEntry(index)
                if (entry.pathString != review.path) builder.add(entry)
            }
            if (content != null) {
                git.repository.newObjectInserter().use { inserter ->
                    val blob = inserter.insert(Constants.OBJ_BLOB, content)
                    inserter.flush()
                    val entry = DirCacheEntry(review.path)
                    entry.fileMode = if (review.workingExecutable == true) FileMode.EXECUTABLE_FILE else FileMode.REGULAR_FILE
                    entry.setObjectId(blob)
                    builder.add(entry)
                }
            }
            // Frozen reviewed bytes are staged; later worktree changes remain unstaged.
            check(builder.commit()) { "无法提交Git索引，请刷新" }
        } finally { cache.unlock() }
        revision(git)
    }
    /** Reset just the selected index entry to HEAD, preserving the current working file. */
    suspend fun unstage(path: String, expected: Revision): Revision = access { git ->
        checkedPath(git.repository.workTree.canonicalFile, path)
        check(revision(git) == expected) { "暂存区已变化，请重新审阅" }
        verifyWrite()
        val cache = git.repository.lockDirCache()
        try {
            check(revision(git) == expected) { "暂存区在获取索引锁时变化，请刷新" }
            verifyWrite()
            check(revision(git) == expected) { "暂存区在授权时变化，请刷新" }
            val builder = cache.builder()
            for (index in 0 until cache.entryCount) {
                val entry = cache.getEntry(index)
                if (entry.pathString != path) builder.add(entry)
            }
            val tree = git.repository.resolve("HEAD^{tree}")
            if (tree != null) TreeWalk.forPath(git.repository, path, tree)?.use { walk ->
                check(walk.getFileMode(0) != FileMode.TREE) { "只能取消暂存单个文件" }
                val entry = DirCacheEntry(path)
                entry.fileMode = walk.getFileMode(0)
                entry.setObjectId(walk.getObjectId(0))
                builder.add(entry)
            }
            check(builder.commit()) { "无法提交Git索引，请刷新" }
        } finally { cache.unlock() }
        revision(git)
    }
    /** Commit only the reviewed index; never implicitly stage or amend worktree files. */
    suspend fun commit(expected: Revision, message: String, author: String, email: String): Commit = access { git ->
        require(message.isNotBlank() && message.length <= 4096 && '\u0000' !in message)
        require(author.isNotBlank() && author.length <= 80 && author.none(Char::isISOControl))
        require(email.length <= 254 && email.none(Char::isISOControl) && email.matches(Regex("[^\\s<>@]+@[^\\s<>@]+")))
        check(git.repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
            "仓库存在未完成的合并或其他操作，请先处理"
        }
        check(revision(git) == expected) { "HEAD或暂存区已变化，请重新审阅" }
        verifyWrite()
        val cache = git.repository.lockDirCache()
        try {
            check(revision(git) == expected) { "HEAD或暂存区在获取索引锁时变化" }
            git.repository.newObjectInserter().use { inserter ->
                val tree = cache.writeTree(inserter)
                check((expected.head != null || cache.entryCount > 0) && tree != git.repository.resolve("HEAD^{tree}")) { "暂存区没有需要提交的变化" }
                val identity = PersonIdent(author, email)
                val commit = CommitBuilder().apply {
                    setTreeId(tree)
                    expected.head?.let { setParentId(ObjectId.fromString(it)) }
                    setAuthor(identity); setCommitter(identity); setMessage(message)
                }
                val id = inserter.insert(commit)
                inserter.flush()
                verifyWrite()
                check(revision(git) == expected) { "HEAD或暂存区在提交前变化，请刷新" }
                val update = git.repository.updateRef(expected.branch?.takeIf { it.startsWith("refs/heads/") } ?: Constants.HEAD)
                update.setExpectedOldObjectId(expected.head?.let(ObjectId::fromString) ?: ObjectId.zeroId())
                update.setNewObjectId(id)
                update.setRefLogIdent(identity)
                update.setRefLogMessage("commit: " + message.lineSequence().first(), false)
                check(update.update() in setOf(RefUpdate.Result.NEW, RefUpdate.Result.FAST_FORWARD)) {
                    "Git提交未写入：HEAD已变化或无法获得引用锁，请刷新"
                }
                RevWalk(git.repository).use { walk ->
                    val result = walk.parseCommit(id)
                    Commit(result.name, result.fullMessage, result.authorIdent.name, result.commitTime)
                }
            }
        } finally { cache.unlock() }
    }
    suspend fun close() = mutex.withLock { closed = true }
}
