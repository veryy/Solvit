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
            WorkspaceImportCapacity.admit(root,payload,{need-1}){calls++}
        }
        assertTrue(denied.message!!.contains("尚未开始导入"));assertEquals(0,calls)
        WorkspaceImportCapacity.admit(root,payload,{need}){calls++}
        assertEquals(1,calls)
        assertFails { WorkspaceImportCapacity.admit(root,0,{-1}){calls++} }
        assertFails { WorkspaceImportCapacity.admit(root,0,{error("unavailable")}){calls++} }
        assertEquals(1,calls)
    }
    @Test fun separateWorkspaceImportsShareReservationAndCancellationReleasesIt()=fixture { root ->
        coroutineScope {
            val other=File(root,"other").apply { mkdir() }
            val payload=200_000;val need=payload+WorkspaceImportCapacity.HEADROOM_BYTES
            val started=CompletableDeferred<Unit>()
            val first=launch { WorkspaceImportCapacity.admit(root,payload,{need}) {
                started.complete(Unit);awaitCancellation()
            } }
            started.await()
            try {
                assertFails { WorkspaceImportCapacity.admit(other,payload,{need}){error("overbooked")} }
            }finally{first.cancelAndJoin()}
            assertEquals("after cancellation",WorkspaceImportCapacity.admit(other,payload,{need}){"after cancellation"})
        }
    }
    @Test fun failureDoesNotLeakReservationAndNeverInventsSpaceFromRetainedOriginal()=fixture { root ->
        val original=File(root,"existing").apply { writeBytes(ByteArray(500_000)) }
        val payload=32;val need=payload+WorkspaceImportCapacity.HEADROOM_BYTES
        assertFails { WorkspaceImportCapacity.admit(root,payload,{need}){error("write failed")} }
        assertEquals(42,WorkspaceImportCapacity.admit(root,payload,{need}){42})
        assertFails { WorkspaceImportCapacity.admit(root,payload,{need-1}){original.delete()} }
        assertEquals(500_000L,original.length())
    }
}
