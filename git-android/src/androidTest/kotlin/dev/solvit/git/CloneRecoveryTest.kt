package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.workspace.AndroidWorkspaces
import dev.solvit.workspace.AndroidWorkspaceDirectoryPublisher
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Actual Android inode publication and bound native service. External force-stop and
 * plugin HTML are separate acceptance gates, not simulated by these cancellation tests. */
@RunWith(AndroidJUnit4::class)
class CloneRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Fixture(context: android.content.Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope)
        val chat = "clone-recovery-${UUID.randomUUID()}"
        lateinit var workspace: dev.solvit.core.Workspace
        lateinit var root: File
        suspend fun prepare() {
            workspace = workspaces.forChat(chat)
            root = File(workspaces.location(workspace.id)).canonicalFile
        }
        val base = File(context.noBackupFilesDir, "clone-recovery-test-${UUID.randomUUID()}").canonicalFile
        var active: String? = chat
        var readable = true
        var writable = true
        var networkCalls = 0
        fun page() = BoundWorkspaceGit(workspaces, chat, { active }, authorizeWrite = { check(writable) },
            authorizeNetwork = { networkCalls++; error("Offline recovery must not authorize network") }) { check(readable) }
            .also { it.cloneStagingDirectory = base }
        val store = CloneInstallTransactions(base, AndroidWorkspaceDirectoryPublisher::publish)
        suspend fun ready(): CloneInstallTransactions.Record {
            val r = store.begin(workspace.id, root, "https://github.com/veryy/Solvit", "main")
            val tree = store.tree(r)
            val head = Git.init().setDirectory(tree).setInitialBranch("main").setFs(WorkspaceGitFileSystem()).call().use { git ->
                File(tree, "a.txt").writeText("complete file\n")
                File(tree, "nested").mkdir(); File(tree, "nested/b.txt").writeText("nested file\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Recovery fixture").setAuthor("Solvit test", "test@example.invalid")
                    .setCommitter("Solvit test", "test@example.invalid").call().name
            }
            return store.seal(r.id, head)
        }
        suspend fun close() {
            workspaces.unbind(chat); scope.cancel()
            if (::root.isInitialized) check(root.deleteRecursively())
            if (base.exists()) check(base.deleteRecursively())
        }
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(context); try { f.prepare(); block(f) } finally { f.close() }
    }

    @Test fun productionDirectoryPublisherNeverReplacesExistingDirectoryFileOrLink() {
        val parent = File(context.cacheDir, "directory-publisher-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val from = File(parent, "source").apply { check(mkdir()); File(this, "source.txt").writeText("source") }
        val target = File(parent, ".git").apply { check(mkdir()); File(this, "keep.txt").writeText("keep") }
        try {
            try { AndroidWorkspaceDirectoryPublisher.publish(from, target); fail("Existing .git cannot be replaced") }
            catch (_: java.nio.file.FileAlreadyExistsException) { }
            assertEquals("keep", File(target, "keep.txt").readText())
            assertEquals("source", File(from, "source.txt").readText())
            assertTrue(target.deleteRecursively()); target.writeText("same-name file")
            try { AndroidWorkspaceDirectoryPublisher.publish(from, target); fail("Existing file cannot be replaced") }
            catch (_: java.nio.file.FileAlreadyExistsException) { }
            assertEquals("same-name file", target.readText()); assertTrue(target.delete())
            Files.createSymbolicLink(target.toPath(), from.toPath())
            try { AndroidWorkspaceDirectoryPublisher.publish(from, target); fail("Existing symlink cannot be replaced") }
            catch (_: java.nio.file.FileAlreadyExistsException) { }
            assertTrue(Files.isSymbolicLink(target.toPath())); Files.delete(target.toPath())
            val original = android.system.Os.lstat(from.path).st_ino
            AndroidWorkspaceDirectoryPublisher.publish(from, target)
            assertFalse(from.exists()); assertEquals(original, android.system.Os.lstat(target.path).st_ino)
            assertEquals("source", File(target, "source.txt").readText())
        } finally { parent.deleteRecursively() }
    }

    @Test fun reconstructedSessionExplicitlyResumesOfflineAndPreservesRootAndShellCwd() = runBlocking<Unit> {
        fixture { f ->
            val r = f.ready(); val rootInode = android.system.Os.lstat(f.root.path).st_ino
            try {
                f.store.install(r.id) { if (File(f.root, "a.txt").exists()) throw CancellationException("after first hard link") }
                fail("Must retain interrupted installation")
            } catch (_: CancellationException) { }
            val shell = ProcessBuilder("/system/bin/sh").directory(f.root).start()
            val page = f.page()
            try {
                assertEquals("INSTALL_INTERRUPTED", page.cloneInstalls().entries.single().status)
                assertFalse(File(f.root, "nested/b.txt").exists())
                val review = page.reviewCloneInstall(r.id)
                assertTrue(review.canResume); assertFalse(review.canRestart)
                assertEquals(2, review.files); assertEquals(1, review.installedFiles)
                assertEquals(r.head, page.resumeCloneInstall(review).head)
                assertEquals(0, f.networkCalls)
                assertEquals(rootInode, android.system.Os.lstat(f.root.path).st_ino)
                assertEquals(r.head, page.status().head); assertTrue(page.status().entries.isEmpty())
                assertTrue(page.cloneInstalls().entries.isEmpty())
                shell.outputStream.bufferedWriter().use { it.write("cat nested/b.txt\nexit\n"); it.flush() }
                assertTrue(shell.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, shell.exitValue())
                assertEquals("nested file\n", shell.inputStream.bufferedReader().use { it.readText() })
            } finally { shell.destroy(); page.close() }
        }
    }

    @Test fun changedFilesAndRevokedOrChangedChatCannotReplayOrDiscardAndOtherWorkspaceCannotReadRecord() = runBlocking<Unit> {
        fixture { f ->
            val r = f.ready(); val page = f.page()
            try {
                val review = page.reviewCloneInstall(r.id)
                File(f.root, "user.txt").writeText("do not replace")
                try { page.resumeCloneInstall(review); fail("Stale review must be rejected") } catch (_: IllegalStateException) { }
                assertFalse(File(f.root, ".git").exists()); assertFalse(File(f.root, "a.txt").exists())
                val conflict = page.reviewCloneInstall(r.id); assertFalse(conflict.canResume)
                f.writable = false
                try { page.discardCloneInstall(conflict); fail("Write revocation must stop discard") } catch (_: IllegalStateException) { }
                assertTrue(f.base.listFiles()!!.isNotEmpty()); f.writable = true
                f.active = "another-chat"
                try { page.cloneInstalls(); fail("Inactive chat cannot read records") } catch (_: IllegalStateException) { }
                f.active = f.chat
                val otherChat = "other-${UUID.randomUUID()}"
                val otherWorkspace = f.workspaces.forChat(otherChat)
                val otherRoot = File(f.workspaces.location(otherWorkspace.id))
                val other = BoundWorkspaceGit(f.workspaces, otherChat, { otherChat }, authorizeWrite = {}) {}
                    .also { it.cloneStagingDirectory = f.base }
                try {
                    assertTrue(other.cloneInstalls().entries.isEmpty())
                    try { other.reviewCloneInstall(r.id); fail("Another workspace cannot inspect this record") } catch (_: IllegalStateException) { }
                    try { other.discardCloneInstall(conflict); fail("Another workspace cannot discard this record") } catch (_: IllegalStateException) { }
                } finally { other.close(); f.workspaces.unbind(otherChat); otherRoot.deleteRecursively() }
                page.discardCloneInstall(conflict)
                assertEquals("do not replace", File(f.root, "user.txt").readText())
                assertEquals(listOf("user.txt"), f.root.listFiles()!!.map { it.name })
                assertTrue(page.cloneInstalls().entries.isEmpty()); assertEquals(0, f.networkCalls)
            } finally { page.close() }
        }
    }

    @Test fun publishedMetadataIsAcknowledgedWithoutRewritingLaterUserChanges() = runBlocking<Unit> {
        fixture { f ->
            val r = f.ready()
            try {
                f.store.install(r.id) { if (File(f.root, ".git").exists()) throw CancellationException("after actual native rename") }
                fail("Must retain publication fact")
            } catch (_: CancellationException) { }
            assertEquals("PUBLISHING", f.store.read(r.id).phase)
            assertTrue(File(f.root, "a.txt").delete()); File(f.root, "user.txt").writeText("later user file")
            File(f.root, "nested/b.txt").writeText("later edit")
            val page = f.page()
            try {
                assertEquals("PUBLISHED", page.cloneInstalls().entries.single().status)
                val review = page.reviewCloneInstall(r.id); assertTrue(review.canResume)
                page.resumeCloneInstall(review)
                assertFalse(File(f.root, "a.txt").exists()); assertEquals("later edit", File(f.root, "nested/b.txt").readText())
                assertEquals("later user file", File(f.root, "user.txt").readText()); assertEquals(0, f.networkCalls)
                assertTrue(page.cloneInstalls().entries.isEmpty()); assertEquals(r.head, page.status().head)
                assertTrue(page.status().entries.isNotEmpty())
            } finally { page.close() }
        }
    }
}
