package dev.solvit.workspace

import dev.solvit.core.MAX_WORKSPACE_IMPORT_BYTES
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Create-only publication: a hard link fails atomically if a concurrent Shell creates the name. */
internal object WorkspaceFileImports {
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
    suspend fun create(root: File, path: String, bytes: ByteArray, expected: String,
        authorize: suspend () -> Unit): Pair<Long,String> {
        require(bytes.size <= MAX_WORKSPACE_IMPORT_BYTES && expected.matches(Regex("[a-f0-9]{64}"))) { "导入文件超过20MiB或版本无效" }
        val content=bytes.copyOf()
        try {
            require(sha(content)==expected) { "导入文件内容校验失败" }
            authorize();currentCoroutineContext().ensureActive()
            val destination=target(root,path)
            check(!Files.exists(destination.toPath(),NOFOLLOW_LINKS)) { "目标文件已存在，请选择其他名称" }
            val staged=File.createTempFile(".solvit-import-",".partial",destination.parentFile)
            try {
                FileOutputStream(staged).use { output ->
                    var position=0
                    while(position<content.size) {
                        currentCoroutineContext().ensureActive()
                        val count=minOf(64*1024,content.size-position)
                        output.write(content,position,count);position+=count
                    }
                    output.fd.sync()
                }
                authorize();currentCoroutineContext().ensureActive()
                check(target(root,path)==destination && staged.canonicalFile==staged.absoluteFile &&
                    Files.isRegularFile(staged.toPath(),NOFOLLOW_LINKS)) { "导入目标目录已改变" }
                check(fileDigest(staged)==expected) { "导入暂存文件已改变" }
                // No replace option: do not turn a create conflict into an overwrite.
                Files.createLink(destination.toPath(),staged.toPath())
                check(target(root,path)==destination && Files.isSameFile(destination.toPath(),staged.toPath()) && fileDigest(destination)==expected) { "文件导入结果已改变，请刷新工作区" }
                return content.size.toLong() to expected
            } finally { Files.deleteIfExists(staged.toPath()) }
        } finally { content.fill(0) }
    }
}
