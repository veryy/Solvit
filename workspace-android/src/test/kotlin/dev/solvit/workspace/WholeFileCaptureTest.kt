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
class WholeFileCaptureTest {
    private suspend fun fixture(block: suspend (AndroidWorkspaces,String)->Unit) {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val ws=AndroidWorkspaces(RuntimeEnvironment.getApplication(),scope)
        try { block(ws,ws.create("Immutable capture").id) } finally { ws.close();scope.cancel() }
    }
    @Test fun capturesFullUnicodePastExcerptAndKeepsBothSidesAfterLaterWrites()=runBlocking<Unit> {
        fixture { ws,id ->
            val first="第一版🙂\n".repeat(4000)+"OLD_END"
            val second=first.replace("OLD_END","NEW_END")
            ws.write(id,"src/code.txt",first)
            val before=ws.snapshot(id)
            val evidence=ws.writeObserved(id,"src/code.txt",second)
            val after=ws.snapshot(id)
            ws.write(id,"src/code.txt","third version")
            assertEquals(first,before.files.getValue("src/code.txt").captureText)
            assertEquals(second,after.files.getValue("src/code.txt").captureText)
            assertEquals(first,evidence.before.captureText);assertEquals(second,evidence.after.captureText)
            assertEquals(evidence.before.sha256,evidence.before.captureSha256)
            assertEquals(evidence.after.sha256,evidence.after.captureSha256)
            assertTrue(evidence.before.excerptTruncated)
            assertEquals(WorkspaceChangeKind.MODIFIED,compareWorkspaceSnapshots(before,after).changes.single().kind)
        }
    }
    @Test fun capturesInclusiveLimitAndRefusesOversizedBinaryAndLinkedBodies()=runBlocking<Unit> {
        fixture { ws,id ->
            val base=File(ws.location(id))
            File(base,"edge.txt").writeText("x".repeat(FileObservations.CAP))
            File(base,"oversized.txt").writeText("x".repeat(FileObservations.CAP+1))
            File(base,"binary.dat").writeBytes(byteArrayOf(65,0,66))
            File(base,"invalid.dat").writeBytes(byteArrayOf(0xc3.toByte(),0x28))
            val outside=File(RuntimeEnvironment.getApplication().cacheDir,"capture-private").apply { writeText("private") }
            Files.createSymbolicLink(File(base,"linked.txt").toPath(),outside.toPath())
            val snapshot=ws.snapshot(id)
            assertEquals(FileObservations.CAP,snapshot.files.getValue("edge.txt").captureText?.toByteArray()?.size)
            for(path in listOf("oversized.txt","binary.dat","invalid.dat","linked.txt")) {
                assertNull(snapshot.files.getValue(path).captureText);assertNull(snapshot.files.getValue(path).captureSha256)
            }
            assertEquals(FileObservationKind.OVERSIZED,snapshot.files.getValue("oversized.txt").kind)
            assertEquals(FileObservationKind.BINARY,snapshot.files.getValue("invalid.dat").kind)
            assertEquals(FileObservationKind.UNAVAILABLE,snapshot.files.getValue("linked.txt").kind)
        }
    }
}
