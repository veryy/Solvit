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
import org.eclipse.jgit.dircache.DirCacheIterator
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
    private val authorize: suspend () -> Unit,
) {
    private val binding = checkNotNull(workspaces.state.value.bindings[chatId]) { "此对话尚未绑定工作区" }
    private val mutex = Mutex()
    private var closed = false
    data class Entry(val path: String, val staged: String?, val working: String?, val conflict: Boolean)
    data class Status(val branch: String?, val head: String?, val entries: List<Entry>, val total: Int)
    data class Revision(val head: String?, val indexSha256: String?)
    data class Diff(val path: String, val staged: Boolean, val patch: String, val revision: Revision,
        val workingSha256: String?)
    data class Commit(val id: String, val message: String, val author: String, val timeSeconds: Int)

    private suspend fun verify() {
        currentCoroutineContext().ensureActive()
        check(!closed) { "Git页面已关闭" }
        authorize()
        check(activeChat() == chatId && workspaces.state.value.bindings[chatId] == binding) {
            "对话或工作区已变化，请重新打开Git页面"
        }
    }
    private suspend fun <T> read(block: (Git) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            verify()
            val root = withContext(WorkspacePageAuthority { target, session ->
                verify()
                check(target == null || target == binding)
                check(session == null || session == chatId)
            }) { File(workspaces.location(binding)).canonicalFile }
            validateRepository(root)
            verify()
            val result = FileRepositoryBuilder().setWorkTree(root).setGitDir(File(root, ".git"))
                .setMustExist(true).build().use { repository ->
                    check(repository.workTree.canonicalFile == root && !repository.isBare)
                    Git(repository).use(block)
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
        check(config.sections.none { it.equals("include", true) || it.equals("includeIf", true) }) {
            "Git配置不能读取外部配置文件"
        }
        check(config.getString("core", null, "worktree") == null &&
            !config.getBoolean("core", null, "bare", false) &&
            !config.getBoolean("extensions", null, "worktreeConfig", false)) { "Git工作树配置不受支持" }
    }
    suspend fun status(offset: Int = 0, limit: Int = 100): Status {
        require(offset in 0..100_000 && limit in 1..100)
        return read { git ->
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
        digest(File(git.repository.directory, "index")))

    /** Read a real HEAD/index or index/worktree patch without changing either tree. */
    suspend fun diff(path: String, staged: Boolean): Diff = read { git ->
        val file = checkedPath(git.repository.workTree.canonicalFile, path)
        val before = revision(git)
        val workingBefore = if (staged) null else digest(file)
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
        check(before == revision(git) && (staged || workingBefore == digest(file))) {
            "Git文件或暂存区在读取中变化，请刷新差异"
        }
        val patch = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString()
        Diff(path, staged, patch, before, workingBefore)
    }

    suspend fun history(limit: Int = 30): List<Commit> {
        require(limit in 1..100)
        return read { git ->
            if (git.repository.resolve(Constants.HEAD) == null) emptyList()
            else git.log().setMaxCount(limit).call().map { commit ->
                Commit(commit.name, commit.fullMessage.take(16_384), commit.authorIdent.name.take(256), commit.commitTime)
            }
        }
    }
    suspend fun close() = mutex.withLock { closed = true }
}
