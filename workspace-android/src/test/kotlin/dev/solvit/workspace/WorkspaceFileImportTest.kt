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
class WorkspaceFileImportTest {
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fixture(block:suspend (AndroidWorkspaces,String,File)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try {
            val workspace=service.create("file import fixture")
            block(service,workspace.id,File(service.location(workspace.id)))
        } finally { service.close();scope.cancel() }
    }
    @Test fun createsExactBinaryBytesVisibleToExistingListingAndSurvivesServiceReload()=fixture { service,id,root ->
        val bytes=ByteArray(300_000) { (it%256).toByte() };val sha=digest(bytes)
        val result=service.importFile(id,"scan.pdf",bytes,sha)
        assertEquals(WorkspaceImportedFile(id,"scan.pdf",bytes.size.toLong(),sha),result)
        assertContentEquals(bytes,File(root,"scan.pdf").readBytes())
        assertEquals(bytes.size.toLong(),service.list(id).single().bytes)
        assertEquals(1,root.listFiles()!!.size)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val reopened=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try { assertEquals("scan.pdf",reopened.list(id).single().name) }
        finally { reopened.close();scope.cancel() }
    }
    @Test fun rejectsExistingAndConcurrentNamesWithoutOverwritingOrLeavingPartialFiles()=fixture { service,id,root ->
        val bytes=byteArrayOf(0,1,2,3);val sha=digest(bytes)
        File(root,"existing").writeText("keep")
        assertFails { service.importFile(id,"existing",bytes,sha) }
        assertEquals("keep",File(root,"existing").readText())
        var checks=0
        withContext(WorkspacePageAuthority { _,_ ->
            checks++
            if(checks==3) File(root,"race.bin").writeText("shell content")
        }) { assertFails { service.importFile(id,"race.bin",bytes,sha) } }
        assertEquals(3,checks);assertEquals("shell content",File(root,"race.bin").readText())
        assertEquals(setOf("existing","race.bin"),root.listFiles()!!.map { it.name }.toSet())
    }
    @Test fun refusesBadHashTraversalInternalPathsAndSymlinkDestinations()=fixture { service,id,root ->
        val bytes=byteArrayOf(1,2);val sha=digest(bytes)
        for(path in listOf("../escape","/absolute","missing/file","a/../file",".git/config",".solvit-import-fake"))
            assertFails { service.importFile(id,path,bytes,sha) }
        assertFails { service.importFile(id,"bad.bin",bytes,"a".repeat(64)) }
        assertTrue(root.listFiles()!!.isEmpty())
        val outside=File(root.parentFile,"outside-${java.util.UUID.randomUUID()}").apply { mkdir() }
        try {
            val link=File(root,"linked");Files.createSymbolicLink(link.toPath(),outside.toPath())
            assertFails { service.importFile(id,"linked/escape",bytes,sha) }
            assertTrue(outside.listFiles()!!.isEmpty());Files.delete(link.toPath())
        } finally { outside.deleteRecursively() }
    }
    @Test fun revocationBeforePublicationLeavesNoTargetOrStaging()=fixture { service,id,root ->
        val bytes=byteArrayOf(0,1);var checks=0
        withContext(WorkspacePageAuthority { target,_ ->
            assertEquals(id,target);checks++;check(checks<3) { "revoked" }
        }) { assertFails { service.importFile(id,"revoked.bin",bytes,digest(bytes)) } }
        assertEquals(3,checks);assertTrue(root.listFiles()!!.isEmpty())
    }
}
