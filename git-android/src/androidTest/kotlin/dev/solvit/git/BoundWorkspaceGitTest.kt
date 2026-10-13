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
