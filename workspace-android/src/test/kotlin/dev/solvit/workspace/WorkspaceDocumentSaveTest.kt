package dev.solvit.workspace

import dev.solvit.core.*
import kotlinx.coroutines.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WorkspaceDocumentSaveTest {
    private fun fixture(block:suspend (AndroidWorkspaces,String,File)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try {
            val workspace=service.create("document save fixture")
            block(service,workspace.id,File(service.location(workspace.id)))
        }finally { service.close();scope.cancel() }
    }
    @Test fun savesCompleteUnicodeDocumentAndKeepsLegacySmallEditorBounded()=fixture { service,id,root ->
        val file=File(root,"code.kt").apply { writeText("old") }
        val opened=service.readTextChunk(id,"code.kt")
        val text="// 🙂\n"+"val 中文 = 1\n".repeat(9000)
        val saved=service.writeTextForEdit(id,"code.kt",text,opened.sha256)
        assertEquals(text,file.readText())
        assertTrue(saved.verified);assertEquals(true,saved.changed)
        assertEquals(opened.sha256,saved.before.sha256)
        assertEquals(service.readTextChunk(id,"code.kt").sha256,saved.after.sha256)
        assertFails { service.writeForEdit(id,"code.kt",text,saved.after.sha256) }
        assertEquals(text,file.readText())
        val limit="x".repeat(MAX_FILE_CAPTURE_BYTES)
        service.writeTextForEdit(id,"limit.txt",limit,null)
        assertEquals(limit,File(root,"limit.txt").readText())
    }
    @Test fun refusesStaleAndCreateConflictsWithoutChangingTheFile()=fixture { service,id,root ->
        val file=File(root,"code.txt").apply { writeText("a".repeat(8000)) }
        val opened=service.readTextChunk(id,"code.txt")
        val timestamp=file.lastModified();file.writeText("b".repeat(8000));assertTrue(file.setLastModified(timestamp))
        assertFails { service.writeTextForEdit(id,"code.txt","editor replacement",opened.sha256) }
        assertFails { service.writeTextForEdit(id,"code.txt","create replacement",null) }
        assertEquals("b".repeat(8000),file.readText())
        assertTrue(file.delete())
        assertFails { service.writeTextForEdit(id,"code.txt","stale resurrect",opened.sha256) }
        assertFalse(file.exists())
        service.writeTextForEdit(id,"code.txt","new document",null)
        assertEquals("new document",file.readText())
    }
    @Test fun rejectsInvalidTextAndRevocationBeforeCommitWithoutCreatingParents()=fixture { service,id,root ->
        for(text in listOf("x".repeat(MAX_FILE_CAPTURE_BYTES+1),"bad\u0000text","bad\uD800text","bad\uDC00text")) {
            assertFails { service.writeTextForEdit(id,"new/invalid.txt",text,null) }
            assertFalse(File(root,"new").exists())
        }
        assertFails { service.writeTextForEdit(id,"../escape.txt","escape",null) }
        var checks=0
        withContext(WorkspacePageAuthority { target,_ ->
            assertEquals(id,target);checks++;check(checks<2) { "revoked before commit" }
        }) {
            assertFails { service.writeTextForEdit(id,"new/revoked.txt","valid",null) }
        }
        assertEquals(2,checks);assertFalse(File(root,"new").exists())
    }
}
