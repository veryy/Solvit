package dev.solvit.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Trusted host adapter for private cleanup claims and non-overwriting compensation.
 * No URI, absolute path, mover or quarantine location is exposed to scripts. */
internal object AndroidWorkspaceBackupMover:WorkspaceImportPublisher {
    init { System.loadLibrary("solvit_workspace") }
    private external fun transferNative(sourceDirectory:String,destinationDirectory:String,source:String,destination:String):Int
    override fun publish(source:File,destination:File) {
        require(source.parentFile?.canonicalFile==source.parentFile?.absoluteFile &&
            destination.parentFile?.canonicalFile==destination.parentFile?.absoluteFile)
        val error=transferNative(checkNotNull(source.parent),checkNotNull(destination.parent),source.name,destination.name)
        if(error==17)throw FileAlreadyExistsException(destination.name,null,"同名文件已存在，原文件与暂存文件均保留")
        if(error!=0)throw IOException("备份认领或恢复失败（errno=$error），文件系统须支持同卷不覆盖的原子移动")
    }
}
