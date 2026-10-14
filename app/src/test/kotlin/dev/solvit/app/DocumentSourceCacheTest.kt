package dev.solvit.app

import dev.solvit.documents.DocumentSourceCache
import java.nio.file.Files
import java.util.UUID
import org.junit.Test
import kotlin.test.*

class DocumentSourceCacheTest {
    @Test fun interruptedSourceAndScratchAreRemovedWithoutFollowingLinksOrTouchingUnknownFolders() {
        val root=Files.createTempDirectory("document-cache").toFile().canonicalFile
        try {
            val cache=root.resolve("document-reader").apply { mkdir() }
            val interrupted=cache.resolve(UUID.randomUUID().toString()).apply { mkdir() }
            interrupted.resolve("source.pdf").writeText("private PDF")
            interrupted.resolve("PDFBox.tmp").writeText("scratch")
            val outside=root.resolve("outside").apply { mkdir() }
            outside.resolve("keep.pdf").writeText("keep")
            Files.createSymbolicLink(interrupted.resolve("link").toPath(),outside.toPath())
            val unknown=cache.resolve("unknown").apply { mkdir() }
            unknown.resolve("keep").writeText("unknown")
            val directoryLink=cache.resolve(UUID.randomUUID().toString())
            Files.createSymbolicLink(directoryLink.toPath(),outside.toPath())
            DocumentSourceCache(cache).removeInterruptedReads()
            assertFalse(interrupted.exists())
            assertEquals("keep",outside.resolve("keep.pdf").readText())
            assertEquals("unknown",unknown.resolve("keep").readText())
            assertTrue(Files.isSymbolicLink(directoryLink.toPath()))
            Files.delete(directoryLink.toPath())
        } finally { root.deleteRecursively() }
    }
    @Test fun aRedirectedCacheRootIsRejectedAndAbsentCacheDoesNotGetCreated() {
        val root=Files.createTempDirectory("document-cache").toFile().canonicalFile
        try {
            val cache=root.resolve("document-reader")
            DocumentSourceCache(cache).removeInterruptedReads();assertFalse(cache.exists())
            val outside=root.resolve("outside").apply { mkdir() }
            outside.resolve("keep.pdf").writeText("keep")
            Files.createSymbolicLink(cache.toPath(),outside.toPath())
            assertFailsWith<IllegalStateException> { DocumentSourceCache(cache).removeInterruptedReads() }
            assertEquals("keep",outside.resolve("keep.pdf").readText())
            Files.delete(cache.toPath())
        } finally { root.deleteRecursively() }
    }
}
