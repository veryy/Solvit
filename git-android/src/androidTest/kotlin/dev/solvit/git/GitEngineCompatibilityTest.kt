package dev.solvit.git

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.io.File
import java.util.UUID

/** Real Android storage and Git index/objects; no mocked repository or installed git executable. */
@RunWith(AndroidJUnit4::class)
class GitEngineCompatibilityTest {
    @Test fun androidTransferPreservesShortAndZeroReadsAndErrorsWithoutClosingEitherStream() {
        val bytes = ByteArray(17_000) { (it % 251).toByte() }
        var inputClosed = false; var outputClosed = false
        val input = object : ByteArrayInputStream(bytes) {
            var reads = 0
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                if (++reads % 2 == 0) 0 else super.read(target, offset, minOf(length, 7))
            override fun close() { inputClosed = true; super.close() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        assertEquals(bytes.size.toLong(), AndroidInputStreams.transferTo(input, output))
        assertArrayEquals(bytes, output.toByteArray())
        assertFalse(inputClosed); assertFalse(outputClosed)
        assertEquals(0L, AndroidInputStreams.transferTo(ByteArrayInputStream(byteArrayOf()), output))
        val readError = object : InputStream() { override fun read(): Int = throw IOException("fixture read failure") }
        try { AndroidInputStreams.transferTo(readError, output); fail("Read errors must propagate") }
        catch (error: IOException) { assertEquals("fixture read failure", error.message) }
        val writeError = object : OutputStream() { override fun write(value: Int) { throw IOException("fixture write failure") } }
        try { AndroidInputStreams.transferTo(ByteArrayInputStream(bytes), writeError); fail("Write errors must propagate") }
        catch (error: IOException) { assertEquals("fixture write failure", error.message) }
    }

    @Test fun androidStreamReadsHonorBoundsShortReadsAndLeaveTheStreamOpen() {
        var closed = false
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 1))
            override fun close() { closed = true; super.close() }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), AndroidInputStreams.readNBytes(input, 3))
        assertEquals(4, input.read())
        val target = ByteArray(4) { 9 }
        assertEquals(1, AndroidInputStreams.readNBytes(input, target, 1, 2))
        assertArrayEquals(byteArrayOf(9, 5, 9, 9), target)
        assertEquals(0, AndroidInputStreams.readNBytes(input, target, 0, 0))
        assertArrayEquals(byteArrayOf(), AndroidInputStreams.readAllBytes(input))
        assertFalse(closed)
        try { AndroidInputStreams.readNBytes(input, -1); fail("Negative length must fail") }
        catch (_: IllegalArgumentException) {}
        try { AndroidInputStreams.readNBytes(input, target, 3, 2); fail("Invalid bounds must fail") }
        catch (_: IndexOutOfBoundsException) {}
        val broken = object : InputStream() { override fun read(): Int = throw IOException("fixture read failure") }
        try { AndroidInputStreams.readAllBytes(broken); fail("Read errors must propagate") }
        catch (error: IOException) { assertEquals("fixture read failure", error.message) }
    }

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
                git.gc().call() // Actual packed objects, not only loose-file reads.
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
