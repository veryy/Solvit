package dev.solvit.storage

import dev.solvit.core.ChatAttachment
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class AttachmentFilesTest {
    private fun fixture(block: suspend (File, String, AttachmentFiles) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("solvit-attachments-").toFile()
        try { block(root, UUID.randomUUID().toString(), AttachmentFiles(root)) }
        finally { root.deleteRecursively() }
    }
    @Test fun importedBytesSurviveReopenAndSameNamesRemainIndependent() = fixture { root, session, store ->
        val text = "中文代码 😀\nprint('hello')".toByteArray()
        val first = store.import(session, "代码.py", "text/plain", ByteArrayInputStream(text))
        val second = store.import(session, "代码.py", "text/plain", ByteArrayInputStream("different".toByteArray()))
        assertNotEquals(first.id, second.id)
        assertEquals(text.size.toLong(), first.sizeBytes)
        assertContentEquals(text, AttachmentFiles(root).read(session, first))
        assertFailsWith<IllegalStateException> { store.read(UUID.randomUUID().toString(), first) }
        store.remove(session, second)
        assertContentEquals(text, store.read(session, first))
        assertFailsWith<IllegalStateException> { store.read(session, second) }
    }
    @Test fun sameLengthCorruptionAndExternalSymlinksCannotReturnAttachmentBytes() = fixture { root, session, store ->
        val reference = store.import(session, "file.txt", "text/plain", ByteArrayInputStream("original".toByteArray()))
        val path = File(File(root, session), reference.id + ".blob")
        path.writeText("modified")
        assertFailsWith<IllegalStateException> { store.read(session, reference) }
        val outside = Files.createTempFile("solvit-outside-", ".txt").toFile()
        try {
            outside.writeText("original")
            assertTrue(path.delete())
            Files.createSymbolicLink(path.toPath(), outside.toPath())
            assertFailsWith<IllegalStateException> { store.read(session, reference) }
            assertFailsWith<IllegalStateException> { store.remove(session, reference) }
            assertEquals("original", outside.readText())
        } finally { outside.delete() }
    }
    @Test fun recoveryDoesNotFollowSessionOrBlobLinksOrDeleteUnknownContent() = fixture { root, session, store ->
        val outside = Files.createTempDirectory("attachment-outside").toFile()
        try {
            val saved = store.import(session, "keep.txt", "text/plain", ByteArrayInputStream("keep".toByteArray()))
            val unsafeId = UUID.randomUUID().toString()
            val folder = File(root, session)
            val target = File(outside, "target.txt").apply { writeText("outside") }
            Files.createSymbolicLink(File(folder, "$unsafeId.blob").toPath(), target.toPath())
            Files.createSymbolicLink(File(root, UUID.randomUUID().toString()).toPath(), outside.toPath())
            File(folder, "unknown").mkdir()
            File(folder, "unknown/keep.txt").writeText("unknown")
            store.removeUnreferenced(mapOf(session to setOf(saved.id)))
            assertEquals("outside", target.readText())
            assertEquals("unknown", File(folder, "unknown/keep.txt").readText())
            assertContentEquals("keep".toByteArray(), store.read(session, saved))
            assertFalse(Files.exists(File(folder, "$unsafeId.blob").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        } finally { outside.deleteRecursively() }
    }
    @Test fun revokedAuthorityAndOversizedStreamsLeaveNoPartialOrInstalledBlob() = fixture { root, session, store ->
        var approvals = 0
        assertFailsWith<IllegalStateException> {
            store.import(session, "a.txt", "text/plain", ByteArrayInputStream("private".toByteArray())) {
                approvals++; check(approvals < 3) { "revoked after installation" }
            }
        }
        assertEquals(3, approvals)
        assertTrue(File(root, session).listFiles().orEmpty().isEmpty())
        val oversized = object : InputStream() {
            var remaining = ChatAttachment.MAX_ATTACHMENT_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 1 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                buffer.fill(1, offset, offset + count); remaining -= count
                return count
            }
        }
        assertFailsWith<IllegalArgumentException> { store.import(session, "big.bin", "application/octet-stream", oversized) }
        assertTrue(File(root, session).listFiles().orEmpty().isEmpty())
        assertFailsWith<IllegalArgumentException> {
            store.import(session, "../outside", "text/plain", ByteArrayInputStream(byteArrayOf()))
        }
        assertTrue(File(root, session).listFiles().orEmpty().isEmpty())
    }
}
