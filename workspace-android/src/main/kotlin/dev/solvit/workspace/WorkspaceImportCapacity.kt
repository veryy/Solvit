package dev.solvit.workspace

import dev.solvit.core.MAX_WORKSPACE_IMPORT_BYTES
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Admission check, not an OS allocation or a filesystem quota. Other apps and Shell can
 * consume space afterwards. Keep the original untouched when rejection is already known. */
internal object WorkspaceImportCapacity {
    // Leave room for transaction/staging metadata and app bookkeeping. No backup is deleted
    // to satisfy this margin. Old bytes stay on disk and are not counted as reclaimed space.
    const val HEADROOM_BYTES=4L*1024*1024
    private val lock=Any()
    private val outstanding=mutableMapOf<String,Long>()

    suspend fun <T> admit(root:File,contentBytes:Int,availableBytes:(File)->Long,volume:String,
        block:suspend()->T):T {
        require(contentBytes in 0..MAX_WORKSPACE_IMPORT_BYTES) { "导入文件超过20MiB" }
        currentCoroutineContext().ensureActive()
        // Android supplies stat(2)'s device identity. Do not inspect mount tables here:
        // Files.getFileStore is blocked by Android even for our own private directory.
        require(volume.isNotBlank()) { "无法识别工作区存储卷；尚未开始导入" }
        val required=contentBytes.toLong()+HEADROOM_BYTES
        synchronized(lock) {
            val reserved=outstanding[volume]?:0L
            val available=try { availableBytes(root) } catch(failure:Exception) {
                throw IllegalStateException("无法读取工作区剩余存储空间；尚未开始导入",failure)
            }
            check(available>=0) { "无法读取工作区剩余存储空间；尚未开始导入" }
            check(required<=available && reserved<=available-required) {
                "工作区存储空间不足：可用 $available B，本次需 $required B（含4MiB余量），其他导入预留 $reserved B；尚未开始导入，请先释放空间"
            }
            outstanding[volume]=Math.addExact(reserved,required)
        }
        try {
            currentCoroutineContext().ensureActive()
            return block()
        }finally {
            synchronized(lock) {
                val remaining=checkNotNull(outstanding[volume])-required
                check(remaining>=0)
                if(remaining==0L)outstanding.remove(volume) else outstanding[volume]=remaining
            }
        }
    }
}
