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
class FileWriteEvidenceTest {
    private suspend fun fixture(block: suspend (AndroidWorkspaces,String)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val service=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try { block(service,service.create("Write evidence").id) } finally { service.close();scope.cancel() }
    }
    @Test fun capturesCreateReplaceAndUnchangedContentThenKeepsImmutableObservations()=runBlocking<Unit> {
        fixture { ws,id ->
            val created=ws.writeObserved(id,"src/main.sh","exit 7\n")
            assertEquals(FileObservationKind.ABSENT,created.before.kind)
            assertEquals("exit 7\n",created.after.excerpt);assertEquals(true,created.changed);assertTrue(created.verified)
            val fixed=ws.writeObserved(id,"src/main.sh","exit 0\n")
            assertEquals(created.after,fixed.before);assertEquals(true,fixed.changed)
            assertEquals("exit 0\n",fixed.after.excerpt);assertNotEquals(fixed.before.sha256,fixed.after.sha256)
            val repeated=ws.writeObserved(id,"src/../src/main.sh","exit 0\n")
            assertEquals("src/main.sh",repeated.path);assertEquals(false,repeated.changed)
            assertEquals("exit 7\n",created.after.excerpt)
            assertEquals("exit 0\n",ws.read(id,"src/main.sh"))
        }
    }
    @Test fun boundedBinaryAndOversizedBeforeAreNeverMisreportedAsAbsentOrUnchanged()=runBlocking<Unit> {
        fixture { ws,id ->
            val base=File(ws.location(id));File(base,"binary").writeBytes(byteArrayOf(65,0,66))
            val binary=ws.writeObserved(id,"binary","plain")
            assertEquals(FileObservationKind.BINARY,binary.before.kind);assertNull(binary.before.excerpt)
            assertNotNull(binary.before.sha256);assertEquals(true,binary.changed)
            File(base,"large").writeBytes(ByteArray(FileObservations.CAP+1) { 65 })
            val large=ws.writeObserved(id,"large","now small")
            assertEquals(FileObservationKind.OVERSIZED,large.before.kind)
            assertNull(large.before.sha256);assertNull(large.changed);assertTrue(large.verified)
            val utf8=ws.writeObserved(id,"utf8","中".repeat(3000))
            val excerpt=requireNotNull(utf8.after.excerpt)
            assertTrue(utf8.after.excerptTruncated);assertTrue(excerpt.toByteArray().size<=6144)
            assertFalse(excerpt.contains('\uFFFD'))
        }
    }
    @Test fun failedCaptureAndEscapingPathNeverModifyTarget()=runBlocking<Unit> {
        fixture { ws,id ->
            val base=File(ws.location(id));File(base,"folder").mkdirs()
            assertFailsWith<IllegalArgumentException> { ws.writeObserved(id,"folder","not a directory") }
            assertTrue(File(base,"folder").isDirectory)
            assertFailsWith<IllegalArgumentException> { ws.writeObserved(id,"../../outside","bad") }
            ws.write(id,"kept","original")
            assertFailsWith<IllegalArgumentException> { ws.writeObserved(id,"kept","x".repeat(FileObservations.CAP+1)) }
            assertEquals("original",ws.read(id,"kept"))
        }
    }
}
