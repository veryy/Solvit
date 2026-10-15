package dev.solvit.git

import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.*

/** Real temporary inodes and complete file moves. These movers are JVM fixtures;
 * Android RENAME_NOREPLACE and actual force-stop are separate required device gates. */
class CloneInstallTransactionsTest {
    private class Fixture {
        val temp = Files.createTempDirectory("solvit-clone-recovery-").toFile().canonicalFile
        val root = File(temp, "workspace").apply { check(mkdir()) }
        val base = File(temp, "private-ledger")
        private fun move(source: File, target: File) { Files.move(source.toPath(), target.toPath()) }
        fun store() = CloneInstallTransactions(base, ::move, ::move)
        val store = store()
        val originalKey = key(root)
        fun begin() = store.begin("workspace-fixture", root, "https://github.com/veryy/Solvit", "main")
        suspend fun ready(): CloneInstallTransactions.Record {
            val r = begin(); val tree = store.tree(r)
            File(tree, ".git").apply { check(mkdir()) }.let { File(it, "HEAD").writeText("ref: refs/heads/main\n") }
            File(tree, "alpha.txt").writeText("complete original clone file")
            File(tree, "src").mkdir(); File(tree, "src/main.txt").writeText("nested complete file")
            return store.seal(r.id, "a".repeat(40))
        }
        suspend fun interruptAfterFirstFile(r: CloneInstallTransactions.Record) {
            assertFailsWith<CancellationException> {
                store.install(r.id) { if (File(root, "alpha.txt").exists()) throw CancellationException("after actual complete file move") }
            }
            assertEquals("INSTALLING", store.read(r.id).phase)
            assertEquals("complete original clone file", File(root, "alpha.txt").readText())
            assertFalse(File(store.tree(store.read(r.id)), "alpha.txt").exists())
            assertFalse(File(root, ".git").exists())
        }
        fun close() { check(temp.deleteRecursively()) }
    }
    companion object {
        private fun key(file: File) = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey().toString()
    }
    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(); try { block(f) } finally { f.close() }
    }
    @Test fun completeInstallPreservesRootIdentityAndRetiresPrivateStaging() = runBlocking {
        fixture { f ->
            val r = f.ready(); f.store.install(r.id)
            assertEquals(f.originalKey, key(f.root))
            assertEquals("complete original clone file", File(f.root, "alpha.txt").readText())
            assertEquals("nested complete file", File(f.root, "src/main.txt").readText())
            assertEquals(r.id, File(f.root, ".git/solvit-clone-origin").readText())
            assertTrue(f.base.listFiles()!!.isEmpty())
            assertTrue(f.store.inventory("workspace-fixture").records.isEmpty())
        }
    }
    @Test fun interruptionAfterCompleteFileMoveSurvivesReconstructionAndExplicitlyFinishesMissingEntries() = runBlocking {
        fixture { f ->
            val r = f.ready(); f.interruptAfterFirstFile(r)
            val restarted = f.store()
            assertEquals("INSTALL_INTERRUPTED", restarted.inventory("workspace-fixture").records.single().status)
            assertFalse(File(f.root, "src/main.txt").exists()) // Merely opening inventory never replays.
            restarted.install(r.id)
            assertEquals(f.originalKey, key(f.root)); assertTrue(File(f.root, ".git/HEAD").isFile)
            assertEquals("nested complete file", File(f.root, "src/main.txt").readText())
            assertTrue(f.base.listFiles()!!.isEmpty())
        }
    }
    @Test fun concurrentUnplannedFileFailsBeforeMutationAndAbandonKeepsAllWorkspaceFiles() = runBlocking {
        fixture { f ->
            val r = f.ready(); File(f.root, "from-shell.txt").writeText("concurrent user file")
            assertFailsWith<IllegalStateException> { f.store.install(r.id) }
            assertFalse(File(f.root, "alpha.txt").exists()); assertFalse(File(f.root, ".git").exists())
            f.store.retire(r.id)
            assertEquals(listOf("from-shell.txt"), f.root.listFiles()!!.map { it.name })
            assertEquals("concurrent user file", File(f.root, "from-shell.txt").readText())
        }
    }
    @Test fun modifyingPublishedFileInvalidatesResumeAndAbandonNeverDeletesTheUserEdit() = runBlocking {
        fixture { f ->
            val r = f.ready(); f.interruptAfterFirstFile(r)
            File(f.root, "alpha.txt").writeText("user edit after interruption")
            assertFailsWith<IllegalStateException> { f.store().install(r.id) }
            assertFalse(File(f.root, ".git").exists())
            f.store.retire(r.id)
            assertEquals("user edit after interruption", File(f.root, "alpha.txt").readText())
        }
    }
    @Test fun sameBytesAtDifferentInodeAndSymlinkAreNotAdoptedOrOverwritten() = runBlocking {
        fixture { f ->
            val r = f.ready(); f.interruptAfterFirstFile(r)
            val file = File(f.root, "alpha.txt"); val oldKey = key(file)
            // Keep the old inode allocated so this fixture deterministically creates
            // a different inode instead of relying on filesystem allocator timing.
            file.inputStream().use {
                assertTrue(file.delete()); file.writeText("complete original clone file"); assertNotEquals(oldKey, key(file))
                assertFailsWith<IllegalStateException> { f.store().install(r.id) }
            }
            assertTrue(file.delete()); val outside = File(f.temp, "outside.txt").apply { writeText("external file") }
            Files.createSymbolicLink(file.toPath(), outside.toPath())
            assertFailsWith<IllegalStateException> { f.store().install(r.id) }
            f.store.retire(r.id)
            assertTrue(Files.isSymbolicLink(file.toPath())); assertEquals("external file", outside.readText())
        }
    }
    @Test fun killAfterMetadataRenameKeepsPublishedFactAndDoesNotReapplyLaterUserChanges() = runBlocking {
        fixture { f ->
            val r = f.ready()
            assertFailsWith<CancellationException> {
                f.store.install(r.id) { if (File(f.root, ".git").exists()) throw CancellationException("actual rename before completion") }
            }
            assertEquals("PUBLISHING", f.store.read(r.id).phase)
            assertEquals("PUBLISHED", f.store().inventory("workspace-fixture").records.single().status)
            assertTrue(File(f.root, "alpha.txt").delete()); File(f.root, "from-shell.txt").writeText("keep after published")
            File(f.root, "src/main.txt").writeText("edited after published")
            f.store().install(r.id)
            assertFalse(File(f.root, "alpha.txt").exists()); assertEquals("keep after published", File(f.root, "from-shell.txt").readText())
            assertEquals("edited after published", File(f.root, "src/main.txt").readText())
            assertTrue(File(f.root, ".git/HEAD").isFile); assertTrue(f.base.listFiles()!!.isEmpty())
        }
    }
    @Test fun replacedRootAndDamagedManifestAreKeptForInspectionWithoutAnyReplay() = runBlocking {
        fixture { f ->
            val r = f.ready(); val manifest = File(f.base, "${r.id}/manifest")
            manifest.appendText("unexpected changed bytes\n")
            assertFailsWith<IllegalStateException> { f.store().install(r.id) }
            assertTrue(f.root.listFiles()!!.isEmpty())
            val old = File(f.temp, "retained-root"); Files.move(f.root.toPath(), old.toPath()); assertTrue(f.root.mkdir())
            File(f.root, "new.txt").writeText("new workspace inode")
            assertEquals("UNAVAILABLE", f.store().inventory("workspace-fixture").records.single().status)
            assertFailsWith<IllegalStateException> { f.store().install(r.id) }
            f.store.retire(r.id)
            assertEquals("new workspace inode", File(f.root, "new.txt").readText()); assertTrue(old.isDirectory)
        }
    }
    @Test fun incompleteDownloadAndUnregisteredDirectoryAreNotMisreportedAsInstallableOrDeleted() = runBlocking {
        fixture { f ->
            val r = f.begin(); File(f.store.tree(r), "partial-pack").writeText("private download")
            assertEquals("DOWNLOAD_INTERRUPTED", f.store().inventory("workspace-fixture").records.single().status)
            assertFailsWith<IllegalStateException> { f.store.install(r.id) }
            assertTrue(f.store.inventory("another-workspace").records.isEmpty())
            val unknown = File(f.base, java.util.UUID.randomUUID().toString()).apply { mkdir() }
            File(unknown, "keep.txt").writeText("unregistered")
            assertEquals(1, f.store.inventory("workspace-fixture").unreadable)
            f.store.retire(r.id)
            assertEquals("unregistered", File(unknown, "keep.txt").readText()); assertTrue(f.root.listFiles()!!.isEmpty())
        }
    }
    @Test fun twoStoreInstancesCannotMutateTheSameWorkspaceWhileAnotherOwnsItsOperation() = runBlocking {
        fixture { f ->
            val reached = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = async(Dispatchers.Default) { f.store.exclusive("workspace-fixture") { reached.complete(Unit); release.await(); f.begin() } }
            reached.await()
            assertFailsWith<IllegalStateException> { f.store().exclusive("workspace-fixture") { error("Must not enter second instance") } }
            release.complete(Unit); val r = first.await()
            assertFailsWith<IllegalStateException> { f.store().exclusive("workspace-fixture") { f.begin() } }
            f.store.retire(r.id)
        }
    }
    @Test fun reviewChecksActualContentsWithoutWritingAndDistinguishesRestartFromResume() = runBlocking {
        fixture { f ->
            val downloading = f.begin()
            val unfinished = f.store.review(downloading.id)
            assertTrue(unfinished.canRestart); assertFalse(unfinished.canResume)
            assertTrue(f.root.listFiles()!!.isEmpty())
            f.store.retire(downloading.id)
            val r = f.ready(); f.interruptAfterFirstFile(r)
            val review = f.store().review(r.id)
            assertTrue(review.canResume); assertFalse(review.canRestart)
            assertEquals(2, review.files); assertEquals(1, review.installedFiles)
            assertFalse(File(f.root, "src/main.txt").exists())
            File(f.root, "alpha.txt").writeText("concurrent edit")
            val conflict = f.store().review(r.id)
            assertFalse(conflict.canResume); assertFalse(conflict.canRestart)
            assertNotNull(conflict.problem); assertFalse(conflict.problem!!.contains(f.temp.path))
            assertEquals("concurrent edit", File(f.root, "alpha.txt").readText())
        }
    }
    @Test fun missingMovedFileIsNotRecreatedOrMistakenForUnpublishedSource() = runBlocking {
        fixture { f ->
            val r = f.ready(); f.interruptAfterFirstFile(r)
            assertTrue(File(f.root, "alpha.txt").delete())
            assertFalse(f.store().review(r.id).canResume)
            assertFailsWith<IllegalStateException> { f.store().install(r.id) }
            assertFalse(File(f.root, "alpha.txt").exists()); assertFalse(File(f.root, ".git").exists())
            f.store.retire(r.id); assertTrue(f.root.listFiles()!!.isEmpty())
        }
    }
    @Test fun publishedReviewDoesNotRequireOrReplayTheOriginalWorktreeAndCancellationEscapes() = runBlocking {
        fixture { f ->
            val r = f.ready()
            f.store.install(r.id, retireOnSuccess = false)
            File(f.root, "alpha.txt").delete(); File(f.root, "user.txt").writeText("later user content")
            val review = f.store().review(r.id)
            assertTrue(review.canResume); assertFalse(review.canRestart)
            assertFalse(File(f.root, "alpha.txt").exists())
            assertFailsWith<CancellationException> { f.store.review(r.id) { throw CancellationException("revoked") } }
            assertEquals("COMMITTED", f.store.read(r.id).phase)
            f.store.retire(r.id)
            assertEquals("later user content", File(f.root, "user.txt").readText())
        }
    }
}
