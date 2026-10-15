package dev.solvit.workspace

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
class WorkspaceImportCapacityServiceTest {
    @Test fun lowSpaceRejectsBothServiceEntryPointsBeforeMovingOriginalOrCreatingJournalThenRetryWorks()=runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        var space=0L;var moves=0
        val service=AndroidWorkspaces(context,scope,importAvailableBytes={space},importPublisher=WorkspaceImportPublisher { source,destination ->
            moves++;Files.move(source.toPath(),destination.toPath());Unit
        })
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        try {
            val workspace=service.create("Capacity ${java.util.UUID.randomUUID()}")
            val root=File(service.location(workspace.id))
            val old="original must stay".toByteArray();val replacement="replacement".toByteArray()
            File(root,"target.txt").writeBytes(old)
            val denied=assertFailsWith<IllegalStateException> {
                service.replaceImportedFile(workspace.id,"target.txt",replacement,sha(replacement),sha(old))
            }
            assertTrue(denied.message!!.contains("存储空间不足"));assertTrue(denied.message!!.contains("0 B"))
            assertFails { service.importFile(workspace.id,"new.txt",replacement,sha(replacement)) }
            assertEquals(0,moves);assertEquals(listOf("target.txt"),root.listFiles()!!.map { it.name })
            assertContentEquals(old,File(root,"target.txt").readBytes())
            val ledger=File(context.noBackupFilesDir,"workspace-import-staging/${workspace.id}")
            assertTrue(!ledger.exists()||ledger.listFiles()!!.isEmpty())
            space=WorkspaceImportCapacity.HEADROOM_BYTES+replacement.size
            val result=service.replaceImportedFile(workspace.id,"target.txt",replacement,sha(replacement),sha(old))
            assertContentEquals(replacement,File(root,"target.txt").readBytes())
            assertContentEquals(old,File(root,checkNotNull(result.backupPath)).readBytes())
            service.importFile(workspace.id,"new.txt",replacement,sha(replacement))
            assertContentEquals(replacement,File(root,"new.txt").readBytes());assertEquals(3,moves)
            assertTrue(service.importRecoveries(workspace.id).isEmpty())
        }finally{service.close();scope.cancel()}
    }
}
