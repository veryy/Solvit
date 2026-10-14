package dev.solvit.git

import dev.solvit.core.WorkspacePageAuthority
import dev.solvit.core.WorkspaceService
import dev.solvit.core.WorkspaceGitSession
import dev.solvit.core.WorkspaceRemoteGitSession
import dev.solvit.core.GitRemoteEndpoint
import dev.solvit.core.WorkspaceGitSession.Entry
import dev.solvit.core.WorkspaceGitSession.Status
import dev.solvit.core.WorkspaceGitSession.Revision
import dev.solvit.core.WorkspaceGitSession.Diff
import dev.solvit.core.WorkspaceGitSession.Commit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val onClose: () -> Unit = {},
    private val authorizeNetwork: suspend (GitRemoteEndpoint) -> Unit = { error("Git网络访问尚未授权") },
    private val authorize: suspend () -> Unit,
) : WorkspaceRemoteGitSession {
    private val binding = checkNotNull(workspaces.state.value.bindings[chatId]) { "此对话尚未绑定工作区" }
    private val mutex = Mutex()
    @Volatile private var closed = false
    private val transferLock = Any()
    private var activeTransfer: Job? = null
    private val transferProgress = MutableStateFlow<WorkspaceRemoteGitSession.Progress?>(null)
    override val progress = transferProgress.asStateFlow()
    override suspend fun transferProgress(): WorkspaceRemoteGitSession.Progress? {
        verify()
        return progress.value
    }
    internal var remoteTransport = RepositoryRemoteTransport()
    internal var borrowCredentials: (GitRemoteEndpoint) -> RepositoryCredentials? = { null }
    internal var cloneStagingDirectory: File? = null
    internal var configuredCredentials: (GitRemoteEndpoint) -> Boolean = { false }
    internal var storeCredentials: (GitRemoteEndpoint, String, CharArray) -> Unit = { _, _, _ -> error("Git凭据库尚未配置") }
    internal var deleteCredentials: (GitRemoteEndpoint) -> Unit = { error("Git凭据库尚未配置") }

    override suspend fun credentialsConfigured(endpoint: GitRemoteEndpoint): Boolean = withContext(Dispatchers.IO) {
        verify(); resolveRoot()
        configuredCredentials(endpoint).also { verify() }
    }

    override suspend fun saveCredentials(endpoint: GitRemoteEndpoint, username: String, secret: CharArray) = withContext(Dispatchers.IO) {
        mutex.withLock {
            verifyRemote(endpoint); resolveRoot()
            storeCredentials(endpoint, username, secret)
            verifyRemote(endpoint)
        }
    }

    override suspend fun removeCredentials(endpoint: GitRemoteEndpoint) = withContext(Dispatchers.IO) {
        mutex.withLock {
            verifyRemote(endpoint); resolveRoot()
            deleteCredentials(endpoint)
            verifyRemote(endpoint)
        }
    }

    private suspend fun verifyRemote(endpoint: GitRemoteEndpoint) {
        verifyWrite()
        authorizeNetwork(endpoint)
        verify()
    }

    private suspend fun <T> ownTransfer(endpoint: GitRemoteEndpoint, operation: String,
        block: suspend () -> T): T = coroutineScope {
        val ownedJob = currentCoroutineContext().job
        synchronized(transferLock) {
            check(!closed) { "Git页面已关闭" }
            check(activeTransfer == null) { "Git传输正在进行" }
            activeTransfer = ownedJob
            transferProgress.value = WorkspaceRemoteGitSession.Progress(operation, "", 0, 0)
        }
        try {
            verifyRemote(endpoint)
            block()
        } finally {
            synchronized(transferLock) {
                if (activeTransfer === ownedJob) {
                    activeTransfer = null
                    transferProgress.value = null
                }
            }
        }
    }

    private suspend fun <T> transfer(endpoint: GitRemoteEndpoint, operation: String,
        block: suspend (Git) -> T): T = ownTransfer(endpoint, operation) {
        access { git -> verifyRemote(endpoint); block(git) }
    }

    override suspend fun clone(endpoint: GitRemoteEndpoint, branch: String): WorkspaceRemoteGitSession.Fetch =
        ownTransfer(endpoint, "clone") { withContext(Dispatchers.IO) { mutex.withLock {
            require(branch.length in 1..256 && !branch.startsWith('-') && org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$branch"))
            verifyRemote(endpoint)
            val root = resolveRoot()
            val initialRoot = android.system.Os.lstat(root.path).let { it.st_dev to it.st_ino }
            fun sameRoot() {
                check(root.canonicalFile == root.absoluteFile &&
                    android.system.Os.lstat(root.path).let { it.st_dev to it.st_ino } == initialRoot) {
                    "克隆期间工作目录已被替换"
                }
            }
            fun emptyTarget() {
                sameRoot()
                check(root.isDirectory && root.canonicalFile == root.absoluteFile && root.listFiles()?.isEmpty() == true) {
                    "克隆需要空工作目录，已有文件不会被覆盖"
                }
            }
            emptyTarget()
            val base = checkNotNull(cloneStagingDirectory) { "Git克隆暂存目录尚未配置" }.canonicalFile
            check(base.mkdirs() || base.isDirectory) { "无法创建私有克隆暂存目录" }
            check(!root.toPath().startsWith(base.toPath()) && !base.toPath().startsWith(root.toPath()))
            val staged = Files.createTempDirectory(base.toPath(), "clone-").toFile()
            try {
                val fetched = Git.init().setDirectory(staged).setInitialBranch(branch).setFs(WorkspaceGitFileSystem()).call().use { git ->
                    val result = remoteTransport.fetch(git.repository, endpoint, branch, borrowCredentials(endpoint),
                        { verifyRemote(endpoint) }, { report("clone", it) })
                    // Bound inflated worktree size before checkout; never materialize remote symlinks or submodules.
                    var files = 0; var bytes = 0L
                    TreeWalk(git.repository).use { walk ->
                        walk.addTree(git.repository.resolve("${result.head}^{tree}")); walk.isRecursive = true
                        while (walk.next()) {
                            currentCoroutineContext().ensureActive()
                            checkedPath(staged, walk.pathString)
                            check(walk.getFileMode(0) in setOf(FileMode.REGULAR_FILE, FileMode.EXECUTABLE_FILE)) {
                                "克隆暂不支持符号链接或子模块"
                            }
                            val size = git.repository.newObjectReader().use { it.getObjectSize(walk.getObjectId(0), Constants.OBJ_BLOB) }
                            bytes += size
                            check(++files <= 100_000 && size <= 32L * 1024 * 1024 && bytes <= 256L * 1024 * 1024) {
                                "克隆工作树超过当前文件或大小上限"
                            }
                        }
                    }
                    verifyRemote(endpoint)
                    git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(result.head).call()
                    git.repository.config.apply {
                        setString("remote", "origin", "url", endpoint.url)
                        setString("remote", "origin", "fetch", "+refs/heads/$branch:refs/remotes/origin/$branch")
                        setString("branch", branch, "remote", "origin")
                        setString("branch", branch, "merge", "refs/heads/$branch")
                        save()
                    }
                    result
                }
                verifyRemote(endpoint)
                check(resolveRoot() == root) { "克隆期间工作目录已变化" }
                emptyTarget() // A Shell may have created files while HTTPS was blocked.
                // Preserve the root inode used by existing Shell working directories. Never use recursive cleanup on it.
                val metadata = File(staged, ".git")
                Files.walk(staged.toPath()).use { paths ->
                    val iterator = paths.filter { it != staged.toPath() && !it.startsWith(metadata.toPath()) }.sorted().iterator()
                    while (iterator.hasNext()) {
                        verifyRemote(endpoint)
                        sameRoot()
                        val source = iterator.next()
                        val target = root.toPath().resolve(staged.toPath().relativize(source))
                        check(target.toFile().canonicalFile == target.toFile().absoluteFile) { "克隆目标路径已变化" }
                        if (Files.isDirectory(source)) Files.createDirectory(target)
                        else Files.copy(source, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
                verifyRemote(endpoint)
                sameRoot()
                // No REPLACE_EXISTING or ATOMIC_MOVE: an existing destination must cause failure.
                Files.move(metadata.toPath(), File(root, ".git").toPath())
                validateRepository(root)
                verifyRemote(endpoint)
                WorkspaceRemoteGitSession.Fetch(fetched.branch, fetched.head, fetched.updates)
            } finally {
                // Only this operation's private temporary directory is owned for deletion.
                withContext(NonCancellable + Dispatchers.IO) { staged.deleteRecursively() }
            }
        } } }

    private fun report(operation: String, value: GitTransferProgress) {
        transferProgress.value = WorkspaceRemoteGitSession.Progress(operation, value.task, value.completed, value.total)
    }

    override suspend fun fetch(endpoint: GitRemoteEndpoint, branch: String) = transfer(endpoint, "fetch") { git ->
        val result = remoteTransport.fetch(git.repository, endpoint, branch, borrowCredentials(endpoint),
            { verifyRemote(endpoint) }, { report("fetch", it) })
        WorkspaceRemoteGitSession.Fetch(result.branch, result.head, result.updates)
    }

    override suspend fun reviewPush(endpoint: GitRemoteEndpoint, branch: String) = transfer(endpoint, "review") { git ->
        val local = revision(git)
        check(local.head != null && local.branch == "refs/heads/$branch") { "请选择当前本地分支，且至少有一次提交" }
        val remote = remoteTransport.fetch(git.repository, endpoint, branch, borrowCredentials(endpoint),
            { verifyRemote(endpoint) }, { report("review", it) })
        check(revision(git) == local) { "本地提交或暂存区已变化，请重新审阅" }
        WorkspaceRemoteGitSession.PushReview(endpoint, branch, local, remote.head)
    }

    override suspend fun push(review: WorkspaceRemoteGitSession.PushReview) = transfer(review.endpoint, "push") { git ->
        check(revision(git) == review.revision && review.revision.branch == "refs/heads/${review.branch}") {
            "本地提交或暂存区已变化，请重新审阅"
        }
        val head = checkNotNull(review.revision.head)
        val result = remoteTransport.push(git.repository, review.endpoint, review.branch, head, review.remoteHead,
            borrowCredentials(review.endpoint), { verifyRemote(review.endpoint) }, { report("push", it) })
        WorkspaceRemoteGitSession.Push(result.branch, result.localHead, result.status)
    }

    private fun worktreeFingerprint(root: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0
        var bytes = 0L
        Files.walk(root.toPath()).use { paths ->
            paths.filter { !it.startsWith(File(root, ".git").toPath()) }.sorted().forEach { path ->
                check(++count <= 100_000) { "工作区超过拉取审阅上限" }
                check(!Files.isSymbolicLink(path)) { "拉取审阅暂不支持符号链接" }
                val relative = root.toPath().relativize(path).toString()
                hash.update(relative.toByteArray(Charsets.UTF_8)); hash.update(0.toByte())
                if (Files.isRegularFile(path)) {
                    hash.update(1.toByte())
                    val file = path.toFile()
                    bytes += file.length()
                    check(bytes <= 64L * 1024 * 1024) { "工作区超过拉取审阅大小上限" }
                    hash.update(checkNotNull(digest(file)).toByteArray(Charsets.US_ASCII))
                    hash.update(if (file.canExecute()) 1.toByte() else 0.toByte())
                } else { check(Files.isDirectory(path)) { "工作区包含不支持的文件类型" }; hash.update(2.toByte()) }
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    override suspend fun reviewPull(endpoint: GitRemoteEndpoint, branch: String) = transfer(endpoint, "review-pull") { git ->
        val local = revision(git)
        check(local.state == "SAFE" && local.head != null && local.branch == "refs/heads/$branch") {
            "拉取需要当前分支已有提交，且没有未完成的操作"
        }
        check(git.status().call().isClean) { "请先提交工作区变化，再拉取合并" }
        val worktree = worktreeFingerprint(git.repository.workTree)
        val remote = remoteTransport.fetch(git.repository, endpoint, branch, borrowCredentials(endpoint),
            { verifyRemote(endpoint) }, { report("review-pull", it) })
        check(revision(git) == local && worktreeFingerprint(git.repository.workTree) == worktree) {
            "工作区在下载期间变化，请重新审阅"
        }
        WorkspaceRemoteGitSession.PullReview(local, remote.head, worktree)
    }

    override suspend fun merge(review: WorkspaceRemoteGitSession.PullReview) = access { git ->
        fun unchanged() {
            check(review.revision.state == "SAFE" && revision(git) == review.revision &&
                git.status().call().isClean && worktreeFingerprint(git.repository.workTree) == review.worktreeSha256) {
                "工作区或提交在拉取审阅后变化，请重新审阅"
            }
        }
        unchanged()
        verifyWrite()
        unchanged()
        val remote = RevWalk(git.repository).use { it.parseCommit(ObjectId.fromString(review.remoteHead)) }
        val result = git.merge().include(remote).setCommit(false).call()
        WorkspaceRemoteGitSession.Merge(result.mergeStatus.name, git.repository.resolve(Constants.HEAD)?.name,
            git.status().call().conflicting.sorted())
    }

    override suspend fun cancelTransfer() {
        verify() // Must not wait behind the transfer's repository mutex.
        synchronized(transferLock) { activeTransfer?.cancel() }
    }
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
    override suspend fun initialize(): Revision = withContext(Dispatchers.IO) {
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
    override suspend fun status(offset: Int, limit: Int): Status {
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
        digest(File(git.repository.directory, "index")), git.repository.fullBranch,
        git.repository.repositoryState.name, git.repository.readMergeHeads().orEmpty().map { it.name })

    /** Read a real HEAD/index or index/worktree patch without changing either tree. */
    override suspend fun diff(path: String, staged: Boolean): Diff = access { git ->
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

    override suspend fun history(limit: Int): List<Commit> {
        require(limit in 1..100)
        return access { git ->
            if (git.repository.resolve(Constants.HEAD) == null) emptyList()
            else git.log().setMaxCount(limit).call().map { commit ->
                Commit(commit.name, commit.fullMessage.take(16_384), commit.authorIdent.name.take(256), commit.commitTime)
            }
        }
    }
    override suspend fun reviewIndex(): Revision = access { git -> revision(git) }

    private suspend fun verifyWrite() {
        verify()
        authorizeWrite()
        verify()
    }
    /** Stage the exact reviewed file version; does not stage unrelated paths. */
    override suspend fun stage(review: Diff): Revision = access { git ->
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
    override suspend fun unstage(path: String, expected: Revision): Revision = access { git ->
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
    override suspend fun commit(expected: Revision, message: String, author: String, email: String): Commit = access { git ->
        require(message.isNotBlank() && message.length <= 4096 && '\u0000' !in message)
        require(author.isNotBlank() && author.length <= 80 && author.none(Char::isISOControl))
        require(email.length <= 254 && email.none(Char::isISOControl) && email.matches(Regex("[^\\s<>@]+@[^\\s<>@]+")))
        val merging = git.repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.MERGING_RESOLVED
        check(merging || git.repository.repositoryState == org.eclipse.jgit.lib.RepositoryState.SAFE) {
            "仓库仍有未解决的冲突或其他操作，请先处理并暂存解决结果"
        }
        check(revision(git) == expected) { "HEAD或暂存区已变化，请重新审阅" }
        verifyWrite()
        val cache = git.repository.lockDirCache()
        try {
            check(revision(git) == expected) { "HEAD或暂存区在获取索引锁时变化" }
            git.repository.newObjectInserter().use { inserter ->
                val tree = cache.writeTree(inserter)
                check((expected.head != null || cache.entryCount > 0) &&
                    (merging || tree != git.repository.resolve("HEAD^{tree}"))) { "暂存区没有需要提交的变化" }
                check(!merging || expected.head != null && expected.mergeHeads.isNotEmpty()) { "合并父提交缺失，请重新审阅" }
                val identity = PersonIdent(author, email)
                val commit = CommitBuilder().apply {
                    setTreeId(tree)
                    setParentIds((listOfNotNull(expected.head) + expected.mergeHeads).distinct().map(ObjectId::fromString))
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
                if (merging) {
                    git.repository.writeMergeHeads(null)
                    git.repository.writeMergeCommitMsg(null)
                }
                RevWalk(git.repository).use { walk ->
                    val result = walk.parseCommit(id)
                    Commit(result.name, result.fullMessage, result.authorIdent.name, result.commitTime)
                }
            }
        } finally { cache.unlock() }
    }
    override suspend fun close() {
        val first = synchronized(transferLock) {
            if (closed) false else {
                closed = true
                activeTransfer?.cancel()
                true
            }
        }
        mutex.withLock { if (first) onClose() }
    }
}
