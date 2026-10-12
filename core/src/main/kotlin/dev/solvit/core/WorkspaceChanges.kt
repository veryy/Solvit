package dev.solvit.core

/** Bounded observations, not an atomic filesystem snapshot; external Shell processes may write concurrently. */
data class WorkspaceSnapshot(val workspaceId: String, val files: Map<String,FileObservation>,
    val complete: Boolean, val observedAt: Long) {
    fun validate() {
        require(workspaceId.isNotBlank() && workspaceId.length<=128 && observedAt>=0 && files.size<=64)
        require(files.keys.all { path -> path.isNotBlank() && path.length<=1024 && !path.startsWith('/') &&
            '\u0000' !in path && path.split('/').none { it in setOf("",".","..") } })
    }
}
enum class WorkspaceChangeKind { CREATED, MODIFIED, DELETED, UNKNOWN }
data class WorkspaceChange(val path: String, val kind: WorkspaceChangeKind,
    val before: FileObservation, val after: FileObservation)
data class WorkspaceComparison(val workspaceId: String, val complete: Boolean, val changes: List<WorkspaceChange>)

fun compareWorkspaceSnapshots(before: WorkspaceSnapshot, after: WorkspaceSnapshot): WorkspaceComparison {
    before.validate();after.validate();require(before.workspaceId==after.workspaceId)
    val absent=FileObservation(FileObservationKind.ABSENT)
    val unknown=FileObservation(FileObservationKind.UNAVAILABLE)
    val changes=(before.files.keys+after.files.keys).sorted().mapNotNull { path ->
        val old=before.files[path] ?: if(before.complete) absent else unknown
        val new=after.files[path] ?: if(after.complete) absent else unknown
        val kind=when {
            old.kind==FileObservationKind.ABSENT && new.kind!=FileObservationKind.UNAVAILABLE -> WorkspaceChangeKind.CREATED
            new.kind==FileObservationKind.ABSENT && old.kind!=FileObservationKind.UNAVAILABLE -> WorkspaceChangeKind.DELETED
            old.sha256!=null && new.sha256!=null -> if(old.sha256==new.sha256) null else WorkspaceChangeKind.MODIFIED
            else -> WorkspaceChangeKind.UNKNOWN
        }
        kind?.let { WorkspaceChange(path,it,old,new) }
    }
    return WorkspaceComparison(before.workspaceId,before.complete && after.complete && changes.none { it.kind==WorkspaceChangeKind.UNKNOWN },changes)
}
