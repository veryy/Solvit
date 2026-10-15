package dev.solvit.workspace

import dev.solvit.core.WorkspaceImportMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.*

/** On-disk interrupted layouts use the same record writer as real imports. Actual OS kill
 * coverage belongs to the separate Android process-death gate, not these JVM fixtures. */
class WorkspaceImportRecoveryTest {
    private val publisher = WorkspaceImportPublisher { from, to -> Files.move(from.toPath(), to.toPath()); Unit }
    private fun fixture(block: suspend (File, File) -> Unit) = runBlocking<Unit> {
        val base = Files.createTempDirectory("solvit-import-recovery").toFile().canonicalFile
        try { block(File(base, "workspace").apply { mkdir() }, File(base, "ledger")) }
        finally { base.deleteRecursively() }
    }
    private fun backup(root: File) = File(root, "solvit-backup-${UUID.randomUUID()}")
    private fun staging(root: File) = File.createTempFile(".solvit-import-", ".partial", root).apply { writeText("new file") }
    private fun recover(root: File, ledger: File): WorkspaceImportTransactions {
        val journal = WorkspaceImportTransactions(root, ledger)
        WorkspaceFileImports.recover(root, WorkspaceImportStaging(root, ledger), journal, publisher)
        return journal
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun interruptedClaimRestoresOriginalAndDismissOnlyRemovesReport() = fixture { root, ledger ->
        val target = File(root, "file").apply { writeText("original") }; val backup = backup(root)
        WorkspaceImportTransactions(root, ledger).begin(target, backup)
        publisher.publish(target, backup)
        val reopened = recover(root, ledger)
        assertEquals("original", target.readText()); assertFalse(backup.exists())
        val notice = reopened.notices().single()
        assertEquals("ORIGINAL_AVAILABLE", notice.status); assertEquals("file", notice.path)
        assertTrue(ledger.listFiles()!!.none { it.extension == "transaction" })
        recover(root, ledger); assertEquals("original", target.readText())
        reopened.dismiss(notice.id); assertTrue(reopened.notices().isEmpty()); assertEquals("original", target.readText())
    }
    @Test fun publishingIntentIsRecoveredBeforeOwnedStagingIsReclaimed() = fixture { root, ledger ->
        val target = File(root, "file").apply { writeText("original") }; val backup = backup(root)
        val transaction = WorkspaceImportTransactions(root, ledger).begin(target, backup)
        publisher.publish(target, backup)
        val staged = staging(root); WorkspaceImportStaging(root, ledger).register(staged)
        transaction.publishing(staged)
        val reopened = recover(root, ledger)
        assertEquals("original", target.readText()); assertFalse(staged.exists()); assertFalse(backup.exists())
        assertEquals("ORIGINAL_AVAILABLE", reopened.notices().single().status)
    }
    @Test fun publicationBeforeCommitMarkerPreservesNewFileAndOriginalBackup() = fixture { root, ledger ->
        val target = File(root, "file").apply { writeText("original") }; val backup = backup(root)
        val transaction = WorkspaceImportTransactions(root, ledger).begin(target, backup)
        publisher.publish(target, backup)
        val staged = staging(root); WorkspaceImportStaging(root, ledger).register(staged)
        transaction.publishing(staged); publisher.publish(staged, target)
        assertEquals("PUBLISHED", recover(root, ledger).notices().single().status)
        assertEquals("new file", target.readText()); assertEquals("original", backup.readText())
    }
    @Test fun committedOrAmbiguousMissingNewNameNeverResurrectsDeletedUserData() = fixture { root, ledger ->
        for (committed in listOf(false, true)) {
            val target = File(root, "file-$committed").apply { writeText("original") }; val backup = backup(root)
            val transaction = WorkspaceImportTransactions(root, ledger).begin(target, backup)
            publisher.publish(target, backup)
            val staged = staging(root); transaction.publishing(staged)
            publisher.publish(staged, target)
            if (committed) transaction.committed()
            target.delete()
            val reopened = recover(root, ledger)
            assertFalse(target.exists()); assertEquals("original", backup.readText())
            assertEquals(if (committed) "PUBLISHED" else "REVIEW_REQUIRED", reopened.notices().single { it.path == target.name }.status)
        }
    }
    @Test fun interruptedRollbackCanRestoreAfterStagingWasAlreadyDeleted() = fixture { root, ledger ->
        val target = File(root, "file").apply { writeText("original") }; val backup = backup(root)
        val transaction = WorkspaceImportTransactions(root, ledger).begin(target, backup)
        publisher.publish(target, backup)
        val staged = staging(root); transaction.publishing(staged); transaction.rollingBack(); staged.delete()
        assertEquals("ORIGINAL_AVAILABLE", recover(root, ledger).notices().single().status)
        assertEquals("original", target.readText()); assertFalse(backup.exists())
    }
    @Test fun concurrentNameSurvivesAndReportCannotRearmAfterItIsDeleted() = fixture { root, ledger ->
        val target = File(root, "file").apply { writeText("original") }; val backup = backup(root)
        WorkspaceImportTransactions(root, ledger).begin(target, backup); publisher.publish(target, backup)
        target.writeText("concurrent Shell")
        val reopened = recover(root, ledger); val notice = reopened.notices().single()
        assertEquals("CONFLICT", notice.status); assertEquals("concurrent Shell", target.readText())
        assertEquals("original", backup.readText()); assertEquals(backup.name, notice.backupPath)
        target.delete(); recover(root, ledger); assertFalse(target.exists())
        reopened.dismiss(notice.id); assertEquals("original", backup.readText())
    }
    @Test fun changedBackupIdentityAndSymlinkParentNeverAuthorizeRecovery() = fixture { root, ledger ->
        val folder = File(root, "folder").apply { mkdir() }
        val target = File(folder, "file").apply { writeText("original") }; val backup = backup(folder)
        WorkspaceImportTransactions(root, ledger).begin(target, backup); publisher.publish(target, backup)
        Files.move(backup.toPath(), File(folder, "kept-original").toPath()); backup.writeText("same name, new inode")
        assertEquals("REVIEW_REQUIRED", recover(root, ledger).notices().single().status)
        assertFalse(target.exists()); assertEquals("same name, new inode", backup.readText())
        val target2 = File(folder, "second").apply { writeText("second original") }; val backup2 = backup(folder)
        WorkspaceImportTransactions(root, ledger).begin(target2, backup2); publisher.publish(target2, backup2)
        val moved = File(root, "moved-folder"); Files.move(folder.toPath(), moved.toPath())
        Files.createSymbolicLink(folder.toPath(), moved.toPath())
        try { recover(root, ledger); assertFalse(File(moved, "second").exists()); assertEquals("second original", File(moved, backup2.name).readText()) }
        finally { Files.delete(folder.toPath()) }
    }
    @Test fun normalImportAndCancellationRetireRecordsWhileActiveImportsAreProtected() = fixture { root, ledger ->
        val original = "original".toByteArray(); val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val journal = WorkspaceImportTransactions(root, ledger); val staging = WorkspaceImportStaging(root, ledger)
        for (cancel in listOf(false, true)) {
            val target = File(root, "file-$cancel").apply { writeBytes(original) }
            suspend fun import() = withContext(journal + staging + WorkspaceImportMonitor {
                if (it.stage == "WRITING") {
                    recover(root, ledger)
                    assertFalse(target.exists()); assertTrue(journal.notices().isEmpty())
                    if (cancel) throw CancellationException("cancel")
                }
            }) { WorkspaceFileImports.replace(root, target.name, bytes, sha(bytes), sha(original), publisher) {} }
            if (cancel) { assertFailsWith<CancellationException> { import() }; assertContentEquals(original, target.readBytes()) }
            else { val result = import(); assertContentEquals(bytes, target.readBytes()); assertContentEquals(original, File(root, result.third).readBytes()) }
            assertTrue(ledger.listFiles()!!.isEmpty())
        }
    }
    @Test fun malformedRecordCannotEscapeWorkspaceOrMoveAnUnrecordedBackup() = fixture { root, ledger ->
        val target = File(root, "file").apply { writeText("original") }; val backup = backup(root)
        val transaction = WorkspaceImportTransactions(root, ledger).begin(target, backup)
        publisher.publish(target, backup)
        val record = File(transaction.recordPath); val lines = record.readLines().toMutableList()
        lines[2] = Base64.getEncoder().encodeToString("../outside".toByteArray())
        record.writeText(lines.joinToString("\n", postfix = "\n"))
        val reopened = recover(root, ledger)
        assertTrue(reopened.notices().isEmpty()); assertTrue(record.exists()); assertFalse(target.exists()); assertEquals("original", backup.readText())
    }
}
