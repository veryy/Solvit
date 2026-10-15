package dev.solvit.workspace

import dev.solvit.core.WorkspaceBackupStatus
import dev.solvit.core.WorkspacePageAuthority
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceImportBackupsServiceTest {
    @Test fun reviewReadsCurrentOwnedBytesAndRejectsStaleDigestBeforeReturningCopy()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val native=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,importAvailableBytes={10_000_000L},
            importPublisher=WorkspaceImportPublisher { a,b -> Files.move(a.toPath(),b.toPath());Unit })
        fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
        try {
            val workspace=native.create("Backup review");val root=File(native.location(workspace.id))
            val original="original bytes".toByteArray();val replacement="published bytes".toByteArray()
            File(root,"file").writeBytes(original)
            val imported=native.replaceImportedFile(workspace.id,"file",replacement,sha(replacement),sha(original))
            val entry=native.importBackups(workspace.id).backups.single()
            val review=native.readImportBackup(workspace.id,entry.id)
            assertContentEquals(original,review.bytes);assertEquals(sha(original),review.sha256)
            review.close();assertTrue(review.bytes.all { it==0.toByte() })
            File(root,checkNotNull(imported.backupPath)).writeText("changed through same inode")
            assertFails { native.readImportBackup(workspace.id,entry.id,sha(original)) }
            val current=native.readImportBackup(workspace.id,entry.id)
            assertEquals("changed through same inode",current.bytes.toString(Charsets.UTF_8))
            assertEquals(entry.originalSha256,current.backup.originalSha256)
            assertNotEquals(current.sha256,current.backup.originalSha256);current.close()
            withContext(WorkspacePageAuthority { _,_ -> error("revoked") }) {
                assertFails { native.readImportBackup(workspace.id,entry.id) }
            }
            assertContentEquals(replacement,File(root,"file").readBytes())
        }finally { native.close();scope.cancel() }
    }
    @Test fun nativeServicePaginatesPersistedProvenanceAndRechecksWorkspaceAuthority()=runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var space=10_000_000L
        fun service()=AndroidWorkspaces(context,scope,importAvailableBytes={space},
            importPublisher=WorkspaceImportPublisher { a,b -> Files.move(a.toPath(),b.toPath());Unit })
        var native=service()
        fun sha(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
        try {
            val workspace=native.create("Backup provenance")
            val other=native.create("Unrelated workspace")
            val root=File(native.location(workspace.id))
            for(path in listOf("a","b")) {
                val old="old $path".toByteArray();val bytes="new $path".toByteArray()
                File(root,path).writeBytes(old)
                native.replaceImportedFile(workspace.id,path,bytes,sha(bytes),sha(old))
            }
            space=12345
            val page=native.importBackups(workspace.id,0,1)
            val next=native.importBackups(workspace.id,1,1)
            assertEquals(2,page.total);assertEquals(1,page.backups.size);assertEquals(1,next.offset)
            assertEquals(setOf("a","b"),(page.backups+next.backups).map { it.path }.toSet())
            assertEquals(10L,page.logicalBytes);assertEquals(12345L,page.availableBytes)
            assertTrue((page.backups+next.backups).all { it.status==WorkspaceBackupStatus.PRESENT&&!it.pending })
            assertTrue(native.importBackups(workspace.id,2,1).backups.isEmpty())
            assertFails { native.importBackups(workspace.id,-1,1) }
            assertFails { native.importBackups(workspace.id,0,101) }
            native.close();native=service()
            assertEquals(page,native.importBackups(workspace.id,0,1))
            withContext(WorkspacePageAuthority { id,_ -> check(id==workspace.id) { "wrong workspace" } }) {
                assertEquals(page,native.importBackups(workspace.id,0,1))
                assertFails { native.importBackups(other.id) }
            }
            var checks=0
            withContext(WorkspacePageAuthority { _,_ -> check(++checks==1) { "page revoked during read" } }) {
                assertFails { native.importBackups(workspace.id) }
            }
            assertEquals(2,checks)
            assertEquals(2,native.importBackups(workspace.id).total)
        }finally { native.close();scope.cancel() }
    }
}
