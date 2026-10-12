package dev.solvit.workspace

import dev.solvit.core.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files

/** Same platform constraints as file tools. Do not follow links or infer absence from omitted directories. */
internal object WorkspaceSnapshots {
    suspend fun capture(workspaceId: String, root: File): WorkspaceSnapshot {
        val base=root.canonicalFile
        val files=linkedMapOf<String,FileObservation>()
        var complete=true
        var visited=0
        var remaining=2*1024*1024
        suspend fun walk(folder: File,depth: Int) {
            currentCoroutineContext().ensureActive()
            if(depth>12 || visited>=512 || files.size>=64) { complete=false;return }
            val children=folder.listFiles()
            if(children==null || children.size>512) { complete=false;return }
            for(child in children.sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                if(++visited>512 || files.size>=64) { complete=false;return }
                val path=child.relativeTo(base).invariantSeparatorsPath
                if(path.length>1024) { complete=false;continue }
                if(Files.isSymbolicLink(child.toPath())) {
                    files[path]=FileObservation(FileObservationKind.UNAVAILABLE);complete=false;continue
                }
                val canonical=runCatching { child.canonicalFile }.getOrNull()
                if(canonical==null || !canonical.path.startsWith(base.path+File.separator) || canonical!=child.absoluteFile) {
                    files[path]=FileObservation(FileObservationKind.UNAVAILABLE);complete=false;continue
                }
                if(child.isDirectory) { walk(child,depth+1);continue }
                if(!child.isFile) { files[path]=FileObservation(FileObservationKind.UNAVAILABLE);complete=false;continue }
                if(remaining<=0) { files[path]=FileObservation(FileObservationKind.UNAVAILABLE);complete=false;continue }
                val cap=minOf(FileObservations.CAP,remaining)
                val beforeSize=child.length();val beforeModified=child.lastModified()
                val observation=runCatching { FileObservations.capture(child,cap,512) }.getOrElse {
                    FileObservation(FileObservationKind.UNAVAILABLE)
                }
                remaining-=observation.bytes?.toInt() ?: cap
                if(Files.isSymbolicLink(child.toPath()) || runCatching { child.canonicalFile }.getOrNull()!=canonical || child.length()!=beforeSize || child.lastModified()!=beforeModified || observation.kind==FileObservationKind.ABSENT) {
                    files[path]=FileObservation(FileObservationKind.UNAVAILABLE);complete=false
                } else {
                    files[path]=observation
                    if(observation.kind in setOf(FileObservationKind.UNAVAILABLE,FileObservationKind.OVERSIZED)) complete=false
                }
            }
        }
        walk(base,0)
        return WorkspaceSnapshot(workspaceId,files.toMap(),complete,System.currentTimeMillis()).also(WorkspaceSnapshot::validate)
    }
}
