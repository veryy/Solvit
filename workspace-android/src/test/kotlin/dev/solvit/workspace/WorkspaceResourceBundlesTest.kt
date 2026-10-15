package dev.solvit.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorkspaceResourceBundlesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun publish(source: File, destination: File) {
        check(!destination.exists())
        Files.move(source.toPath(), destination.toPath())
    }
    @Test fun publishesCompleteUtf8CopyWithoutExecutingOrReplacingAndReturnsOrderIndependentDigest() = runBlocking<Unit> {
        val base = temporary.newFolder("workspace"); val cache = temporary.newFolder("cache")
        File(base, "keep.py").writeText("original")
        val files = linkedMapOf("scripts/main.py" to "print('中文🌌')", "references/input.txt" to "data")
        val first = WorkspaceResourceBundles.prepare(base, cache, "example", files, ::publish) { }
        val second = WorkspaceResourceBundles.prepare(base, cache, "example", files.toList().reversed().toMap(), ::publish) { }
        assertNotEquals(first.path, second.path)
        assertEquals(first.sourceSha256, second.sourceSha256)
        assertEquals(2, first.files)
        assertEquals(files.values.sumOf { it.toByteArray().size.toLong() }, first.bytes)
        for ((path, text) in files) assertEquals(text, File(base, "${first.path}/$path").readText())
        assertEquals("original", File(base, "keep.py").readText())
        assertTrue(cache.list()!!.isEmpty())
    }
    @Test fun rejectsTraversalPathCollisionsMalformedUnicodeAndCapacityBeforePublishing() = runBlocking<Unit> {
        val base = temporary.newFolder("workspace"); val cache = temporary.newFolder("cache")
        val invalid = listOf(mapOf("../escape.py" to "x"), mapOf("/absolute.py" to "x"),
            mapOf("scripts\\escape.py" to "x"), mapOf("scripts" to "x", "scripts/a.py" to "x"),
            mapOf("a.txt" to "\uD800"), mapOf("a.txt" to "\u0000"), mapOf("a.txt" to "x".repeat(32769)),
            (1..41).associate { "$it.txt" to "x" }, (1..8).associate { "$it.txt" to "x".repeat(32768) })
        for (files in invalid) assertFails { WorkspaceResourceBundles.prepare(base, cache, "example", files, ::publish) { } }
        assertTrue(base.list()!!.isEmpty()); assertTrue(cache.list()!!.isEmpty())
    }
    @Test fun rejectedAuthorizationAndCancellationClearOnlyPrivateStaging() = runBlocking<Unit> {
        val base = temporary.newFolder("workspace"); val cache = temporary.newFolder("cache")
        File(base, "keep.txt").writeText("keep")
        assertFailsWith<CancellationException> {
            WorkspaceResourceBundles.prepare(base, cache, "example", mapOf("a.txt" to "x", "b.txt" to "y"), ::publish) {
                if (cache.walkTopDown().any { it.name == "a.txt" }) throw CancellationException("cancel during preparation")
            }
        }
        assertEquals(listOf("keep.txt"), base.list()!!.toList()); assertTrue(cache.list()!!.isEmpty())
    }
    @Test fun refusesSymlinkRootsAndPreservesConcurrentDestinationOnPublisherFailure() = runBlocking<Unit> {
        val base = temporary.newFolder("workspace"); val cache = temporary.newFolder("cache")
        val link = File(temporary.root, "link"); Files.createSymbolicLink(link.toPath(), base.toPath())
        assertFails { WorkspaceResourceBundles.prepare(link, cache, "example", mapOf("a.txt" to "x"), ::publish) { } }
        assertFails {
            WorkspaceResourceBundles.prepare(base, cache, "example", mapOf("a.txt" to "x"), { _, destination ->
                destination.mkdir(); File(destination, "concurrent.txt").writeText("keep")
                throw java.nio.file.FileAlreadyExistsException(destination.path)
            }) { }
        }
        assertEquals("keep", base.listFiles()!!.single().resolve("concurrent.txt").readText())
        assertTrue(cache.list()!!.isEmpty())
    }
    @Test fun refusesChangedRootBeforePublication() = runBlocking<Unit> {
        val base = temporary.newFolder("workspace"); val cache = temporary.newFolder("cache")
        var changed = false
        assertFails {
            WorkspaceResourceBundles.prepare(base, cache, "example", mapOf("a.txt" to "x"), ::publish) {
                if (!changed && cache.walkTopDown().any { it.name == "a.txt" }) {
                    Files.move(base.toPath(), File(temporary.root, "old").toPath()); base.mkdir(); changed = true
                }
            }
        }
        assertTrue(changed); assertTrue(base.list()!!.isEmpty()); assertTrue(cache.list()!!.isEmpty())
    }
    @Test fun revocationAfterPublishKeepsTheWorkspaceCopyAndDoesNotDeleteLaterEdits() = runBlocking<Unit> {
        val base = temporary.newFolder("workspace"); val cache = temporary.newFolder("cache")
        var published: File? = null
        assertFails {
            WorkspaceResourceBundles.prepare(base, cache, "example", mapOf("a.txt" to "x"), { source, destination ->
                publish(source, destination); published = destination; File(destination, "a.txt").writeText("later edit")
            }) { check(published == null) { "revoked" } }
        }
        assertEquals("later edit", File(published!!, "a.txt").readText()); assertTrue(cache.list()!!.isEmpty())
    }
}
