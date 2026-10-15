package dev.solvit.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Trusted Kotlin host primitive. Never exposed as script paths or a general page action.
 * Atomically publishes a directory without replacing any concurrent destination. */
object AndroidWorkspaceDirectoryPublisher {
    init { System.loadLibrary("solvit_workspace") }
    private external fun publishNative(sourceParent: String, destinationParent: String,
        source: String, destination: String): Int
    fun publish(source: File, destination: File) {
        require(source.parentFile?.canonicalFile == source.parentFile?.absoluteFile &&
            destination.parentFile?.canonicalFile == destination.parentFile?.absoluteFile)
        val error = publishNative(checkNotNull(source.parent), checkNotNull(destination.parent), source.name, destination.name)
        if (error == 17) throw FileAlreadyExistsException(destination.name, null, "同名目录已存在，工作区与暂存均保留")
        if (error != 0) throw IOException("Git目录发布失败（errno=$error），需要同卷不覆盖的原子移动")
    }
}
