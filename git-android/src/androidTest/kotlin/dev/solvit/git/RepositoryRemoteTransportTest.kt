package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.GitRemoteEndpoint
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
