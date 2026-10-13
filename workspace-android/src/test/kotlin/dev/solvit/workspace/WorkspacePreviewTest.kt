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
class WorkspacePreviewTest {
    private fun fixture(block:suspend (AndroidWorkspaces,String,File)->Unit)=runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try {
            val workspace=service.create("preview fixture")
            block(service,workspace.id,File(service.location(workspace.id)))
        }finally { service.close();scope.cancel() }
    }
    @Test fun reconstructsCompleteUnicodeAtTheFileLimitWithoutSplittingSurrogates()=fixture { service,id,root ->
        val text="a".repeat(4095)+"🙂"+"界".repeat(80000)
        File(root,"large.kt").writeText(text)
        assertTrue(text.toByteArray().size>4096)
        var offset=0;var digest:String?=null;val result=StringBuilder()
        do {
            val chunk=service.readTextChunk(id,"large.kt",offset,digest)
            assertEquals(offset,chunk.offset);assertEquals(text.length,chunk.total)
            assertTrue(chunk.text.length<=4096)
            assertFalse(chunk.text.lastOrNull()?.isHighSurrogate()==true)
            assertFalse(chunk.text.firstOrNull()?.isLowSurrogate()==true)
            if(digest==null)digest=chunk.sha256 else assertEquals(digest,chunk.sha256)
            result.append(chunk.text);assertTrue(chunk.nextOffset>offset)
            offset=chunk.nextOffset
        }while(offset<text.length)
        assertEquals(text,result.toString())
        assertEquals("",service.readTextChunk(id,"large.kt",offset,digest).text)
        val exact="x".repeat(MAX_FILE_CAPTURE_BYTES)
        File(root,"limit.txt").writeText(exact)
        assertEquals(exact.length,service.readTextChunk(id,"limit.txt").total)
    }
    @Test fun rejectsSameSizeTimestampReplacementAndRequiresVersionForContinuation()=fixture { service,id,root ->
        val file=File(root,"code.txt").apply { writeText("a".repeat(8000)) }
        val first=service.readTextChunk(id,"code.txt")
        val timestamp=file.lastModified();file.writeText("b".repeat(8000));assertTrue(file.setLastModified(timestamp))
        assertFailsWith<IllegalStateException> { service.readTextChunk(id,"code.txt",first.nextOffset,first.sha256) }
        assertFailsWith<IllegalArgumentException> { service.readTextChunk(id,"code.txt",first.nextOffset) }
        val refreshed=service.readTextChunk(id,"code.txt");assertNotEquals(first.sha256,refreshed.sha256)
        assertEquals("b".repeat(4096),refreshed.text)
        assertTrue(file.delete())
        assertFails { service.readTextChunk(id,"code.txt",refreshed.nextOffset,refreshed.sha256) }
    }
    @Test fun enforcesAuthorityPathStrictUtf8AndPageBoundaries()=fixture { service,id,root ->
        File(root,"emoji.txt").writeText("a🙂b")
        val first=service.readTextChunk(id,"emoji.txt")
        assertFailsWith<IllegalArgumentException> { service.readTextChunk(id,"emoji.txt",2,first.sha256) }
        assertFailsWith<IllegalArgumentException> { service.readTextChunk(id,"emoji.txt",5,first.sha256) }
        assertFails { service.readTextChunk(id,"../other.txt") }
        File(root,"binary").writeBytes(byteArrayOf(0,1,2))
        File(root,"invalid").writeBytes(byteArrayOf(0xc3.toByte(),0x28))
        File(root,"oversized").writeText("x".repeat(MAX_FILE_CAPTURE_BYTES+1))
        for(path in listOf("binary","invalid","oversized"))assertFails { service.readTextChunk(id,path) }
        var allowed=true
        withContext(WorkspacePageAuthority { target,_ -> check(allowed&&target==id) }) {
            assertEquals("a🙂b",service.readTextChunk(id,"emoji.txt").text)
            allowed=false
            assertFailsWith<IllegalStateException> { service.readTextChunk(id,"emoji.txt") }
        }
        var checks=0
        withContext(WorkspacePageAuthority { target,_ ->
            assertEquals(id,target);checks++;check(checks<2) { "revoked during read" }
        }) {
            assertFailsWith<IllegalStateException> { service.readTextChunk(id,"emoji.txt") }
        }
        assertEquals(2,checks)
    }
}
