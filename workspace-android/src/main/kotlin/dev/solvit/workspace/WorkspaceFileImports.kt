package dev.solvit.workspace

import dev.solvit.core.MAX_WORKSPACE_IMPORT_BYTES
import dev.solvit.core.WorkspaceImportTarget
import dev.solvit.core.WorkspaceImportMonitor
import dev.solvit.core.WorkspaceImportProgress
import kotlinx.coroutines.yield
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Complete-file staging with platform-specific atomic, create-only publication. */
internal object WorkspaceFileImports {
    private val stagingLock=Any()
    private val activeStaging=mutableSetOf<String>()
    private suspend fun progress(stage:String,completed:Long,total:Long) {
        yield()
        currentCoroutineContext()[WorkspaceImportMonitor]?.update?.invoke(WorkspaceImportProgress(stage,completed,total))
        currentCoroutineContext().ensureActive()
    }
    /** No journal means no authority to delete, even if a filename resembles staging. */
    fun cleanup(root:File,journal:WorkspaceImportStaging?=null):Int=synchronized(stagingLock) {
        journal?.cleanup(activeStaging)?:0
    }
    private fun target(root: File, path: String): File {
        require(path.length in 1..1024 && '\u0000' !in path && !File(path).isAbsolute) { "导入路径无效" }
        val pieces=path.split('/')
        require(pieces.all { it.isNotEmpty() && it !in setOf(".","..") && it.none(Char::isISOControl) }) { "导入路径无效" }
        require(pieces.none { it == ".git" } && !pieces.last().startsWith(".solvit-import-")) { "不能导入到内部文件路径" }
        check(root.canonicalFile==root.absoluteFile && Files.isDirectory(root.toPath(),NOFOLLOW_LINKS)) { "工作区目录已改变" }
        var parent=root
        pieces.dropLast(1).forEach { part ->
            parent=File(parent,part)
            check(Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS) && parent.canonicalFile==parent.absoluteFile) { "请先创建目标目录，且不能使用符号链接" }
        }
        return File(parent,pieces.last())
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun fileDigest(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        Files.newByteChannel(file.toPath(),setOf(StandardOpenOption.READ,NOFOLLOW_LINKS)).use { channel ->
            val buffer=ByteBuffer.allocate(64*1024);var total=0L
            while(true) {
                currentCoroutineContext().ensureActive()
                val count=channel.read(buffer);if(count<0) break
                total+=count;check(total<=MAX_WORKSPACE_IMPORT_BYTES) { "导入文件已改变" }
                buffer.flip();digest.update(buffer);buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    suspend fun review(root: File, path: String, authorize: suspend () -> Unit): WorkspaceImportTarget? {
        authorize();currentCoroutineContext().ensureActive()
        val destination=target(root,path)
        if(!Files.exists(destination.toPath(),NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(destination.toPath(),NOFOLLOW_LINKS)) { "目标不是普通文件，不能替换" }
        val bytes=destination.length()
        check(bytes<=MAX_WORKSPACE_IMPORT_BYTES) { "仅支持审阅不超过20MiB的目标文件" }
        val digest=fileDigest(destination)
        authorize();currentCoroutineContext().ensureActive()
        check(target(root,path)==destination && Files.isRegularFile(destination.toPath(),NOFOLLOW_LINKS) && destination.length()==bytes) {
            "目标文件已变化，请重新审阅"
        }
        return WorkspaceImportTarget(bytes,digest)
    }

    /** Claim the old name without replacement, verify the claimed bytes, then create the new name.
     * Keeping the old inode also preserves writes through an already-open Shell descriptor.
     * External writers are not locked: a concurrent name wins and the backup remains recoverable.
     */
    suspend fun replace(root: File, path: String, bytes: ByteArray, sourceSha256: String,
        targetSha256: String, publisher: WorkspaceImportPublisher, authorize: suspend () -> Unit): Triple<Long,String,String> {
        require(bytes.size<=MAX_WORKSPACE_IMPORT_BYTES && sourceSha256.matches(Regex("[a-f0-9]{64}")) &&
            targetSha256.matches(Regex("[a-f0-9]{64}"))) { "导入文件超过20MiB或版本无效" }
        require(sha(bytes)==sourceSha256) { "导入文件内容校验失败" }
        check(review(root,path,authorize)?.sha256==targetSha256) { "目标文件已变化，请重新审阅" }
        val destination=target(root,path)
        val backup=File(destination.parentFile,"solvit-backup-${java.util.UUID.randomUUID()}")
        authorize();currentCoroutineContext().ensureActive()
        check(target(root,path)==destination) { "导入目标目录已改变" }
        publisher.publish(destination,backup)
        try {
            check(Files.isRegularFile(backup.toPath(),NOFOLLOW_LINKS) && fileDigest(backup)==targetSha256) {
                "目标文件已变化，请重新审阅"
            }
            val result=create(root,path,bytes,sourceSha256,publisher,authorize)
            return Triple(result.first,result.second,backup.relativeTo(root).invariantSeparatorsPath)
        } catch(failure:Throwable) {
            // Recovery must run even after cancellation/revocation. Never replace a concurrent file.
            withContext(NonCancellable) {
                try {
                    check(target(root,path)==destination) { "目标目录已改变，保留恢复文件" }
                    publisher.publish(backup,destination)
                } catch(recovery:Throwable) { failure.addSuppressed(recovery) }
            }
            if(Files.exists(backup.toPath(),NOFOLLOW_LINKS)) {
                throw IllegalStateException("${failure.message}；原文件保留在 ${backup.relativeTo(root).invariantSeparatorsPath}",failure)
            }
            throw failure
        }
    }
    suspend fun create(root: File, path: String, bytes: ByteArray, expected: String, publisher: WorkspaceImportPublisher,
        authorize: suspend () -> Unit): Pair<Long,String> {
        require(bytes.size <= MAX_WORKSPACE_IMPORT_BYTES && expected.matches(Regex("[a-f0-9]{64}"))) { "导入文件超过20MiB或版本无效" }
        progress("PREPARING",0,bytes.size.toLong())
        val content=bytes.copyOf()
        try {
            require(sha(content)==expected) { "导入文件内容校验失败" }
            authorize();currentCoroutineContext().ensureActive()
            val destination=target(root,path)
            check(!Files.exists(destination.toPath(),NOFOLLOW_LINKS)) { "目标文件已存在，请选择其他名称" }
            val staged=synchronized(stagingLock) {
                File.createTempFile(".solvit-import-",".partial",destination.parentFile).also { activeStaging.add(it.absolutePath) }
            }
            var record:File?=null
            val journal=currentCoroutineContext()[WorkspaceImportStaging]
            try {
                synchronized(stagingLock) { record=journal?.register(staged) }
                FileOutputStream(staged).use { output ->
                    var position=0
                    while(position<content.size) {
                        currentCoroutineContext().ensureActive()
                        val count=minOf(64*1024,content.size-position)
                        output.write(content,position,count);position+=count
                        progress("WRITING",position.toLong(),content.size.toLong())
                    }
                    progress("SYNCING",content.size.toLong(),content.size.toLong())
                    output.fd.sync()
                }
                authorize();currentCoroutineContext().ensureActive()
                check(target(root,path)==destination && staged.canonicalFile==staged.absoluteFile &&
                    Files.isRegularFile(staged.toPath(),NOFOLLOW_LINKS)) { "导入目标目录已改变" }
                progress("VERIFYING",content.size.toLong(),content.size.toLong())
                check(fileDigest(staged)==expected) { "导入暂存文件已改变" }
                // No replace option: do not turn a create conflict into an overwrite.
                progress("PUBLISHING",content.size.toLong(),content.size.toLong())
                publisher.publish(staged,destination)
                withContext(NonCancellable) {
                check(target(root,path)==destination && Files.isRegularFile(destination.toPath(),NOFOLLOW_LINKS) && destination.length()==content.size.toLong() && fileDigest(destination)==expected) { "文件导入结果已改变，请刷新工作区" }
                // A cancellation arriving after publication cannot turn a complete file into a partial one.
                // Monitoring failures must not change the already committed outcome.
                runCatching { currentCoroutineContext()[WorkspaceImportMonitor]?.update?.invoke(
                    WorkspaceImportProgress("COMMITTED",content.size.toLong(),content.size.toLong())) }
                }
                return content.size.toLong() to expected
            } finally {
                synchronized(stagingLock) {
                    try {
                        Files.deleteIfExists(staged.toPath())
                        record?.let { runCatching { journal?.forget(it) } }
                    } finally { activeStaging.remove(staged.absolutePath) }
                }
            }
        } finally { content.fill(0) }
    }
}
