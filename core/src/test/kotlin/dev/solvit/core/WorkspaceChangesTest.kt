package dev.solvit.core

import kotlin.test.*

class WorkspaceChangesTest {
    private fun file(hash: String)=FileObservation(FileObservationKind.TEXT,1,hash.map { it.code.toString(16) }.joinToString("").padEnd(64,'0'),"text")
    @Test fun reportsCreateModifyDeleteAndLeavesIdenticalBinaryOutOfChanges() {
        val before=WorkspaceSnapshot("workspace",mapOf("modify" to file("old"),"delete" to file("gone"),"binary" to FileObservation(FileObservationKind.BINARY,2,"a".repeat(64))),true,1)
        val after=WorkspaceSnapshot("workspace",mapOf("modify" to file("new"),"create" to file("added"),"binary" to FileObservation(FileObservationKind.BINARY,2,"a".repeat(64))),true,2)
        val diff=compareWorkspaceSnapshots(before,after)
        assertTrue(diff.complete)
        assertEquals(mapOf("modify" to WorkspaceChangeKind.MODIFIED,"delete" to WorkspaceChangeKind.DELETED,"create" to WorkspaceChangeKind.CREATED),diff.changes.associate { it.path to it.kind })
    }
    @Test fun incompleteScansNeverInventAbsentFilesOrClaimUnreadContentMatches() {
        val before=WorkspaceSnapshot("workspace",mapOf("known" to file("old"),"large" to FileObservation(FileObservationKind.OVERSIZED)),false,1)
        val after=WorkspaceSnapshot("workspace",mapOf("new" to file("new"),"large" to FileObservation(FileObservationKind.OVERSIZED)),false,2)
        val diff=compareWorkspaceSnapshots(before,after)
        assertFalse(diff.complete);assertTrue(diff.changes.all { it.kind==WorkspaceChangeKind.UNKNOWN })
        assertEquals(setOf("known","new","large"),diff.changes.map { it.path }.toSet())
        assertFailsWith<IllegalArgumentException> { compareWorkspaceSnapshots(before,after.copy(workspaceId="other")) }
        assertFailsWith<IllegalArgumentException> { compareWorkspaceSnapshots(before.copy(files=mapOf("../outside" to file("hash"))),after) }
    }
}
