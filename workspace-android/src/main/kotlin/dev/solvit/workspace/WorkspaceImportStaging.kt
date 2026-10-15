package dev.solvit.workspace

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Host journal kept outside the workspace. A reserved-looking filename is not ownership.
 * Records are synced before copying bytes; identity changes never authorize deletion.
 */
internal class WorkspaceImportStaging(private val root:File,private val ledger:File) :
    AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<WorkspaceImportStaging>
    private fun identity(file:File):String?=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey()?.toString()
    private fun directory() {
        check(ledger.mkdirs()||ledger.isDirectory)
        check(ledger.canonicalFile==ledger.absoluteFile && Files.isDirectory(ledger.toPath(),NOFOLLOW_LINKS)) { "导入暂存记录目录无效" }
    }
    fun register(staged:File):File {
        directory()
        check(root.canonicalFile==root.absoluteFile && staged.canonicalFile==staged.absoluteFile)
        val relative=staged.relativeTo(root).invariantSeparatorsPath
        val key=checkNotNull(identity(staged)) { "无法记录导入暂存文件身份" }
        val record=File(ledger,"${UUID.randomUUID()}.record")
        check(record.createNewFile())
        try {
            val encode=Base64.getEncoder()
            val data=encode.encodeToString(relative.toByteArray(Charsets.UTF_8))+"\n"+
                encode.encodeToString(key.toByteArray(Charsets.UTF_8))+"\n"
            FileOutputStream(record).use { it.write(data.toByteArray(Charsets.UTF_8));it.fd.sync() }
        }catch(error:Throwable){record.delete();throw error}
        return record
    }
    fun forget(record:File){Files.deleteIfExists(record.toPath())}
    /** Called under the process-wide staging lock; active files from all service instances survive. */
    fun cleanup(active:Set<String>):Int {
        if(!Files.exists(ledger.toPath(),NOFOLLOW_LINKS))return 0
        directory()
        if(root.canonicalFile!=root.absoluteFile || !Files.isDirectory(root.toPath(),NOFOLLOW_LINKS))return 0
        var removed=0;var visited=0
        Files.newDirectoryStream(ledger.toPath()).use { records ->
            for(path in records) {
                if(++visited>20_000)break
                if(!path.fileName.toString().matches(Regex("[a-f0-9-]{36}\\.record")) ||
                    !Files.isRegularFile(path,NOFOLLOW_LINKS) || Files.size(path)>8192)continue
                runCatching {
                    val lines=Files.readAllLines(path,Charsets.UTF_8)
                    if(lines.size!=2)return@runCatching
                    val relative=String(Base64.getDecoder().decode(lines[0]),Charsets.UTF_8)
                    val key=String(Base64.getDecoder().decode(lines[1]),Charsets.UTF_8)
                    val parts=relative.split('/')
                    if(relative.length !in 1..1024 || File(relative).isAbsolute ||
                        parts.any { it.isEmpty()||it in setOf(".","..",".git")||it.any(Char::isISOControl) } ||
                        !parts.last().matches(Regex("\\.solvit-import-[a-zA-Z0-9-]+\\.partial")))return@runCatching
                    val staged=File(root,relative)
                    if(staged.absolutePath in active)return@runCatching
                    // Canonical comparison prevents following links in any path component.
                    if(staged.absoluteFile!=staged.canonicalFile)return@runCatching
                    if(Files.exists(staged.toPath(),NOFOLLOW_LINKS)) {
                        if(!Files.isRegularFile(staged.toPath(),NOFOLLOW_LINKS) || identity(staged)!=key)return@runCatching
                        Files.delete(staged.toPath());removed++
                    }
                    Files.deleteIfExists(path)
                }
            }
        }
        return removed
    }
}
