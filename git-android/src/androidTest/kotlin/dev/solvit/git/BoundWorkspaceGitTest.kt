package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BoundWorkspaceGitTest {
    @Test fun reviewedStageUnstageAndCommitPreserveOtherFilesRejectStaleAndNeverRunHooks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope)
        val chat = "git-write-${UUID.randomUUID()}"
        val workspace = workspaces.forChat(chat)
        val root = File(workspaces.location(workspace.id))
        var writable = false
        val page = BoundWorkspaceGit(workspaces, chat, { chat }, authorizeWrite = { check(writable) }) { }
        suspend fun rejected(block: suspend () -> Unit) {
            try { block(); fail("Expected reviewed write rejection") } catch (_: IllegalStateException) { }
        }
        try {
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                File(root, "reviewed.txt").writeText("old\n")
                File(root, "other.txt").writeText("other old\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("Initial").setAuthor("Fixture", "test@example.invalid")
                    .setCommitter("Fixture", "test@example.invalid").call()
            }
            File(root, "reviewed.txt").writeText("reviewed\n")
            File(root, "other.txt").writeText("other untouched\n")
            val review = page.diff("reviewed.txt", false)
            val originalIndex = File(root, ".git/index").readBytes()
            rejected { page.stage(review) }
            assertArrayEquals(originalIndex, File(root, ".git/index").readBytes())
            writable = true
            File(root, "reviewed.txt").writeText("external change\n")
            rejected { page.stage(review) }
            assertArrayEquals(originalIndex, File(root, ".git/index").readBytes())
            File(root, "reviewed.txt").writeText("reviewed\n")
            val staged = page.stage(page.diff("reviewed.txt", false))
            val changes = page.status()
            assertEquals("modified", changes.entries.single { it.path == "reviewed.txt" }.staged)
            assertNull(changes.entries.single { it.path == "other.txt" }.staged)
            File(root, "reviewed.txt").writeText("new unstaged\n")
            val unstaged = page.unstage("reviewed.txt", staged)
            assertEquals("new unstaged\n", File(root, "reviewed.txt").readText())
            assertNull(page.status().entries.single { it.path == "reviewed.txt" }.staged)
            rejected { page.commit(staged, "Stale", "Fixture", "test@example.invalid") }
            val current = page.stage(page.diff("reviewed.txt", false))
            assertNotEquals(unstaged, current)
            File(root, "reviewed.txt").writeText("keep this working text\n")
            for (name in listOf("pre-commit", "commit-msg", "post-commit")) {
                File(root, ".git/hooks/$name").apply {
                    parentFile!!.mkdirs()
                    writeText("#!/system/bin/sh\nprintf unexpected > '${File(root, "hook-ran").absolutePath}'\nexit 1\n")
                    assertTrue(setExecutable(true))
                }
            }
            val committed = page.commit(current, "Only reviewed index", "Fixture", "test@example.invalid")
            assertEquals("Only reviewed index", committed.message)
            assertFalse(File(root, "hook-ran").exists())
            assertEquals("keep this working text\n", File(root, "reviewed.txt").readText())
            assertEquals("other untouched\n", File(root, "other.txt").readText())
            assertEquals(listOf("Only reviewed index", "Initial"), page.history().map { it.message })
            val remaining = page.diff("reviewed.txt", false)
            assertTrue(remaining.patch.contains("-new unstaged"))
            assertTrue(remaining.patch.contains("+keep this working text"))
            assertNull(page.status().entries.single { it.path == "other.txt" }.staged)
            assertTrue(File(root, "reviewed.txt").delete())
            val deletion = page.stage(page.diff("reviewed.txt", false))
            assertEquals("deleted", page.status().entries.single { it.path == "reviewed.txt" }.staged)
            page.unstage("reviewed.txt", deletion)
            assertFalse(File(root, "reviewed.txt").exists())
            assertEquals("deleted", page.status().entries.single { it.path == "reviewed.txt" }.working)
        } finally {
            page.close(); workspaces.unbind(chat); scope.cancel(); root.deleteRecursively()
        }
    }

    @Test fun hostBindingReadsRealChangesAndHistoryAndRejectsRevocationRebindingAndExternalMetadata() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val workspaces = AndroidWorkspaces(context, scope)
        val chat = "git-bound-${UUID.randomUUID()}"
        val workspace = workspaces.forChat(chat)
        val root = File(workspaces.location(workspace.id))
        val external = File(context.cacheDir, "git-external-${UUID.randomUUID()}").apply { mkdirs() }
        var allowed = true
        var active: String? = chat
        fun page() = BoundWorkspaceGit(workspaces, chat, { active }) { check(allowed) { "denied fixture" } }
        suspend fun rejected(block: suspend () -> Unit) {
            try { block(); fail("Expected native Git capability rejection") }
            catch (_: IllegalStateException) { }
        }
        try {
            val absent = page()
            rejected { absent.status() }
            assertFalse(File(root, ".git").exists()) // A read must never initialize a repository.
            absent.close()
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                File(root, "tracked.txt").writeText("original\n")
                git.add().addFilepattern("tracked.txt").call()
                git.commit().setMessage("Reviewed initial").setAuthor("Fixture", "test@example.invalid")
                    .setCommitter("Fixture", "test@example.invalid").call()
                File(root, "tracked.txt").writeText("staged\n")
                git.add().addFilepattern("tracked.txt").call()
                File(root, "tracked.txt").writeText("working\n")
                File(root, "new.txt").writeText("untracked\n")
            }
            val bound = page()
            val status = bound.status()
            assertEquals("refs/heads/main", status.branch)
            assertNotNull(status.head)
            assertEquals(2, status.total)
            val tracked = status.entries.single { it.path == "tracked.txt" }
            assertEquals("modified", tracked.staged); assertEquals("modified", tracked.working)
            assertEquals("untracked", status.entries.single { it.path == "new.txt" }.working)
            assertEquals(1, bound.status(limit = 1).entries.size)
            assertEquals("Reviewed initial", bound.history().single().message)
            val indexBefore = File(root, ".git/index").readBytes()
            val stagedDiff = bound.diff("tracked.txt", staged = true)
            assertTrue(stagedDiff.patch.contains("-original"))
            assertTrue(stagedDiff.patch.contains("+staged"))
            assertFalse(stagedDiff.patch.contains("+working"))
            val workingDiff = bound.diff("tracked.txt", staged = false)
            assertTrue(workingDiff.patch.contains("-staged"))
            assertTrue(workingDiff.patch.contains("+working"))
            assertEquals(stagedDiff.revision, workingDiff.revision)
            assertNotNull(workingDiff.workingSha256)
            assertArrayEquals(indexBefore, File(root, ".git/index").readBytes())
            assertEquals("working\n", File(root, "tracked.txt").readText())
            try { bound.diff("../outside", staged = false); fail("Outside path must fail") }
            catch (_: IllegalArgumentException) { }
            try { bound.diff(".git/config", staged = false); fail("Metadata path must fail") }
            catch (_: IllegalArgumentException) { }
            val worktreeLink = File(root, "outside-link").toPath()
            Files.createSymbolicLink(worktreeLink, external.toPath())
            rejected { bound.diff("outside-link/config", staged = false) }
            Files.delete(worktreeLink)
            allowed = false; rejected { bound.status() }; allowed = true
            active = "other"; rejected { bound.history() }; active = chat
            val config = File(root, ".git/config"); val original = config.readText()
            config.appendText("\n[include]\n path = ${File(external, "config").absolutePath}\n")
            rejected { bound.status() }; config.writeText(original)
            val link = File(root, ".git/objects/external-link").toPath()
            Files.createSymbolicLink(link, external.toPath())
            rejected { bound.status() }; Files.delete(link)
            assertTrue(external.isDirectory)
            val second = workspaces.create("second")
            workspaces.bind(chat, second.id)
            assertNotEquals(workspace.id, second.id)
            rejected { bound.status() }
            bound.close(); rejected { bound.status() }
        } finally {
            workspaces.unbind(chat); scope.cancel(); root.deleteRecursively(); external.deleteRecursively()
        }
    }
}
