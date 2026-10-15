package dev.solvit.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.WorkspacePageAuthority
import dev.solvit.workspace.AndroidWorkspaces
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** The production cross-directory JNI mover, not java.nio.Files.move or a fake outcome. */
@RunWith(AndroidJUnit4::class)
class WorkspaceBackupCleanupDeviceTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun productionPrivateClaimDeletesOnlyApprovedBackupAndRevocationRestoresThroughRealNativeMove()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="cleanup-device-${UUID.randomUUID()}"
        val base=File(app.cacheDir,prefix).apply { check(mkdirs()) }
        val context=object:ContextWrapper(app) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=File(base,"files").apply { check(mkdirs()||isDirectory) }
            override fun getNoBackupFilesDir():File=File(base,"no-backup").apply { check(mkdirs()||isDirectory) }
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("$prefix-$name",mode)
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val service=AndroidWorkspaces(context,scope)
        try {
            val workspace=service.create("Native backup cleanup")
            val root=File(service.location(workspace.id))
            val original="original to be cleaned".toByteArray();val current="current target survives".toByteArray()
            File(root,"data.txt").writeBytes(original)
            val imported=service.replaceImportedFile(workspace.id,"data.txt",current,sha(current),sha(original))
            val backup=File(root,checkNotNull(imported.backupPath))
            val record=service.importBackups(workspace.id).backups.single()
            var rejected=false
            try { service.cleanupImportBackup(workspace.id,record.id,"0".repeat(64)) }catch(_:IllegalStateException){rejected=true}
            assertTrue(rejected);assertArrayEquals(original,backup.readBytes())
            // Revoke at the real point where backup's public name has been claimed.
            var sawClaim=false;var revoked=false
            try {
                withContext(WorkspacePageAuthority { _,_ ->
                    if(!backup.exists()){sawClaim=true;error("fixture revoked after actual native claim")}
                }) { service.cleanupImportBackup(workspace.id,record.id,sha(original)) }
            }catch(_:IllegalStateException){revoked=true}
            assertTrue(sawClaim);assertTrue(revoked)
            assertArrayEquals(original,backup.readBytes());assertArrayEquals(current,File(root,"data.txt").readBytes())
            assertTrue(service.importBackupCleanups(workspace.id).cleanups.isEmpty())
            assertFalse(service.importBackups(workspace.id).backups.single().pending)
            service.cleanupImportBackup(workspace.id,record.id,sha(original))
            assertFalse(backup.exists());assertArrayEquals(current,File(root,"data.txt").readBytes())
            assertTrue(service.importBackups(workspace.id).backups.isEmpty())
            assertTrue(service.importBackupCleanups(workspace.id).cleanups.isEmpty())
            assertTrue(File(context.noBackupFilesDir,"workspace-import-staging/${workspace.id}/backup-cleanup").listFiles().orEmpty().isEmpty())
        }finally {
            service.close();scope.cancel();base.deleteRecursively();app.deleteSharedPreferences("$prefix-workspaces")
        }
    }
}
