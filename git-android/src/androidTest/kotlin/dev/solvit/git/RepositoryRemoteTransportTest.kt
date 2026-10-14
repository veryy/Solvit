package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.GitRemoteEndpoint
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.RepositoryState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Collections
import java.util.UUID
import javax.net.ssl.*

/** Actual Android JGit packets against a TLS git http-backend on the CI host. */
@RunWith(AndroidJUnit4::class)
class RepositoryRemoteTransportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtureUrl = "https://localhost:19443"
    private val tls: (HttpsURLConnection) -> Unit by lazy {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val certificate = assets.open("git-fixture.crt").use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        return@lazy { connection: HttpsURLConnection -> connection.sslSocketFactory = ssl.socketFactory }
    }
    private fun endpoint(name: String) = GitRemoteEndpoint.parse("$fixtureUrl/$name.git")
    private fun credentials(endpoint: GitRemoteEndpoint) = RepositoryCredentials(endpoint, "fixture-user", "fixture-secret".toCharArray())
    private suspend fun control(path: String): String = withContext(Dispatchers.IO) {
        val connection = URL(fixtureUrl + path).openConnection() as HttpsURLConnection
        tls(connection); connection.connectTimeout = 3_000; connection.readTimeout = 3_000
        try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
    }
    private suspend fun <T> repository(block: suspend (Git, File) -> T): T {
        val root = File(context.filesDir, "git-http-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            return Git.init().setDirectory(root).setInitialBranch("main").setFs(WorkspaceGitFileSystem())
                .call().use { block(it, root) }
        } finally { assertTrue(root.deleteRecursively()) }
    }
    private fun commit(git: Git, root: File, content: String): String {
        File(root, "content.txt").writeText(content)
        git.add().addFilepattern("content.txt").call()
        return git.commit().setMessage("Actual native change").setAuthor("Solvit test", "test@example.invalid")
            .setCommitter("Solvit test", "test@example.invalid").call().name
    }

    @Test fun boundSessionRequiresNetworkPreservesFilesAndPushesOnlyReviewedRevision() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope)
        val chat = "git-remote-bound-${UUID.randomUUID()}"
        val workspace = workspaces.forChat(chat)
        val root = File(workspaces.location(workspace.id))
        var active: String? = chat
        var networkAllowed = false
        val remote = endpoint("bound")
        val page = BoundWorkspaceGit(workspaces, chat, { active }, authorizeWrite = {},
            authorizeNetwork = { target -> assertEquals(remote.url, target.url); check(networkAllowed) }) { }
        page.remoteTransport = RepositoryRemoteTransport(tls)
        val vault = GitCredentialVault(context)
        val secret = "fixture-secret".toCharArray()
        try {
            vault.save(remote, "fixture-user", secret)
            page.borrowCredentials = { vault.borrow(it) }
            page.initialize()
            File(root, "keep.txt").writeText("concurrent Shell file\n")
            try { page.fetch(remote, "main"); fail("Network must have separate authorization") }
            catch (_: IllegalStateException) { }
            assertNull(page.status().head)
            networkAllowed = true
            val fetched = page.fetch(remote, "main")
            assertNull(page.status().head)
            assertFalse(File(root, "content.txt").exists())
            assertEquals("concurrent Shell file\n", File(root, "keep.txt").readText())
            Git.open(root).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(fetched.head).call()
            }
            File(root, "content.txt").writeText("bound commit 中文 😀\n")
            val staged = page.stage(page.diff("content.txt", false))
            val committed = page.commit(staged, "Bound reviewed commit", "Fixture", "test@example.invalid")
            val review = page.reviewPush(remote, "main")
            File(root, "another.txt").writeText("change reviewed index\n")
            val changed = page.stage(page.diff("another.txt", false))
            try { page.push(review); fail("Stale reviewed index must fail") }
            catch (_: IllegalStateException) { }
            page.unstage("another.txt", changed)
            val fresh = page.reviewPush(remote, "main")
            val pushed = page.push(fresh)
            assertEquals("OK", pushed.status)
            assertEquals(committed.id, pushed.head)
            repository { git, _ ->
                val actual = RepositoryRemoteTransport(tls).fetch(git.repository, remote, "main", credentials(remote), {})
                assertEquals(committed.id, actual.head)
            }
            active = "another-chat"
            try { page.fetch(remote, "main"); fail("Changed chat must reject network operation") }
            catch (_: IllegalStateException) { }
            assertEquals("concurrent Shell file\n", File(root, "keep.txt").readText())
            assertEquals("change reviewed index\n", File(root, "another.txt").readText())
            assertNull(page.progress.value)
        } finally {
            secret.fill('\u0000'); vault.remove(remote)
            page.close(); workspaces.unbind(chat); scope.cancel(); root.deleteRecursively()
        }
    }

    @Test fun boundPullReviewRejectsChangedFilesThenResolvesAndPushesBothHistories() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope)
        val chat = "git-bound-pull-${UUID.randomUUID()}"
        val root = File(workspaces.location(workspaces.forChat(chat).id))
        val remote = endpoint("bound-pull")
        val page = BoundWorkspaceGit(workspaces, chat, { chat }, authorizeWrite = {}, authorizeNetwork = {}) { }
        val engine = RepositoryRemoteTransport(tls)
        page.remoteTransport = engine
        page.borrowCredentials = { credentials(it) }
        try {
            page.initialize()
            val base = page.fetch(remote, "main")
            val local = Git.open(root).use { git ->
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.head).call()
                commit(git, root, "local branch\n")
            }
            val remoteCommit = repository { git, directory ->
                engine.fetch(git.repository, remote, "main", credentials(remote), {})
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(base.head).call()
                val id = commit(git, directory, "remote branch\n")
                assertEquals("OK", engine.push(git.repository, remote, "main", id, base.head, credentials(remote), {}).status)
                id
            }
            val review = page.reviewPull(remote, "main")
            File(root, "content.txt").writeText("Shell wrote after review\n")
            try { page.merge(review); fail("Changed workspace must reject reviewed merge") }
            catch (_: IllegalStateException) { }
            assertEquals("Shell wrote after review\n", File(root, "content.txt").readText())
            assertEquals(local, page.status().head)
            File(root, "content.txt").writeText("local branch\n")
            val result = page.merge(page.reviewPull(remote, "main"))
            assertEquals("CONFLICTING", result.status)
            assertEquals(listOf("content.txt"), result.conflicts)
            assertTrue(File(root, "content.txt").readText().contains("<<<<<<<"))
            File(root, "content.txt").writeText("resolved local and remote\n")
            val resolved = page.stage(page.diff("content.txt", false))
            val merged = page.commit(resolved, "Resolve pull", "Fixture", "test@example.invalid")
            Git.open(root).use { git ->
                org.eclipse.jgit.revwalk.RevWalk(git.repository).use { walk ->
                    val actual = walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(merged.id))
                    assertEquals(listOf(local, remoteCommit), actual.parents.map { it.name })
                }
                assertEquals(RepositoryState.SAFE, git.repository.repositoryState)
            }
            assertEquals("OK", page.push(page.reviewPush(remote, "main")).status)
            repository { git, directory ->
                val actual = engine.fetch(git.repository, remote, "main", credentials(remote), {})
                assertEquals(merged.id, actual.head)
                git.reset().setMode(ResetCommand.ResetType.HARD).setRef(actual.head).call()
                assertEquals("resolved local and remote\n", File(directory, "content.txt").readText())
            }
        } finally { page.close(); workspaces.unbind(chat); scope.cancel(); root.deleteRecursively() }
    }

    @Test fun closingBoundSessionCancelsTransferWithoutWaitingForRepositoryLock() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope)
        val chat = "git-remote-close-${UUID.randomUUID()}"
        val root = File(workspaces.location(workspaces.forChat(chat).id))
        val remote = endpoint("bound-slow")
        val page = BoundWorkspaceGit(workspaces, chat, { chat }, authorizeWrite = {}, authorizeNetwork = {}) { }
        page.remoteTransport = RepositoryRemoteTransport(tls)
        page.borrowCredentials = { credentials(it) }
        try {
            page.initialize()
            File(root, "keep.txt").writeText("must survive closing\n")
            val pending = async(Dispatchers.IO) { page.fetch(remote, "main") }
            withTimeout(5_000) { while (control("/control/bound-slow-started") != "true") delay(25) }
            assertNotNull(page.progress.value)
            withTimeout(2_000) { page.close(); pending.join() }
            assertTrue(pending.isCancelled)
            assertNull(page.progress.value)
            assertEquals("must survive closing\n", File(root, "keep.txt").readText())
            try { page.status(); fail("Closed session must reject reads") }
            catch (_: IllegalStateException) { }
        } finally {
            page.close(); workspaces.unbind(chat); scope.cancel(); root.deleteRecursively()
        }
    }

    @Test fun realHttpsFetchPushAndNonFastForwardPreserveRemoteCommitsWithoutInheritedHeaders() = runBlocking<Unit> {
        repository { git, root ->
            val endpoint = endpoint("roundtrip")
            val config = git.repository.config
            config.setBoolean("http", null, "sslVerify", false)
            config.setString("http", null, "extraHeader", "X-Workspace-Leak: must-not-be-sent")
            config.setString("http", null, "cookieFile", File(root, "must-not-read-cookies").path)
            config.save()
            val originalConfig = File(root, ".git/config").readText()
            val progress = Collections.synchronizedList(mutableListOf<GitTransferProgress>())
            val engine = RepositoryRemoteTransport(tls)
            val fetched = engine.fetch(git.repository, endpoint, "main", credentials(endpoint), {}, { progress.add(it); Unit })
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(fetched.head).call()
            assertEquals("original 中文 😀\n", File(root, "content.txt").readText())
            val local = commit(git, root, "pushed from Android 中文 😀\n")
            val pushed = engine.push(git.repository, endpoint, "main", local, fetched.head, credentials(endpoint), {})
            assertEquals("OK", pushed.status)
            assertTrue(progress.isNotEmpty())
            repository { second, secondRoot ->
                val actual = engine.fetch(second.repository, endpoint, "main", credentials(endpoint), {})
                assertEquals(local, actual.head)
                second.reset().setMode(ResetCommand.ResetType.HARD).setRef(actual.head).call()
                assertEquals("pushed from Android 中文 😀\n", File(secondRoot, "content.txt").readText())
            }
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(fetched.head).call()
            val divergent = commit(git, root, "divergent local\n")
            val rejected = engine.push(git.repository, endpoint, "main", divergent, local, credentials(endpoint), {})
            assertEquals("REJECTED_NONFASTFORWARD", rejected.status)
            val unchanged = engine.fetch(git.repository, endpoint, "main", credentials(endpoint), {})
            assertEquals(local, unchanged.head)
            assertEquals("divergent local\n", File(root, "content.txt").readText())
            assertEquals(originalConfig, File(root, ".git/config").readText())
            assertFalse(control("/control/records").contains("\"workspaceHeaderLeaked\": true"))
        }
    }

    @Test fun actualRemoteConflictCanBeEditedCommittedAndPushedWithBothParentsPreserved() = runBlocking<Unit> {
        repository { left, leftRoot -> repository { right, rightRoot ->
            val endpoint = endpoint("conflict"); val engine = RepositoryRemoteTransport(tls)
            val original = engine.fetch(left.repository, endpoint, "main", credentials(endpoint), {}).head
            engine.fetch(right.repository, endpoint, "main", credentials(endpoint), {})
            left.reset().setMode(ResetCommand.ResetType.HARD).setRef(original).call()
            right.reset().setMode(ResetCommand.ResetType.HARD).setRef(original).call()
            val remote = commit(left, leftRoot, "remote conflicting line\n")
            assertEquals("OK", engine.push(left.repository, endpoint, "main", remote, original, credentials(endpoint), {}).status)
            val local = commit(right, rightRoot, "local conflicting line\n")
            val fetched = engine.fetch(right.repository, endpoint, "main", credentials(endpoint), {})
            val merge = right.merge().include(org.eclipse.jgit.lib.ObjectId.fromString(fetched.head)).setCommit(false).call()
            assertEquals(MergeResult.MergeStatus.CONFLICTING, merge.mergeStatus)
            assertEquals(setOf("content.txt"), right.status().call().conflicting)
            assertEquals(RepositoryState.MERGING, right.repository.repositoryState)
            assertTrue(File(rightRoot, "content.txt").readText().contains("<<<<<<<"))
            val resolved = commit(right, rightRoot, "resolved after actual conflict\n")
            val actualCommit = right.log().call().first()
            assertEquals(setOf(local, remote), actualCommit.parents.map { it.name }.toSet())
            assertEquals("OK", engine.push(right.repository, endpoint, "main", resolved, remote, credentials(endpoint), {}).status)
            repository { fresh, freshRoot ->
                val downloaded = engine.fetch(fresh.repository, endpoint, "main", credentials(endpoint), {})
                assertEquals(resolved, downloaded.head)
                fresh.reset().setMode(ResetCommand.ResetType.HARD).setRef(downloaded.head).call()
                assertEquals("resolved after actual conflict\n", File(freshRoot, "content.txt").readText())
            }
        } }
    }

    @Test fun authenticationFailureAndRedirectAreExplicitAndTheNextAuthorizedFetchStillWorks() = runBlocking<Unit> {
        repository { git, _ ->
            val engine = RepositoryRemoteTransport(tls); val endpoint = endpoint("auth")
            try { engine.fetch(git.repository, endpoint, "main", null, {}); fail("Anonymous fetch must fail") }
            catch (failure: GitRemoteFailure) {
                assertEquals("AUTHENTICATION", failure.reason); assertEquals(401, failure.httpStatus)
                assertFalse(failure.toString().contains("fixture-secret")); assertNull(failure.cause)
            }
            val redirect = endpoint("redirect")
            try { engine.fetch(git.repository, redirect, "main", credentials(redirect), {}); fail("Redirect must not follow") }
            catch (failure: GitRemoteFailure) { assertEquals(302, failure.httpStatus) }
            assertNotNull(engine.fetch(git.repository, endpoint, "main", credentials(endpoint), {}).head)
        }
    }

    @Test fun cancelWhileTheActualTlsBackendIsWaitingStopsAndKeepsExistingWorkspaceBytes() = runBlocking<Unit> {
        repository { git, root ->
            val endpoint = endpoint("slow"); File(root, "keep.txt").writeText("existing workspace bytes")
            val pending = launch {
                RepositoryRemoteTransport(tls).fetch(git.repository, endpoint, "main", credentials(endpoint), {})
            }
            withTimeout(5_000) { while (control("/control/slow-started") != "true") delay(20) }
            withTimeout(2_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertNull(git.repository.resolve("HEAD"))
            assertEquals("existing workspace bytes", File(root, "keep.txt").readText())
        }
    }
}
