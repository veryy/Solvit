package dev.solvit.workspace

import dev.solvit.core.*
import dev.solvit.system.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceSnapshotTest {
    private suspend fun fixture(block: suspend (AndroidWorkspaces,String)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val ws=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope,ShellLauncher(ShellPaths("/bin/sh","/usr/bin/setsid")))
        try { block(ws,ws.create("Snapshot task").id) } finally { ws.close();scope.cancel() }
    }
    @Test fun observesRealShellCreatedChangedDeletedFilesAndSnapshotsSurviveLaterEdits()=runBlocking<Unit> {
        fixture { ws,id ->
            ws.write(id,"src/change.txt","before\n");ws.write(id,"deleted.txt","gone\n")
            val before=ws.snapshot(id);assertTrue(before.complete)
            val shell=ws.createShell(id,"Change")
            val job=ws.run(id,shell.id,"printf 'after\\n' > src/change.txt; printf 'new\\n' > added.txt; rm deleted.txt")
            withTimeout(10_000) { ws.jobs.first { jobs -> jobs.any { it.id==job.id && it.stage !in setOf(ShellStage.STARTING,ShellStage.RUNNING) } } }
            assertEquals(0,ws.jobs.value.single { it.id==job.id }.exitCode)
            val after=ws.snapshot(id);val diff=compareWorkspaceSnapshots(before,after)
            assertTrue(diff.complete)
            assertEquals(mapOf("src/change.txt" to WorkspaceChangeKind.MODIFIED,"deleted.txt" to WorkspaceChangeKind.DELETED,"added.txt" to WorkspaceChangeKind.CREATED),diff.changes.associate { it.path to it.kind })
            assertEquals("before\n",before.files.getValue("src/change.txt").excerpt)
            assertEquals("after\n",after.files.getValue("src/change.txt").excerpt)
            ws.write(id,"src/change.txt","later")
            assertEquals("after\n",after.files.getValue("src/change.txt").excerpt)
        }
    }
    @Test fun refusesLinksBoundsCoverageAndDoesNotFabricateDeletionOutsideSnapshot()=runBlocking<Unit> {
        fixture { ws,id ->
            val base=File(ws.location(id));val outside=File(RuntimeEnvironment.getApplication().cacheDir,"snapshot-outside").apply { mkdirs() }
            File(outside,"private.txt").writeText("not observable")
            Files.createSymbolicLink(File(base,"escape").toPath(),outside.toPath())
            val linked=ws.snapshot(id)
            assertFalse(linked.complete);assertEquals(FileObservationKind.UNAVAILABLE,linked.files.getValue("escape").kind)
            assertFalse(linked.files.values.any { it.excerpt?.contains("not observable")==true })
            File(base,"escape").delete()
            repeat(70) { File(base,"item-${it.toString().padStart(2,'0')}").writeText("$it") }
            val bounded=ws.snapshot(id);assertEquals(64,bounded.files.size);assertFalse(bounded.complete)
            val fullPrefix=WorkspaceSnapshot(id,bounded.files+emptyMap(),true,1)
            assertTrue(compareWorkspaceSnapshots(fullPrefix,bounded).changes.isEmpty())
            assertFalse(compareWorkspaceSnapshots(fullPrefix,bounded).complete)
        }
    }
}
