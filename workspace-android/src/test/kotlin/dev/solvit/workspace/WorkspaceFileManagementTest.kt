package dev.solvit.workspace

import dev.solvit.core.*
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceFileManagementTest {
    private fun fixture(block:suspend (AndroidWorkspaces,String,File)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try {
            val workspace=service.create("file management fixture")
            block(service,workspace.id,File(service.location(workspace.id)))
        }finally { service.close();scope.cancel() }
    }
    @Test fun createsMovesAndDeletesTheActualNestedDirectoryWithoutReplacingExistingFiles()=fixture { service,id,root ->
        service.makeDirectory(id,"src/nested")
        assertTrue(File(root,"src/nested").isDirectory)
        service.write(id,"src/nested/code.kt","original")
        service.write(id,"src/nested/existing.kt","keep")
        assertFails { service.moveEntry(id,"src/nested/code.kt","src/nested/existing.kt") }
        assertEquals("original",File(root,"src/nested/code.kt").readText())
        assertEquals("keep",File(root,"src/nested/existing.kt").readText())
        service.moveEntry(id,"src/nested/code.kt","src/nested/renamed.kt")
        assertFalse(File(root,"src/nested/code.kt").exists())
        assertEquals("original",File(root,"src/nested/renamed.kt").readText())
        service.moveEntry(id,"src/nested","src/module")
        assertEquals("original",File(root,"src/module/renamed.kt").readText())
        service.deleteEntry(id,"src/module")
        assertTrue(service.list(id,"src").isEmpty());assertTrue(File(root,"src").isDirectory)
    }
    @Test fun refusesRootEscapesAndRevocationAndDoesNotFollowLinksDuringRecursiveRemoval()=fixture { service,id,root ->
        service.makeDirectory(id,"folder")
        service.write(id,"folder/note.txt","inside")
        val other=service.create("outside");val outside=File(service.location(other.id),"keep.txt").apply { writeText("outside") }
        Files.createSymbolicLink(File(root,"folder/link").toPath(),outside.parentFile.toPath())
        for(path in listOf("", ".", "..", "../${other.id}",outside.absolutePath)) {
            assertFails { service.deleteEntry(id,path) }
            assertFails { service.makeDirectory(id,path) }
        }
        assertFails { service.moveEntry(id,"folder","folder/nested") }
        assertFails { service.moveEntry(id,"folder","../${other.id}/stolen") }
        var checks=0
        withContext(WorkspacePageAuthority { target,_ ->
            assertEquals(id,target);checks++;check(checks<2) { "revoked before mutation" }
        }) { assertFails { service.deleteEntry(id,"folder") } }
        assertEquals(2,checks);assertEquals("inside",File(root,"folder/note.txt").readText())
        service.deleteEntry(id,"folder")
        assertFalse(File(root,"folder").exists());assertEquals("outside",outside.readText())
    }
}
