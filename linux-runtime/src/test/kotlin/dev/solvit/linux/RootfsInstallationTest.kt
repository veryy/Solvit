package dev.solvit.linux

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class RootfsInstallationTest {
    private fun fixture(parent: File): Pair<File, RootfsSource> {
        val file = File(parent, "fixture.tar.xz")
        XZOutputStream(file.outputStream(), LZMA2Options()).use { xz ->
            TarArchiveOutputStream(xz).use { tar ->
                for ((path, text) in listOf("usr/bin/bash" to "bash", "etc/os-release" to "ID=ubuntu")) {
                    val bytes = text.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry("ubuntu/$path").apply { size = bytes.size.toLong() })
                    tar.write(bytes); tar.closeArchiveEntry()
                }
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return file to RootfsSource("https://example.invalid/fixture", hash, "ubuntu")
    }

    @Test fun activationKeepsFinalDirectoryAndSurvivesNewManager(): Unit = runBlocking {
        val parent = Files.createTempDirectory("linux install").toFile()
        try {
            val (fixture, source) = fixture(parent)
            val directory = File(parent, "environments")
            val downloader = RootfsDownload { _, target, progress -> fixture.copyTo(target); progress(fixture.length(), fixture.length()) }
            val installer = RootfsInstallation(directory, source, downloader, 0)
            var initializationPath: String? = null
            val root = installer.install { initializationPath = it.absolutePath; File(it, "bootstrap-result").writeText(it.absolutePath) }
            assertEquals(initializationPath, root.absolutePath)
            assertEquals(root.absolutePath, File(root, "bootstrap-result").readText())
            assertEquals(root, RootfsInstallation(directory, source, downloader, 0).activeRoot())
            assertEquals(InstallationStage.READY, installer.state.value.stage)
            assertEquals(setOf("active", root.name), directory.list()!!.toSet())
        } finally { parent.deleteRecursively() }
    }

    @Test fun failedAndCancelledInitializationKeepOldEnvironmentAndCleanCandidates(): Unit = runBlocking {
        val parent = Files.createTempDirectory("linux install").toFile()
        try {
            val (fixture, source) = fixture(parent)
            val directory = File(parent, "environments")
            val installer = RootfsInstallation(directory, source, RootfsDownload { _, target, _ -> fixture.copyTo(target) }, 0)
            val old = installer.install { File(it, "kept").writeText("old") }
            assertFailsWith<IllegalStateException> { installer.install { error("apt exit 100") } }
            assertEquals(InstallationStage.FAILED, installer.state.value.stage)
            assertEquals("apt exit 100", installer.state.value.details)
            assertTrue(installer.state.value.hasActiveEnvironment)
            assertEquals(old, installer.activeRoot())
            assertFailsWith<CancellationException> { installer.install { throw CancellationException("user stopped") } }
            assertEquals(InstallationStage.CANCELLED, installer.state.value.stage)
            assertEquals("old", File(old, "kept").readText())
            assertEquals(old, installer.activeRoot())
            assertEquals(setOf("active", old.name), directory.list()!!.toSet())
        } finally { parent.deleteRecursively() }
    }

    @Test fun partialDownloadCannotActivateAndPointerCannotEscape(): Unit = runBlocking {
        val parent = Files.createTempDirectory("linux install").toFile()
        try {
            val (_, source) = fixture(parent)
            val directory = File(parent, "environments")
            val installer = RootfsInstallation(directory, source, RootfsDownload { _, target, _ ->
                target.writeText("partial"); throw CancellationException("download stopped")
            }, 0)
            assertFailsWith<CancellationException> { installer.install { error("must not initialize") } }
            assertNull(installer.activeRoot()); assertTrue(directory.list()!!.isEmpty())
            File(directory, "active").writeText("../outside")
            assertNull(installer.activeRoot())
            File(directory, "active").delete()
            Files.createSymbolicLink(File(directory, "active").toPath(), File(parent, "fixture.tar.xz").toPath())
            assertNull(installer.activeRoot())
        } finally { parent.deleteRecursively() }
    }
}
