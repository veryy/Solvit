package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real Android storage and Git index/objects; no mocked repository or installed git executable. */
@RunWith(AndroidJUnit4::class)
class GitEngineCompatibilityTest {
    @Test fun realRepositoryPreservesSeparateIndexAndWorktreeChangesAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "git-compat-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        val path = "目录/[note].txt"
        val file = File(root, path)
        try {
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                assertEquals("main", git.repository.branch)
                assertTrue(file.parentFile!!.mkdirs())
                file.writeText("original 中文 😀\n")
                assertEquals(setOf(path), git.status().call().untracked)
                git.add().addFilepattern(path).call()
                assertEquals(setOf(path), git.status().call().added)
                git.commit().setMessage("Initial document").setAuthor("Solvit test", "test@example.invalid")
                    .setCommitter("Solvit test", "test@example.invalid").call()
                assertTrue(git.status().call().isClean)
                file.writeText("staged 中文 😀\n")
                assertEquals(setOf(path), git.status().call().modified)
                git.add().addFilepattern(path).call()
                file.writeText("unstaged 中文 😀\n")
                val status = git.status().call()
                assertEquals(setOf(path), status.changed)
                assertEquals(setOf(path), status.modified)
                val staged = ByteArrayOutputStream()
                git.diff().setCached(true).setOutputStream(staged).call()
                assertTrue(staged.toString("UTF-8").contains("+staged 中文 😀"))
                val working = ByteArrayOutputStream()
                git.diff().setOutputStream(working).call()
                assertTrue(working.toString("UTF-8").contains("+unstaged 中文 😀"))
                git.commit().setMessage("Commit only reviewed index").setAuthor("Solvit test", "test@example.invalid")
                    .setCommitter("Solvit test", "test@example.invalid").call()
                assertEquals("unstaged 中文 😀\n", file.readText())
                assertEquals(setOf(path), git.status().call().modified)
            }
            Git.open(root).use { git ->
                assertEquals("main", git.repository.branch)
                assertEquals(setOf(path), git.status().call().modified)
                assertEquals(listOf("Commit only reviewed index", "Initial document"), git.log().call().map { it.fullMessage })
                assertTrue(file.delete())
                assertEquals(setOf(path), git.status().call().missing)
                git.add().addFilepattern(path).setUpdate(true).call()
                assertEquals(setOf(path), git.status().call().removed)
            }
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }
}
