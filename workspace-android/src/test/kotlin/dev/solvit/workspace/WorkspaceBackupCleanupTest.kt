package dev.solvit.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.*

class WorkspaceBackupCleanupTest {
    private val mover=WorkspaceImportPublisher { from,to -> Files.move(from.toPath(),to.toPath());Unit }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fixture(block:suspend (File,File,WorkspaceImportBackups.Record)->Unit)=runBlocking<Unit> {
        val base=Files.createTempDirectory("backup-cleanup").toFile().canonicalFile
        try {
            val root=File(base,"workspace").apply { mkdir() };val ledger=File(base,"ledger")
            val original=File(root,"data.txt").apply { writeText("original backup") }
            val backup=File(root,"solvit-backup-${UUID.randomUUID()}")
            val id=UUID.randomUUID().toString()
            val key=Files.readAttributes(original.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey().toString()
            val catalog=WorkspaceImportBackups(root,ledger)
            catalog.register(id,original,backup,key,sha(original.readBytes()),original.length())
            mover.publish(original,backup);original.writeText("current target")
            block(root,ledger,catalog.registered(id))
        }finally { base.deleteRecursively() }
    }
    @Test fun explicitlyReviewedClaimDeletesOnlyTheOwnedBackupAndRetiresItsProvenance()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover)
        assertFails { cleanup.deleteReviewed(r.id,r.originalSha256) }
        assertFails { cleanup.prepare(r.id,"0".repeat(64)) }
        assertTrue(File(root,r.backupPath).exists())
        val prepared=cleanup.prepare(r.id,r.originalSha256)
        assertEquals("HELD",prepared.status);assertEquals("CLAIMED",prepared.intent.phase)
        assertFalse(File(root,r.backupPath).exists())
        assertTrue(WorkspaceImportBackups(root,ledger).inventory().observations.single().pending)
        assertFails { WorkspaceImportBackups(root,ledger).readOwned(r.id){} }
        cleanup.deleteReviewed(r.id,r.originalSha256)
        assertEquals("current target",File(root,r.path).readText())
        assertTrue(WorkspaceImportBackups(root,ledger).inventory().observations.isEmpty())
        assertFalse(File(ledger,"backup-cleanup/${r.id}.held").exists())
        assertFalse(File(ledger,"backup-cleanup/${r.id}.intent").exists())
    }
    @Test fun anotherOperationCannotCompensateOrDeleteAnExistingClaim()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover)
        val owner=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
        cleanup.prepare(r.id,r.originalSha256,owner)
        assertFails { cleanup.prepare(r.id,r.originalSha256,other) }
        assertFails { cleanup.restore(r.id,other) }
        assertFails { cleanup.deleteReviewed(r.id,r.originalSha256,other) }
        assertEquals("HELD",cleanup.inspect(r.id).status)
        cleanup.restore(r.id,owner)
        assertEquals("original backup",File(root,r.backupPath).readText())
    }
    @Test fun aNameReplacedImmediatelyBeforeClaimIsPreservedAndCanBeRestoredButNeverDeleted()=fixture { root,ledger,r ->
        val backup=File(root,r.backupPath);val elsewhere=File(root,"Shell-kept-original")
        val racingMover=WorkspaceImportPublisher { from,to ->
            if(from==backup){mover.publish(backup,elsewhere);backup.writeText("concurrent unrelated file")}
            mover.publish(from,to)
        }
        val cleanup=WorkspaceBackupCleanup(root,ledger,racingMover)
        assertFails { cleanup.prepare(r.id,r.originalSha256) }
        assertEquals("HELD_CHANGED",cleanup.inspect(r.id).status)
        assertFails { cleanup.deleteReviewed(r.id,r.originalSha256) }
        assertEquals("original backup",elsewhere.readText())
        WorkspaceBackupCleanup(root,ledger,mover).restore(r.id)
        assertEquals("concurrent unrelated file",backup.readText())
        assertEquals("original backup",elsewhere.readText())
        assertEquals("CHANGED_IDENTITY",WorkspaceImportBackups(root,ledger).inventory().observations.single().status)
    }
    @Test fun reopeningAnInterruptedClaimNeverDeletesAndConcurrentReturnNameCannotBeOverwritten()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover);cleanup.prepare(r.id,r.originalSha256)
        val held=File(ledger,"backup-cleanup/${r.id}.held")
        File(root,r.backupPath).writeText("new concurrent name")
        val reopened=WorkspaceBackupCleanup(root,ledger,mover)
        assertEquals("SOURCE_AND_HELD",reopened.inspect(r.id).status)
        assertFails { reopened.restore(r.id) }
        assertEquals("original backup",held.readText());assertEquals("new concurrent name",File(root,r.backupPath).readText())
        mover.publish(File(root,r.backupPath),File(root,"kept-concurrent"))
        reopened.restore(r.id)
        assertEquals("original backup",File(root,r.backupPath).readText())
        assertEquals("new concurrent name",File(root,"kept-concurrent").readText())
        assertFalse(WorkspaceImportBackups(root,ledger).inventory().observations.single().pending)
    }
    @Test fun existingOpenDescriptorChangesAfterClaimRejectDeletionAndKeepRecoverableBytes()=fixture { root,ledger,r ->
        RandomAccessFile(File(root,r.backupPath),"rw").use { writer ->
            val cleanup=WorkspaceBackupCleanup(root,ledger,mover);cleanup.prepare(r.id,r.originalSha256)
            writer.setLength(0);writer.write("changed through existing Shell fd".toByteArray())
            assertFails { cleanup.deleteReviewed(r.id,r.originalSha256) }
            assertEquals("HELD",cleanup.inspect(r.id).status)
            cleanup.restore(r.id)
            assertEquals("changed through existing Shell fd",File(root,r.backupPath).readText())
        }
    }
    @Test fun cancellationAfterActualClaimLeavesAnInspectableIntentAndRestoresWithoutReplay()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover)
        assertFailsWith<CancellationException> {
            cleanup.prepare(r.id,r.originalSha256) { if(!File(root,r.backupPath).exists())throw CancellationException("after claim") }
        }
        val reopened=WorkspaceBackupCleanup(root,ledger,mover)
        assertEquals("HELD",reopened.inspect(r.id).status)
        assertEquals("PREPARED",reopened.inspect(r.id).intent.phase)
        assertFails { reopened.deleteReviewed(r.id,r.originalSha256) }
        reopened.restore(r.id);assertEquals("original backup",File(root,r.backupPath).readText())
    }
    @Test fun interruptedDeletionIsExplicitlyUnconfirmedAndNeverRetriesAnUnlink()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover);cleanup.prepare(r.id,r.originalSha256)
        val held=File(ledger,"backup-cleanup/${r.id}.held")
        assertFailsWith<CancellationException> {
            cleanup.deleteReviewed(r.id,r.originalSha256) { if(!held.exists())throw CancellationException("after irreversible delete") }
        }
        val reopened=WorkspaceBackupCleanup(root,ledger,mover)
        assertEquals("UNCONFIRMED",reopened.inspect(r.id).status)
        assertEquals("DELETING",reopened.inspect(r.id).intent.phase)
        assertFails { reopened.restore(r.id) };assertFails { reopened.deleteReviewed(r.id,r.originalSha256) }
        File(root,r.backupPath).writeText("new file after interrupted delete")
        assertFails { reopened.restore(r.id) };assertEquals("new file after interrupted delete",File(root,r.backupPath).readText())
    }
    @Test fun unconfirmedDismissalRetiresOnlyMetadataAndMalformedOrOrphanedEntriesAreVisible()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover);cleanup.prepare(r.id,r.originalSha256)
        val held=File(ledger,"backup-cleanup/${r.id}.held")
        assertFails { cleanup.dismiss(r.id) };assertTrue(held.exists())
        assertFailsWith<CancellationException> {
            cleanup.deleteReviewed(r.id,r.originalSha256) { if(!held.exists())throw CancellationException("kill window") }
        }
        File(root,r.backupPath).writeText("concurrent file")
        cleanup.dismiss(r.id)
        assertEquals("concurrent file",File(root,r.backupPath).readText())
        assertEquals("current target",File(root,r.path).readText())
        assertFalse(WorkspaceImportBackups(root,ledger).inventory().observations.single().pending)
        File(ledger,"backup-cleanup/${UUID.randomUUID()}.intent").writeText("bad private record")
        File(ledger,"backup-cleanup/${UUID.randomUUID()}.held").writeText("orphan; never auto-adopt")
        val inventory=cleanup.inventory();assertEquals(2,inventory.unreadableEntries);assertTrue(inventory.observations.isEmpty())
    }
    @Test fun cancellationBeforeClaimRetiresOnlyItsIntentOnExplicitRestore()=fixture { root,ledger,r ->
        val cleanup=WorkspaceBackupCleanup(root,ledger,mover)
        assertFailsWith<CancellationException> {
            cleanup.prepare(r.id,r.originalSha256) { if(File(ledger,"backup-cleanup/${r.id}.intent").exists())throw CancellationException("before claim") }
        }
        assertEquals("SOURCE_AVAILABLE",cleanup.inspect(r.id).status)
        cleanup.restore(r.id)
        assertEquals("original backup",File(root,r.backupPath).readText())
        assertFalse(WorkspaceImportBackups(root,ledger).inventory().observations.single().pending)
    }
}
