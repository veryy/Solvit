package dev.solvit.linux

import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.nio.file.Files
import java.io.File
import java.security.MessageDigest
import kotlin.test.*
import org.junit.Test

class RootfsArchiveTest {
    private fun archive(root: File, malicious: String? = null, linkWrite: Boolean = false): File {
        val file = File(root, "rootfs.tar.xz")
        XZOutputStream(file.outputStream(), LZMA2Options()).use { xz -> TarArchiveOutputStream(xz).use { tar ->
            fun entry(name: String, text: String) {
                val bytes = text.toByteArray(); val e = TarArchiveEntry("ubuntu/$name"); e.size = bytes.size.toLong(); e.mode = 493
                tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
            }
            entry("usr/bin/bash", "fixture bash"); entry("etc/os-release", "ID=ubuntu")
            val link = TarArchiveEntry("ubuntu/bin", '2'.code.toByte()).apply { linkName = "usr/bin" }
            tar.putArchiveEntry(link); tar.closeArchiveEntry()
            val absolute = TarArchiveEntry("ubuntu/etc/fixture-link", '2'.code.toByte()).apply { linkName = "/usr/bin/bash" }
            tar.putArchiveEntry(absolute); tar.closeArchiveEntry()
            if (malicious != null) entry(malicious, "escape")
            if (linkWrite) entry("bin/nested", "escape")
        } }
        return file
    }
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun extractsGuestFilesAndDeferredLinksWithoutFollowingThem() = runBlocking {
        val parent = Files.createTempDirectory("linux archive").toFile()
        try {
            val file = archive(parent); val installed = File(parent, "staging")
            RootfsArchive().extract(file, hash(file), "ubuntu", installed)
            assertEquals("fixture bash", File(installed, "bin/bash").readText())
            assertEquals("/usr/bin/bash", Files.readSymbolicLink(File(installed,"etc/fixture-link").toPath()).toString())
            assertTrue(Files.isExecutable(File(installed,"usr/bin/bash").toPath()))
        } finally { parent.deleteRecursively() }
    }
    @Test fun rejectsTraversalAndRemovesOnlyNewStaging() = runBlocking {
        val parent = Files.createTempDirectory("linux archive").toFile()
        try {
            val old = File(parent,"active").apply { mkdir(); resolve("kept").writeText("old") }
            val file = archive(parent, "../../escaped"); val staging = File(parent,"staging")
            assertFails { RootfsArchive().extract(file,hash(file),"ubuntu",staging) }
            assertFalse(staging.exists()); assertFalse(File(parent.parentFile,"escaped").exists())
            assertEquals("old",File(old,"kept").readText())
        } finally { parent.deleteRecursively() }
    }
    @Test fun rejectsSymlinkAncestorWriteAndCleansStaging() = runBlocking {
        val parent = Files.createTempDirectory("linux archive").toFile()
        try {
            val file = archive(parent,linkWrite=true); val staging=File(parent,"staging")
            assertFails { RootfsArchive().extract(file,hash(file),"ubuntu",staging) }
            assertFalse(staging.exists())
        } finally { parent.deleteRecursively() }
    }
    @Test fun badDigestAndExpansionLimitPreserveExistingInstallation() = runBlocking {
        val parent=Files.createTempDirectory("linux archive").toFile()
        try {
            val file=archive(parent); val staging=File(parent,"staging")
            assertFails { RootfsArchive().extract(file,"0".repeat(64),"ubuntu",staging) }
            assertFalse(staging.exists())
            assertFails { RootfsArchive(maxBytes=3).extract(file,hash(file),"ubuntu",staging) }
            assertFalse(staging.exists())
            staging.mkdir(); File(staging,"kept").writeText("old")
            assertFails { RootfsArchive().extract(file,hash(file),"ubuntu",staging) }
            assertEquals("old",File(staging,"kept").readText())
        } finally { parent.deleteRecursively() }
    }
}
