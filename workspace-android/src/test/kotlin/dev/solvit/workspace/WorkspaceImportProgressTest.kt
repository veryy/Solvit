package dev.solvit.workspace

import dev.solvit.core.*
import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class WorkspaceImportProgressTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    // Native renameat2 is covered by the real Android HTML scenario, not this JVM seam.
    private val publisher=WorkspaceImportPublisher { source,destination -> Files.move(source.toPath(),destination.toPath());Unit }
    private fun fixture(block:suspend (File,ByteArray)->Unit)=runBlocking<Unit> {
        val root=Files.createTempDirectory("solvit-import-progress").toFile()
        try { block(root,ByteArray(300_000) { (it%251).toByte() }) } finally { root.deleteRecursively() }
    }
    @Test fun reportsActualStagedBytesAndOnlyCommitsAfterAllBytesAndVerification()=fixture { root,bytes ->
        val events=mutableListOf<WorkspaceImportProgress>()
        withContext(WorkspaceImportMonitor { event ->
            events.add(event)
            if(event.stage=="WRITING") {
                assertFalse(File(root,"new.bin").exists())
                assertEquals(event.completed,root.listFiles()!!.single().length())
            }
        }) { WorkspaceFileImports.create(root,"new.bin",bytes,sha(bytes),publisher) {} }
        val writes=events.filter { it.stage=="WRITING" }
        assertTrue(writes.size>1)
        assertEquals(listOf(65536L,131072L,196608L,262144L,300000L),writes.map { it.completed })
        assertTrue(events.all { it.total==bytes.size.toLong() })
        assertEquals(listOf("PREPARING","WRITING","SYNCING","VERIFYING","PUBLISHING","COMMITTED"),events.map { it.stage }.distinct())
        assertContentEquals(bytes,File(root,"new.bin").readBytes())
        assertEquals(listOf("new.bin"),root.listFiles()!!.map { it.name })
    }
    @Test fun cancelDuringCopyDeletesPartialAndDoesNotPublish()=fixture { root,bytes ->
        assertFailsWith<CancellationException> {
            withContext(WorkspaceImportMonitor { if(it.stage=="WRITING")throw CancellationException("stop") }) {
                WorkspaceFileImports.create(root,"new.bin",bytes,sha(bytes),publisher) {}
            }
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun cancelReplacementRestoresOriginalAndRemovesPartialBackup()=fixture { root,bytes ->
        val old="original Shell content".toByteArray();File(root,"target").writeBytes(old)
        assertFailsWith<CancellationException> {
            withContext(WorkspaceImportMonitor { if(it.stage=="WRITING")throw CancellationException("stop") }) {
                WorkspaceFileImports.replace(root,"target",bytes,sha(bytes),sha(old),publisher) {}
            }
        }
        assertContentEquals(old,File(root,"target").readBytes())
        assertEquals(listOf("target"),root.listFiles()!!.map { it.name })
    }
    @Test fun lastCheckpointCanCancelButCommittedObservationCannotUndoPublishedFile()=fixture { root,bytes ->
        assertFailsWith<CancellationException> {
            withContext(WorkspaceImportMonitor { if(it.stage=="PUBLISHING")throw CancellationException("stop") }) {
                WorkspaceFileImports.create(root,"cancelled",bytes,sha(bytes),publisher) {}
            }
        }
        assertTrue(root.listFiles()!!.isEmpty())
        withContext(WorkspaceImportMonitor { if(it.stage=="COMMITTED")error("observer closed after commit") }) {
            WorkspaceFileImports.create(root,"complete",bytes,sha(bytes),publisher) {}
        }
        assertContentEquals(bytes,File(root,"complete").readBytes())
    }
    @Test fun cleanupRemovesOnlyOrphanRegularReservedFilesAndProtectsActiveImports()=fixture { root,bytes ->
        val orphan=File(root,"nested/.solvit-import-123456.partial").apply { parentFile!!.mkdir();writeText("orphan") }
        val original=File(root,"solvit-backup-preserve").apply { writeText("original") }
        val ordinary=File(root,"document.partial").apply { writeText("user file") }
        val git=File(root,".git/.solvit-import-123.partial").apply { parentFile!!.mkdir();writeText("git file") }
        val outside=Files.createTempDirectory("solvit-import-outside").toFile()
        try {
            val external=File(outside,".solvit-import-321.partial").apply { writeText("external") }
            Files.createSymbolicLink(File(root,"linked").toPath(),outside.toPath())
            Files.createSymbolicLink(File(root,".solvit-import-link.partial").toPath(),external.toPath())
            assertEquals(1,WorkspaceFileImports.cleanup(root));assertFalse(orphan.exists())
            assertTrue(original.exists()&&ordinary.exists()&&git.exists()&&external.exists())
            assertTrue(Files.isSymbolicLink(File(root,".solvit-import-link.partial").toPath()))
            withContext(WorkspaceImportMonitor { if(it.stage=="WRITING") {
                assertEquals(0,WorkspaceFileImports.cleanup(root))
                assertEquals(1,root.listFiles()!!.count { f -> f.name.startsWith(".solvit-import-") && !Files.isSymbolicLink(f.toPath()) })
            } }) { WorkspaceFileImports.create(root,"complete",bytes,sha(bytes),publisher) {} }
            assertContentEquals(bytes,File(root,"complete").readBytes())
        } finally { Files.deleteIfExists(File(root,"linked").toPath());Files.deleteIfExists(File(root,".solvit-import-link.partial").toPath());outside.deleteRecursively() }
    }
}
