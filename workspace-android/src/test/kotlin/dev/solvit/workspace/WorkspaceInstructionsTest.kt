package dev.solvit.workspace

import dev.solvit.core.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkspaceInstructionsTest {
    private suspend fun fixture(block: suspend (AndroidWorkspaces, String, File) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ws = AndroidWorkspaces(RuntimeEnvironment.getApplication(), scope,
            ShellLauncher(ShellPaths("/bin/sh", "/usr/bin/setsid")))
        try {
            val id = ws.create("Instruction fixture").id
            block(ws, id, File(ws.location(id)))
        } finally { ws.close(); scope.cancel() }
    }
    @Test fun readsOnlyFixedCandidatesAndKeepsFullUtf8BeyondExistingExcerpts() = runBlocking<Unit> {
        fixture { ws, id, root ->
            val text = "项目规则🙂\n".repeat(700)
            assertTrue(text.toByteArray().size > 6 * 1024)
            File(root, "AGENTS.md").writeText(text)
            File(root, "CLAUDE.md").writeText("second")
            File(root, "CLAUDE.local.md").writeText("")
            File(root, "private.txt").writeText("never returned")
            val sources = ws.instructionSources(id) {}
            assertEquals(WorkspaceInstructionSource.NAMES, sources.map { it.path })
            assertEquals(text, sources[0].text)
            assertEquals(WorkspaceInstructionStatus.ABSENT, sources[2].status)
            assertEquals("", sources[3].text)
            assertTrue(sources.none { it.text?.contains("never returned") == true })
            sources.forEach(WorkspaceInstructionSource::validate)
        }
    }
    @Test fun rereadsSameSizeTimestampEditsAndDeletionWithoutStaleContent() = runBlocking<Unit> {
        fixture { ws, id, root ->
            val file = File(root, "AGENTS.md").apply { writeText("first") }
            val first = ws.instructionSources(id) {}.first()
            val stamp = file.lastModified()
            file.writeText("other"); assertTrue(file.setLastModified(stamp))
            val second = ws.instructionSources(id) {}.first()
            assertEquals("other", second.text); assertNotEquals(first.sha256, second.sha256)
            assertTrue(file.delete())
            assertEquals(WorkspaceInstructionStatus.ABSENT, ws.instructionSources(id) {}.first().status)
        }
    }
    @Test fun doesNotFollowLinksAndReportsBinaryDirectoriesAndOversizedSources() = runBlocking<Unit> {
        fixture { ws, id, root ->
            val outside = File(RuntimeEnvironment.getApplication().cacheDir, "outside-instructions").apply { writeText("outside secret") }
            Files.createSymbolicLink(File(root, "AGENTS.md").toPath(), outside.toPath())
            File(root, "CLAUDE.md").mkdirs()
            File(root, "AGENTS.local.md").writeBytes(byteArrayOf(0, 1, 2))
            File(root, "CLAUDE.local.md").writeText("x".repeat(WorkspaceInstructionSource.MAX_SOURCE_BYTES + 1))
            val sources = ws.instructionSources(id) {}
            assertEquals(listOf(WorkspaceInstructionStatus.UNAVAILABLE, WorkspaceInstructionStatus.UNAVAILABLE,
                WorkspaceInstructionStatus.BINARY, WorkspaceInstructionStatus.OVERSIZED), sources.map { it.status })
            assertTrue(sources.all { it.text == null && it.sha256 == null })
        }
    }
    @Test fun invalidUtf8IsNotSilentlyReplacedAndExactLimitIsReadable() = runBlocking<Unit> {
        fixture { ws, id, root ->
            File(root, "AGENTS.md").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            val limit = "界".repeat(5461) + "x"
            assertEquals(WorkspaceInstructionSource.MAX_SOURCE_BYTES, limit.toByteArray().size)
            File(root, "CLAUDE.md").writeText(limit)
            val sources = ws.instructionSources(id) {}
            assertEquals(WorkspaceInstructionStatus.BINARY, sources[0].status)
            assertEquals(limit, sources[1].text)
        }
    }
    @Test fun authorityRevokedAfterReadPreventsReturningAnySource() = runBlocking<Unit> {
        fixture { ws, id, root ->
            File(root, "AGENTS.md").writeText("protected")
            var checks = 0
            assertFailsWith<IllegalStateException> {
                ws.instructionSources(id) { check(++checks < 2) { "revoked" } }
            }
            assertEquals(2, checks)
        }
    }
    @Test fun parentCancellationPropagatesInsteadOfBecomingUnavailable() = runBlocking<Unit> {
        fixture { ws, id, _ ->
            val work = async { ws.instructionSources(id) { awaitCancellation() } }
            yield(); work.cancel()
            assertFailsWith<CancellationException> { work.await() }
        }
    }
}
