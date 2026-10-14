package dev.solvit.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class WorkspaceFileReplacementTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val move=WorkspaceImportPublisher { source,destination -> Files.move(source.toPath(),destination.toPath());Unit }
    private fun fixture(block:suspend (File)->Unit)=runBlocking {
        val root=Files.createTempDirectory("solvit-replacement-").toFile().canonicalFile
        try { block(root) } finally { root.deleteRecursively() }
    }
    @Test fun replacesCompleteBinaryAndRetainsOldInodeIncludingOpenWriter()=fixture { root ->
        val destination=File(root,"file.bin");destination.writeText("old")
        val reviewed=WorkspaceFileImports.review(root,"file.bin") {}!!
        val bytes=ByteArray(300_000) { (it%256).toByte() }
        java.io.RandomAccessFile(destination,"rw").use { writer ->
            val result=WorkspaceFileImports.replace(root,"file.bin",bytes,sha(bytes),reviewed.sha256,move) {}
            assertEquals(bytes.size.toLong(),result.first);assertEquals(sha(bytes),result.second)
            assertContentEquals(bytes,destination.readBytes())
            val backup=File(root,result.third);assertEquals("old",backup.readText())
            writer.seek(0);writer.write("new".toByteArray())
            assertEquals("new",backup.readText())
            assertContentEquals(bytes,destination.readBytes())
            assertEquals(2,root.listFiles()!!.size)
        }
    }
    @Test fun staleDeletedAndInvalidSourceNeverMoveOriginal()=fixture { root ->
        val destination=File(root,"file.bin").apply { writeText("old") }
        val reviewed=WorkspaceFileImports.review(root,"file.bin") {}!!
        val bytes="replacement".toByteArray()
        destination.writeText("external")
        assertFails { WorkspaceFileImports.replace(root,"file.bin",bytes,sha(bytes),reviewed.sha256,move) {} }
        assertEquals("external",destination.readText());assertEquals(1,root.listFiles()!!.size)
        destination.delete()
        assertFails { WorkspaceFileImports.replace(root,"file.bin",bytes,sha(bytes),reviewed.sha256,move) {} }
        assertTrue(root.listFiles()!!.isEmpty())
        destination.writeText("old")
        assertFails { WorkspaceFileImports.replace(root,"file.bin",bytes,"0".repeat(64),reviewed.sha256,move) {} }
        assertEquals("old",destination.readText());assertEquals(1,root.listFiles()!!.size)
    }
    @Test fun detectsChangeAtClaimAndRestoresTheChangedFile()=fixture { root ->
        val destination=File(root,"file.bin").apply { writeText("old") }
        val reviewed=WorkspaceFileImports.review(root,"file.bin") {}!!
        val bytes="replacement".toByteArray()
        val racing=WorkspaceImportPublisher { source,target ->
            if(source==destination) source.writeText("changed during confirmation")
            move.publish(source,target)
        }
        assertFails { WorkspaceFileImports.replace(root,"file.bin",bytes,sha(bytes),reviewed.sha256,racing) {} }
        assertEquals("changed during confirmation",destination.readText());assertEquals(1,root.listFiles()!!.size)
    }
    @Test fun concurrentNewNameWinsAndDisplacedFileRemainsRecoverable()=fixture { root ->
        val destination=File(root,"file.bin").apply { writeText("old") }
        val reviewed=WorkspaceFileImports.review(root,"file.bin") {}!!
        val bytes="replacement".toByteArray()
        val racing=WorkspaceImportPublisher { source,target ->
            if(source.name.startsWith(".solvit-import-")) destination.writeText("concurrent Shell")
            move.publish(source,target)
        }
        val failure=assertFails { WorkspaceFileImports.replace(root,"file.bin",bytes,sha(bytes),reviewed.sha256,racing) {} }
        assertTrue(failure.message!!.contains("solvit-backup-"))
        assertEquals("concurrent Shell",destination.readText())
        assertEquals("old",root.listFiles()!!.single { it.name.startsWith("solvit-backup-") }.readText())
        assertEquals(2,root.listFiles()!!.size)
    }
    @Test fun cancellationAndRevocationAfterClaimRestoreOriginalWithoutPartials()=fixture { root ->
        val destination=File(root,"file.bin").apply { writeText("old") }
        val reviewed=WorkspaceFileImports.review(root,"file.bin") {}!!
        val bytes="replacement".toByteArray()
        for(cancel in listOf(false,true)) {
            val failure=assertFails {
                WorkspaceFileImports.replace(root,"file.bin",bytes,sha(bytes),reviewed.sha256,move) {
                    if(root.listFiles()!!.any { it.name.startsWith("solvit-backup-") }) {
                        if(cancel) throw CancellationException("cancel") else error("revoked")
                    }
                }
            }
            if(cancel) assertTrue(failure is CancellationException)
            assertEquals("old",destination.readText());assertEquals(1,root.listFiles()!!.size)
        }
    }
    @Test fun reviewRejectsSymlinksDirectoriesOversizedAndInternalPaths()=fixture { root ->
        assertNull(WorkspaceFileImports.review(root,"absent") {})
        File(root,"folder").mkdir()
        File(root,"real").writeText("private")
        Files.createSymbolicLink(File(root,"link").toPath(),File(root,"real").toPath())
        java.io.RandomAccessFile(File(root,"huge"),"rw").use { it.setLength(21L*1024*1024) }
        for(path in listOf("folder","link","huge","../outside",".git/config")) {
            assertFails { WorkspaceFileImports.review(root,path) {} }
        }
        assertEquals("private",File(root,"real").readText())
    }
}
