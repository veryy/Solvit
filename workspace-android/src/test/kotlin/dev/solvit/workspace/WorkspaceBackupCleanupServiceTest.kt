package dev.solvit.workspace

import dev.solvit.core.*
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
class WorkspaceBackupCleanupServiceTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun currentAuthorityIsRecheckedAfterClaimAndCompensationPreservesFileBeforeFreshApproval()=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val mover=WorkspaceImportPublisher { a,b->Files.move(a.toPath(),b.toPath());Unit }
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,importPublisher=mover,backupMover=mover)
        try {
            val workspace=service.create("cleanup service")
            val root=File(service.location(workspace.id));val original="old".toByteArray();val bytes="new".toByteArray()
            File(root,"file.txt").writeBytes(original)
            val imported=service.replaceImportedFile(workspace.id,"file.txt",bytes,sha(bytes),sha(original))
            val backup=File(root,checkNotNull(imported.backupPath));val record=service.importBackups(workspace.id).backups.single()
            var revoked=false
            withContext(WorkspacePageAuthority { target,_ ->
                assertEquals(workspace.id,target)
                if(!backup.exists()){revoked=true;error("revoked after claim")}
            }) { assertFails { service.cleanupImportBackup(workspace.id,record.id,sha(original)) } }
            assertTrue(revoked);assertEquals("old",backup.readText());assertEquals("new",File(root,"file.txt").readText())
            assertTrue(service.importBackupCleanups(workspace.id).cleanups.isEmpty())
            assertFails { service.cleanupImportBackup(workspace.id,record.id,"0".repeat(64)) }
            assertEquals("old",backup.readText())
            service.cleanupImportBackup(workspace.id,record.id,sha(original))
            assertFalse(backup.exists());assertEquals("new",File(root,"file.txt").readText())
            assertEquals(0,service.importBackups(workspace.id).total)
            assertTrue(service.importBackupCleanups(workspace.id).cleanups.isEmpty())
            assertFails { service.importBackupCleanups(workspace.id,-1,20) }
        }finally { service.close();scope.cancel() }
    }
}
