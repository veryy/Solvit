package dev.solvit.workspace

import kotlinx.coroutines.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WorkspaceImportCapacityTest {
    private fun fixture(block:suspend(File)->Unit)=runBlocking<Unit> {
        val root=Files.createTempDirectory("import-capacity").toFile()
        try { block(root) }finally{root.deleteRecursively()}
    }
    @Test fun exactAdmissionBoundaryIncludesMarginAndNeverInvokesRejectedOperation()=fixture { root ->
        val payload=128;val need=payload+WorkspaceImportCapacity.HEADROOM_BYTES
        var calls=0
        val denied=assertFailsWith<IllegalStateException> {
            WorkspaceImportCapacity.admit(root,payload,{need-1},"fixture-volume"){calls++}
        }
        assertTrue(denied.message!!.contains("尚未开始导入"));assertEquals(0,calls)
        WorkspaceImportCapacity.admit(root,payload,{need},"fixture-volume"){calls++}
        assertEquals(1,calls)
        assertFails { WorkspaceImportCapacity.admit(root,0,{-1},"fixture-volume"){calls++} }
        assertFails { WorkspaceImportCapacity.admit(root,0,{error("unavailable")},"fixture-volume"){calls++} }
        assertEquals(1,calls)
    }
    @Test fun separateWorkspaceImportsShareReservationAndCancellationReleasesIt()=fixture { root ->
        coroutineScope {
            val other=File(root,"other").apply { mkdir() }
            val payload=200_000;val need=payload+WorkspaceImportCapacity.HEADROOM_BYTES
            val started=CompletableDeferred<Unit>()
            val first=launch { WorkspaceImportCapacity.admit(root,payload,{need},"fixture-volume") {
                started.complete(Unit);awaitCancellation()
            } }
            started.await()
            try {
                assertFails { WorkspaceImportCapacity.admit(other,payload,{need},"fixture-volume"){error("overbooked")} }
            }finally{first.cancelAndJoin()}
            assertEquals("after cancellation",WorkspaceImportCapacity.admit(other,payload,{need},"fixture-volume"){"after cancellation"})
        }
    }
    @Test fun failureDoesNotLeakReservationAndNeverInventsSpaceFromRetainedOriginal()=fixture { root ->
        val original=File(root,"existing").apply { writeBytes(ByteArray(500_000)) }
        val payload=32;val need=payload+WorkspaceImportCapacity.HEADROOM_BYTES
        assertFails { WorkspaceImportCapacity.admit(root,payload,{need},"fixture-volume"){error("write failed")} }
        assertEquals(42,WorkspaceImportCapacity.admit(root,payload,{need},"fixture-volume"){42})
        assertFails { WorkspaceImportCapacity.admit(root,payload,{need-1},"fixture-volume"){original.delete()} }
        assertEquals(500_000L,original.length())
    }
    @Test fun separateVolumesDoNotShareReservation()=fixture { root ->
        val need=WorkspaceImportCapacity.HEADROOM_BYTES
        WorkspaceImportCapacity.admit(root,0,{need},"first-volume") {
            assertEquals(42,WorkspaceImportCapacity.admit(root,0,{need},"second-volume"){42})
        }
    }

}
