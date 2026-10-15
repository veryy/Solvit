package dev.solvit.workspace

import dev.solvit.core.MAX_WORKSPACE_IMPORT_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Explicit host cleanup protocol. No constructor/startup action replays a deletion.
 * The mover MUST atomically refuse an existing destination. Claims live outside Shell's
 * workspace, so an ordinary concurrent rename cannot replace the file we finally unlink.
 * Existing open descriptors/hardlinks are not locked; hashes are bounded observations. */
internal class WorkspaceBackupCleanup(private val root:File,private val ledger:File,
    private val mover:WorkspaceImportPublisher) {
    internal data class Intent(val record:WorkspaceImportBackups.Record,val sha256:String,val phase:String,val operationId:String)
    internal data class Observation(val intent:Intent,val status:String,val heldBytes:Long?)
    internal data class Inventory(val observations:List<Observation>,val incomplete:Boolean,val unreadableEntries:Int)
    companion object { private val lock=Mutex();private const val HEADER="SOLVIT_BACKUP_CLEANUP_1" }
    private val catalog=WorkspaceImportBackups(root,ledger)
    private val directory=File(ledger,"backup-cleanup")
    private fun attrs(file:File):BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
    }catch(_:java.nio.file.NoSuchFileException){null}
    private fun key(file:File)=attrs(file)?.takeIf { it.isRegularFile }?.fileKey()?.toString()
    private fun validateId(id:String){ require(runCatching { UUID.fromString(id).toString()==id }.getOrDefault(false)) }
    private fun files(id:String):Pair<File,File> {
        validateId(id)
        check(directory.canonicalFile==directory.absoluteFile && Files.isDirectory(directory.toPath(),NOFOLLOW_LINKS)) { "备份清理目录无效" }
        return File(directory,"$id.intent") to File(directory,"$id.held")
    }
    private fun createDirectory() {
        check(ledger.canonicalFile==ledger.absoluteFile && Files.isDirectory(ledger.toPath(),NOFOLLOW_LINKS))
        check(directory.mkdir() || Files.isDirectory(directory.toPath(),NOFOLLOW_LINKS))
        check(directory.canonicalFile==directory.absoluteFile)
    }
    private fun enc(s:String)=Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun dec(s:String)=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Base64.getDecoder().decode(s))).toString()
    private fun persist(intent:Intent) {
        val (file,_)=files(intent.record.id);val r=intent.record
        validateId(intent.operationId)
        require(intent.phase in setOf("PREPARED","CLAIMED","DELETING","DELETED"))
        require(intent.sha256.matches(Regex("[a-f0-9]{64}")))
        val temp=File(directory,"${UUID.randomUUID()}.tmp")
        check(temp.createNewFile())
        try {
            FileOutputStream(temp).use { out ->
                out.write(listOf(HEADER,enc(r.path),enc(r.backupPath),enc(r.originalKey),r.originalBytes.toString(),
                    r.originalSha256,r.createdAt.toString(),intent.sha256,intent.operationId,intent.phase).joinToString("\n",postfix="\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(temp.toPath(),file.toPath(),ATOMIC_MOVE)
        }finally { Files.deleteIfExists(temp.toPath()) }
    }
    private fun read(id:String):Intent {
        val (file,_)=files(id)
        check(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS))
        val text=Files.newByteChannel(file.toPath(),setOf(READ,NOFOLLOW_LINKS)).use { channel ->
            val buffer=ByteBuffer.allocate(16_385)
            while(buffer.hasRemaining() && channel.read(buffer)!=-1) { /* bounded private metadata */ }
            require(buffer.position()<=16_384);buffer.flip();Charsets.UTF_8.newDecoder().decode(buffer).toString()
        }
        require(text.endsWith('\n'));val lines=text.removeSuffix("\n").split('\n')
        require(lines.size==10 && lines[0]==HEADER)
        val r=WorkspaceImportBackups.Record(id,dec(lines[1]),dec(lines[2]),dec(lines[3]),lines[4].toLong(),lines[5],lines[6].toLong())
        require(lines[7].matches(Regex("[a-f0-9]{64}")) && lines[9] in setOf("PREPARED","CLAIMED","DELETING","DELETED"))
        catalog.validateCleanupRecord(r)
        // Only independently registered provenance can authorize a file mutation.
        // A completed deletion may already have retired its provenance before a kill.
        val registered=catalog.registeredOrNull(id)
        check(registered==r || registered==null && lines[9]=="DELETED" && !Files.exists(files(id).second.toPath(),NOFOLLOW_LINKS)) { "备份来源登记与清理意图不一致" }
        validateId(lines[8])
        return Intent(r,lines[7],lines[9],lines[8])
    }
    private suspend fun hash(file:File,expectedKey:String,checkpoint:suspend ()->Unit):String {
        val before=checkNotNull(attrs(file)) { "待清理文件已不存在" }
        check(before.isRegularFile && before.fileKey()?.toString()==expectedKey) { "认领文件身份已变化，保留待检查" }
        require(before.size() in 0..MAX_WORKSPACE_IMPORT_BYTES.toLong()) { "备份超过20MiB，不能直接清理" }
        val digest=MessageDigest.getInstance("SHA-256");val bytes=ByteArray(64*1024);var total=0L
        try {
            Files.newByteChannel(file.toPath(),setOf(READ,NOFOLLOW_LINKS)).use { channel ->
                while(true) {
                    checkpoint()
                    val count=channel.read(ByteBuffer.wrap(bytes));if(count<0)break
                    total+=count;require(total<=MAX_WORKSPACE_IMPORT_BYTES) { "备份内容增长，请重新审阅" }
                    digest.update(bytes,0,count)
                }
            }
            checkpoint();val after=attrs(file)
            check(after?.isRegularFile==true && after.fileKey()?.toString()==expectedKey && after.size()==total && before.size()==total) { "备份内容或身份已变化" }
            return digest.digest().joinToString(""){"%02x".format(it)}
        }finally { bytes.fill(0) }
    }
    /** Persists intent before claiming. On ANY failure, inspection/restoration is still
     * available; no best-effort catch block unlinks an unverified workspace filename. */
    suspend fun prepare(id:String,sha256:String,operationId:String=UUID.randomUUID().toString(),checkpoint:suspend ()->Unit={}):Observation = lock.withLock {
        validateId(operationId);require(sha256.matches(Regex("[a-f0-9]{64}")));checkpoint()
        val coroutine=currentCoroutineContext()
        val (record,bytes)=catalog.readOwned(id) { coroutine.ensureActive() }
        try { check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}==sha256) { "备份内容已变化，请重新审阅" } }
        finally { bytes.fill(0) }
        checkpoint();createDirectory();val (intentFile,held)=files(id)
        check(!Files.exists(intentFile.toPath(),NOFOLLOW_LINKS) && !Files.exists(held.toPath(),NOFOLLOW_LINKS)) { "已有未处理的清理操作" }
        val intent=Intent(record,sha256,"PREPARED",operationId);persist(intent)
        checkpoint();mover.publish(catalog.location(record),held)
        checkpoint();check(hash(held,record.originalKey,checkpoint)==sha256) { "认领的备份内容已变化，文件保留待检查" }
        persist(intent.copy(phase="CLAIMED"));inspectLocked(id)
    }
    /** Call ONLY after explicit current approval. Process restart must inspect, not call
     * this method automatically. A missing held file is never treated as deletion proof. */
    suspend fun deleteReviewed(id:String,sha256:String,operationId:String?=null,checkpoint:suspend ()->Unit={}) = lock.withLock {
        val intent=read(id);check(operationId==null || intent.operationId==operationId) { "不能清理另一操作认领的文件" };check(intent.phase=="CLAIMED" && intent.sha256==sha256) { "清理确认已失效" }
        val (intentFile,held)=files(id)
        check(hash(held,intent.record.originalKey,checkpoint)==sha256) { "备份内容已变化，尚未删除" }
        checkpoint();check(key(held)==intent.record.originalKey) { "认领文件身份已变化" }
        persist(intent.copy(phase="DELETING"))
        // The fixed private name is never a user-supplied Shell path.
        Files.delete(held.toPath())
        checkpoint() // A kill here leaves an explicit indeterminate intent, not a fake success.
        persist(intent.copy(phase="DELETED"))
        catalog.forgetDeleted(intent.record)
        Files.delete(intentFile.toPath())
    }
    /** Compensation/manual recovery moves back without replacing a concurrent name.
     * Even a mistakenly claimed foreign inode is preserved, never deleted as our backup. */
    suspend fun restore(id:String,operationId:String?=null,checkpoint:suspend ()->Unit={}):Observation? = lock.withLock {
        val intent=read(id);check(operationId==null || intent.operationId==operationId) { "不能补偿其他清理操作" };val (intentFile,held)=files(id);val source=catalog.location(intent.record)
        val heldAttrs=attrs(held)
        if(heldAttrs==null) {
            check(intent.phase!="DELETING" && intent.phase!="DELETED" && key(source)==intent.record.originalKey) { "认领文件已不存在，无法确认清理结果" }
            checkpoint();Files.delete(intentFile.toPath());return@withLock null
        }
        check(heldAttrs.isRegularFile) { "认领文件类型变化，保留待检查" }
        val heldKey=checkNotNull(heldAttrs.fileKey()?.toString())
        checkpoint();mover.publish(held,source)
        check(key(source)==heldKey && !Files.exists(held.toPath(),NOFOLLOW_LINKS)) { "恢复后文件身份变化，请检查" }
        Files.delete(intentFile.toPath());null
    }
    suspend fun dismiss(id:String,checkpoint:suspend ()->Unit={}) = lock.withLock {
        val observed=inspectLocked(id);val (intentFile,held)=files(id)
        check(observed.status in setOf("DELETED","UNCONFIRMED") && !Files.exists(held.toPath(),NOFOLLOW_LINKS)) { "认领文件仍存在，不能关闭清理记录" }
        checkpoint()
        if(observed.intent.phase=="DELETED")catalog.forgetDeleted(observed.intent.record)
        Files.delete(intentFile.toPath())
    }
    private fun inspectLocked(id:String):Observation {
        val intent=read(id);val held=attrs(files(id).second)
        val source=try { attrs(catalog.location(intent.record)) }catch(_:Exception) { return Observation(intent,"UNAVAILABLE",held?.takeIf { it.isRegularFile }?.size()) }
        val status=when {
            held!=null && (!held.isRegularFile || held.fileKey()?.toString()!=intent.record.originalKey) -> "HELD_CHANGED"
            held!=null && source!=null -> "SOURCE_AND_HELD"
            held!=null -> "HELD"
            intent.phase=="DELETED" -> "DELETED"
            source?.isRegularFile==true && source.fileKey()?.toString()==intent.record.originalKey && intent.phase!="DELETING" -> "SOURCE_AVAILABLE"
            else -> "UNCONFIRMED"
        }
        return Observation(intent,status,held?.takeIf { it.isRegularFile }?.size())
    }
    suspend fun inventory(checkpoint:suspend ()->Unit={}):Inventory = lock.withLock {
        checkpoint()
        if(!Files.exists(directory.toPath(),NOFOLLOW_LINKS))return@withLock Inventory(emptyList(),false,0)
        check(directory.canonicalFile==directory.absoluteFile && Files.isDirectory(directory.toPath(),NOFOLLOW_LINKS))
        val result=mutableListOf<Observation>();var visited=0;var unreadable=0;var incomplete=false
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for(path in entries) {
                checkpoint();if(++visited>20_000){incomplete=true;break}
                val name=path.fileName.toString()
                if(name.matches(Regex("[a-f0-9-]{36}\\.intent"))) {
                    try { result+=inspectLocked(name.removeSuffix(".intent")) }
                    catch(failure:CancellationException){throw failure} catch(_:Exception){unreadable++}
                }else if(name.endsWith(".held") && !Files.exists(File(directory,name.removeSuffix(".held")+".intent").toPath(),NOFOLLOW_LINKS))unreadable++
            }
        }
        Inventory(result.sortedWith(compareByDescending<Observation> { it.intent.record.createdAt }.thenBy { it.intent.record.id }),incomplete,unreadable)
    }
    suspend fun inspect(id:String):Observation = lock.withLock { inspectLocked(id) }
}
