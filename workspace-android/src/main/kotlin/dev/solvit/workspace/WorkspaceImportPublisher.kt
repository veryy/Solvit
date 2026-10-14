package dev.solvit.workspace

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Trusted platform adapter; page scripts cannot provide a publication strategy. */
fun interface WorkspaceImportPublisher {
    fun publish(source: File, destination: File)
}
internal object AndroidWorkspaceImportPublisher : WorkspaceImportPublisher {
    init { System.loadLibrary("solvit_workspace") }
    private external fun publishNative(directory:String,source:String,destination:String):Int
    override fun publish(source:File,destination:File) {
        require(source.parentFile==destination.parentFile)
        val error=publishNative(checkNotNull(source.parent),source.name,destination.name)
        if(error==17) throw FileAlreadyExistsException(destination.name,null,"目标文件已存在，请选择其他名称")
        if(error!=0) throw IOException("工作区文件发布失败（errno=$error）；目标文件系统需支持不覆盖的原子重命名")
    }
}
