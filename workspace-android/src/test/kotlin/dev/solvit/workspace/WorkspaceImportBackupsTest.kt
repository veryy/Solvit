package dev.solvit.workspace

import dev.solvit.core.WorkspaceImportMonitor
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.*

class WorkspaceImportBackupsTest {
    private val publisher=WorkspaceImportPublisher { from,to -> Files.move(from.toPath(),to.toPath());Unit }
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun fixture(block:suspend(File,File)->Unit)=runBlocking<Unit> {
        val base=Files.createTempDirectory("backup-provenance").toFile().canonicalFile
        try { block(File(base,"workspace").apply { mkdir() },File(base,"ledger")) }
        finally { base.deleteRecursively() }
    }
    private suspend fun replace(root:File,ledger:File,path:String,bytes:ByteArray):String {
        val old=File(root,path).readBytes()
        return withContext(WorkspaceImportTransactions(root,ledger)+WorkspaceImportStaging(root,ledger)) {
            WorkspaceFileImports.replace(root,path,bytes,sha(bytes),sha(old),publisher) {}.third
        }
    }
    @Test fun successfulReplacementKeepsProvenanceAfterTransactionRetiresAndDoesNotAdoptNamedFiles()=fixture { root,ledger ->
        val nested=File(root,"nested").apply { mkdir() }
        val original="original UTF-8 内容".toByteArray();File(nested,"file.txt").writeBytes(original)
        File(root,"solvit-backup-${UUID.randomUUID()}").writeText("user-created; not ours")
        val backup=replace(root,ledger,"nested/file.txt","new".toByteArray())
        val reopened=WorkspaceImportBackups(root,ledger).inventory()
        val item=reopened.observations.single()
        assertEquals("nested/file.txt",item.record.path);assertEquals(backup,item.record.backupPath)
        assertEquals(sha(original),item.record.originalSha256);assertEquals(original.size.toLong(),item.record.originalBytes)
        assertTrue(item.record.createdAt>0);assertEquals("PRESENT",item.status);assertFalse(item.pending)
        assertEquals(original.size.toLong(),item.currentBytes);assertEquals(original.size.toLong(),reopened.logicalBytes)
        assertFalse(reopened.incomplete);assertEquals(0,reopened.unreadableRecords)
        assertTrue(ledger.listFiles()!!.none { it.extension in setOf("transaction","record","notice","tmp") })
        assertContentEquals(original,File(root,backup).readBytes());assertEquals("new",File(nested,"file.txt").readText())
    }
    @Test fun cancellationRestoresOriginalAndRemovesOnlyItsProvenance()=fixture { root,ledger ->
        File(root,"kept").writeText("first original");replace(root,ledger,"kept","first new".toByteArray())
        val target=File(root,"cancelled").apply { writeText("second original") }
        withContext(WorkspaceImportTransactions(root,ledger)+WorkspaceImportStaging(root,ledger)+WorkspaceImportMonitor {
            if(it.stage=="WRITING")throw CancellationException("cancel actual import")
        }) {
            assertFailsWith<CancellationException> {
                val bytes=ByteArray(100_000)
                WorkspaceFileImports.replace(root,target.name,bytes,sha(bytes),sha(target.readBytes()),publisher) {}
            }
        }
        assertEquals("second original",target.readText())
        val catalog=WorkspaceImportBackups(root,ledger).inventory()
        assertEquals(listOf("kept"),catalog.observations.map { it.record.path })
        assertEquals(1,root.listFiles()!!.count { it.name.startsWith("solvit-backup-") })
    }
    @Test fun publishedRecoveryAndDismissKeepProvenanceAndOpenDescriptorChangesRemainVisible()=fixture { root,ledger ->
        val original="original".toByteArray();val target=File(root,"file").apply { writeBytes(original) }
        val backup=File(root,"solvit-backup-${UUID.randomUUID()}")
        RandomAccessFile(target,"rw").use { descriptor ->
            val journal=WorkspaceImportTransactions(root,ledger)
            val transaction=journal.begin(target,backup,sha(original))
            publisher.publish(target,backup)
            assertTrue(WorkspaceImportBackups(root,ledger).inventory().observations.single().pending)
            val staged=File.createTempFile(".solvit-import-",".partial",root).apply { writeText("new") }
            transaction.publishing(staged);publisher.publish(staged,target);transaction.committed()
            // Recreate the actual recovery services over the persisted COMMITTED layout.
            val reopened=WorkspaceImportTransactions(root,ledger)
            WorkspaceFileImports.recover(root,WorkspaceImportStaging(root,ledger),reopened,publisher)
            val notice=reopened.notices().single();assertEquals("PUBLISHED",notice.status)
            reopened.dismiss(notice.id)
            descriptor.seek(0);descriptor.write("edited through open Shell descriptor".toByteArray())
            val inventory=WorkspaceImportBackups(root,ledger).inventory();val item=inventory.observations.single()
            assertEquals("PRESENT",item.status);assertFalse(item.pending)
            assertEquals(sha(original),item.record.originalSha256)
            assertNotEquals(item.record.originalSha256,sha(backup.readBytes()))
            assertEquals(backup.length(),item.currentBytes);assertEquals(backup.length(),inventory.logicalBytes)
            assertEquals("new",target.readText());assertTrue(reopened.notices().isEmpty())
        }
    }
    @Test fun newInodeSymlinkAndMissingBackupAreNotCountedAsOwned()=fixture { root,ledger ->
        for(name in listOf("changed","link","missing")) {
            File(root,name).writeText("old $name");replace(root,ledger,name,"new".toByteArray())
        }
        val entries=WorkspaceImportBackups(root,ledger).inventory().observations.associateBy { it.record.path }
        val changed=File(root,entries.getValue("changed").record.backupPath)
        Files.move(changed.toPath(),File(root,"retained-original-inode").toPath());changed.writeText("old changed")
        val link=File(root,entries.getValue("link").record.backupPath);val moved=File(root,"link-original")
        Files.move(link.toPath(),moved.toPath());Files.createSymbolicLink(link.toPath(),moved.toPath())
        val missing=File(root,entries.getValue("missing").record.backupPath);assertTrue(missing.delete())
        val inventory=WorkspaceImportBackups(root,ledger).inventory()
        assertEquals(mapOf("changed" to "CHANGED_IDENTITY","link" to "CHANGED_IDENTITY","missing" to "MISSING"),
            inventory.observations.associate { it.record.path to it.status })
        assertEquals(0L,inventory.logicalBytes);assertTrue(inventory.observations.all { it.currentBytes==null })
        assertEquals("old changed",changed.readText());assertEquals("old link",moved.readText())
    }
    @Test fun corruptOversizedAndSymlinkMetadataAreReportedWithoutFollowingOutsidePaths()=fixture { root,ledger ->
        File(root,"file").writeText("old");replace(root,ledger,"file","new".toByteArray())
        val record=ledger.listFiles()!!.single { it.extension=="backup" }
        val lines=record.readLines().toMutableList()
        lines[2]=Base64.getEncoder().encodeToString("../outside".toByteArray())
        File(ledger,"${UUID.randomUUID()}.backup").writeText(lines.joinToString("\n",postfix="\n"))
        File(ledger,"${UUID.randomUUID()}.backup").writeBytes(ByteArray(16_385))
        val outside=File(root.parentFile,"outside").apply { writeText("never follow or remove") }
        Files.createSymbolicLink(File(ledger,"${UUID.randomUUID()}.backup").toPath(),outside.toPath())
        val inventory=WorkspaceImportBackups(root,ledger).inventory()
        assertEquals(1,inventory.observations.size);assertEquals(3,inventory.unreadableRecords)
        assertEquals("never follow or remove",outside.readText());assertEquals(3L,inventory.logicalBytes)
    }
    @Test fun restoredInterruptedClaimDropsProvenanceAndCancellationOfScanPropagates()=fixture { root,ledger ->
        val target=File(root,"file").apply { writeText("old") };val backup=File(root,"solvit-backup-${UUID.randomUUID()}")
        WorkspaceImportTransactions(root,ledger).begin(target,backup,sha(target.readBytes()))
        publisher.publish(target,backup)
        val reopened=WorkspaceImportTransactions(root,ledger)
        WorkspaceFileImports.recover(root,WorkspaceImportStaging(root,ledger),reopened,publisher)
        assertEquals("old",target.readText());assertTrue(WorkspaceImportBackups(root,ledger).inventory().observations.isEmpty())
        assertFailsWith<CancellationException> { WorkspaceImportBackups(root,ledger).inventory { throw CancellationException("stop scan") } }
    }
    @Test fun twoRetainedHardlinksCountOneObservedInodeRatherThanPromisingReclaimableBytes()=fixture { root,ledger ->
        val a=File(root,"a").apply { writeText("shared original") }
        Files.createLink(File(root,"b").toPath(),a.toPath())
        replace(root,ledger,"a","new a".toByteArray());replace(root,ledger,"b","new b".toByteArray())
        val inventory=WorkspaceImportBackups(root,ledger).inventory()
        assertEquals(2,inventory.observations.size)
        assertTrue(inventory.observations.all { it.status=="PRESENT" })
        assertEquals("shared original".toByteArray().size.toLong(),inventory.logicalBytes)
        assertEquals(1,inventory.observations.map { it.record.originalKey }.toSet().size)
    }
}
