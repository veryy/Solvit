package dev.solvit.git

import dev.solvit.core.GitRemoteEndpoint
import kotlinx.coroutines.*
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.*
import javax.net.ssl.HttpsURLConnection

internal data class GitTransferProgress(val task: String, val completed: Int, val total: Int)
internal data class GitFetchEvidence(val branch: String, val head: String, val updates: Map<String, String>)
internal data class GitPushEvidence(val branch: String, val localHead: String, val status: String)
internal class GitRemoteFailure(val reason: String, val httpStatus: Int?) : Exception(
    "Git传输失败：$reason" + (httpStatus?.let { "（HTTP $it）" } ?: "")
)

/** Native primitive; the bound session must supply authorization and captured repository revisions. */
internal class RepositoryRemoteTransport(private val fixtureTls: ((HttpsURLConnection) -> Unit)? = null) {
    suspend fun fetch(repository: Repository, endpoint: GitRemoteEndpoint, branch: String,
        credentials: RepositoryCredentials?, authorize: suspend () -> Unit,
        progress: (GitTransferProgress) -> Unit = {}): GitFetchEvidence {
        return run(repository, endpoint, credentials, authorize, progress) { transport, monitor, repo ->
            checkBranch(branch)
            val target = "refs/remotes/origin/$branch"
            val result = transport.fetch(monitor, listOf(RefSpec("+refs/heads/$branch:$target")))
            val head = result.getAdvertisedRef("refs/heads/$branch")?.objectId
                ?: throw GitRemoteFailure("BRANCH_NOT_FOUND", null)
            check(repo.resolve(target) == head) { "Git远程引用未完成更新" }
            GitFetchEvidence(branch, head.name, result.trackingRefUpdates.associate { it.localName to it.result.name })
        }
    }

    suspend fun push(repository: Repository, endpoint: GitRemoteEndpoint, branch: String,
        localHead: String, expectedRemoteHead: String?, credentials: RepositoryCredentials?,
        authorize: suspend () -> Unit, progress: (GitTransferProgress) -> Unit = {}): GitPushEvidence {
        return run(repository, endpoint, credentials, authorize, progress) { transport, monitor, repo ->
            checkBranch(branch); require(ObjectId.isId(localHead))
            require(expectedRemoteHead == null || ObjectId.isId(expectedRemoteHead))
            check(repo.resolve("HEAD")?.name == localHead) { "Git提交在推送前变化，请重新审阅" }
            val update = RemoteRefUpdate(repo, localHead, "refs/heads/$branch", false, null,
                expectedRemoteHead?.let(ObjectId::fromString) ?: ObjectId.zeroId())
            val result = transport.push(monitor, listOf(update))
            val actual = checkNotNull(result.getRemoteUpdate("refs/heads/$branch"))
            GitPushEvidence(branch, localHead, actual.status.name)
        }
    }

    private fun checkBranch(branch: String) {
        require(branch.length in 1..256 && !branch.startsWith('-') && Repository.isValidRefName("refs/heads/$branch")) {
            "Git分支名称无效"
        }
    }

    private suspend fun <T> run(repository: Repository, endpoint: GitRemoteEndpoint,
        credentials: RepositoryCredentials?, authorize: suspend () -> Unit,
        progress: (GitTransferProgress) -> Unit,
        action: (Transport, ProgressMonitor, Repository) -> T): T = try { withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        val connections = GitHttpsConnections(endpoint, { job.ensureActive() }, fixtureTls)
        var task = ""; var completed = 0; var total = 0
        val monitor = object : ProgressMonitor {
            override fun start(totalTasks: Int) {}
            @Synchronized override fun beginTask(title: String, totalWork: Int) {
                task = title.filterNot(Char::isISOControl).take(128); completed = 0; total = totalWork
                progress(GitTransferProgress(task, completed, total))
            }
            @Synchronized override fun update(value: Int) { completed += value; progress(GitTransferProgress(task, completed, total)) }
            override fun endTask() {}
            override fun isCancelled() = !job.isActive
            override fun showDuration(enabled: Boolean) {}
        }
        // Authority can be revoked while a blocking socket read is in flight.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { while (isActive) { authorize(); delay(100) } } finally { connections.close() }
        }
        try {
            authorize(); job.ensureActive()
            val builder = FileRepositoryBuilder().setGitDir(repository.directory).setWorkTree(repository.workTree)
                .setFS(WorkspaceGitFileSystem()).setMustExist(true).setup()
            val result = GitTransportRepository(builder).use { repo ->
                Transport.open(repo, URIish(endpoint.url)).use { transport ->
                    check(transport is TransportHttp) { "Git仅支持HTTPS传输" }
                    transport.setHttpConnectionFactory(connections)
                    transport.credentialsProvider = credentials // Null explicitly disables the global provider.
                    transport.timeout = 30
                    transport.setCheckFetchedObjects(true)
                    transport.tagOpt = TagOpt.NO_TAGS
                    transport.setAdditionalHeaders(emptyMap())
                    action(transport, monitor, repo)
                }
            }
            authorize(); job.ensureActive(); result
        } catch (error: CancellationException) { throw error }
        catch (error: GitRemoteFailure) { throw error }
        catch (error: Exception) {
            job.ensureActive()
            // Do not expose remote stderr or nested exception messages containing credentials.
            val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
            val reason = when {
                connections.lastStatus in listOf(401, 403) -> "AUTHENTICATION"
                connections.lastStatus == 404 -> "NOT_FOUND"
                causes.any { it is GitTransferLimitException } -> "TRANSFER_LIMIT"
                causes.any { it is javax.net.ssl.SSLException } -> "TLS_VERIFICATION"
                causes.any { it is java.net.SocketTimeoutException } -> "TIMEOUT"
                else -> error.javaClass.simpleName.take(80)
            }
            throw GitRemoteFailure(reason, connections.lastStatus)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { cancellation.cancelAndJoin() } finally { connections.close() }
            }
        }
    } } finally {
        // Ownership starts before dispatching to IO; cancellation can prevent that block entering.
        withContext(NonCancellable + Dispatchers.IO) { credentials?.close() }
    }
}
