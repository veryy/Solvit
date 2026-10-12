package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

/** Keep only bounded, redacted observations; never use this log as a replayable model transcript. */
internal class StoredExecutionJournal(private val store: ChatStore, private val run: ExecutionRun,
    secrets: List<String>, private val clock: () -> Long = System::currentTimeMillis,
    private val workspaces: WorkspaceService? = null) : ExecutionJournal {
    private val secrets=secrets.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
    private val lock=Mutex()
    private var sequence=0
    private var updatedAt=run.updatedAt
    private var finished=false
    private var observed=false
    private var stepWasAuthorized: Int?=null
    override suspend fun beforeTool(step: Int,descriptor: ToolDescriptor) {
        if(workspaces==null || run.workspaceId==null || descriptor.capabilities.none { it in setOf("workspace","shell") }) return
        stepWasAuthorized=step
        if(!observed) {
            append(listOf(ExecutionEvent(step,ExecutionKind.WORKSPACE_BASELINE,workspace=captureWorkspace())))
            observed=true
        }
    }
    override suspend fun afterTool(step: Int,descriptor: ToolDescriptor) {
        if(observed && stepWasAuthorized==step && descriptor.capabilities.any { it in setOf("workspace","shell") })
            append(listOf(ExecutionEvent(step,ExecutionKind.WORKSPACE_OBSERVED,workspace=captureWorkspace())))
    }
    private suspend fun captureWorkspace(): WorkspaceSnapshot {
        val id=requireNotNull(run.workspaceId)
        return try {
            if(workspaces?.bound(run.sessionId)?.id!=id) return WorkspaceSnapshot(id,emptyMap(),false,maxOf(0,clock()))
            val snapshot=requireNotNull(workspaces).snapshot(id)
            if(workspaces.bound(run.sessionId)?.id!=id) WorkspaceSnapshot(id,emptyMap(),false,maxOf(0,clock())) else snapshot
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { WorkspaceSnapshot(id,emptyMap(),false,maxOf(0,clock())) }
    }
    override suspend fun append(events: List<ExecutionEvent>) = lock.withLock {
        if(finished) throw ExecutionJournalException()
        val safe=events.map { event ->
            if(listOfNotNull(event.call?.id,event.callId).any { id -> secrets.any(id::contains) }) throw ExecutionJournalException()
            val arguments=event.call?.let { bounded(it.argumentsJson,16*1024) }
            val result=event.result?.let { bounded(it,64*1024) }
            val snapshot=event.workspace?.let { source ->
                source.validate()
                // Paths themselves can contain configured secrets; omit them and mark coverage incomplete.
                val retained=source.files.filterKeys { path -> secrets.none(path::contains) }.mapValues { (_,value) ->
                    val excerpt=value.excerpt?.let { bounded(it,512) }
                    value.copy(excerpt=excerpt?.first,excerptTruncated=value.excerptTruncated || excerpt?.second==true)
                }
                source.copy(files=retained,complete=source.complete && retained.size==source.files.size)
            }
            event.copy(call=event.call?.copy(argumentsJson=arguments!!.first),result=result?.first,
                truncated=event.truncated || arguments?.second==true || result?.second==true,workspace=snapshot)
        }
        val now=maxOf(updatedAt,clock())
        val saved=try { store.appendExecutionEvents(run.id,sequence,safe,now) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { throw ExecutionJournalException(e) }
        if(!saved) throw ExecutionJournalException()
        sequence+=safe.size;updatedAt=now
    }
    suspend fun finish(status: ExecutionStatus) = lock.withLock {
        if(finished) return@withLock
        val now=maxOf(updatedAt,clock())
        val saved=try { store.finishExecutionRun(run.id,status,now) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { throw ExecutionJournalException(e) }
        if(!saved) throw ExecutionJournalException()
        finished=true;updatedAt=now
    }
    private fun bounded(value: String,limit: Int): Pair<String,Boolean> {
        var safe=value
        secrets.forEach { safe=safe.replace(it,"[已隐藏]") }
        safe=safe.replace(Regex("(?i)Bearer\\s+[^\\s\\\"'<>]+"),"Bearer [已隐藏]")
        safe=safe.replace(Regex("""(?i)("(?:api[_-]?key|authorization|password|token|secret|access_token)"\s*:\s*)"(?:\\.|[^"\\])*""""),"$1\"[已隐藏]\"")
        val bytes=safe.toByteArray(Charsets.UTF_8)
        if(bytes.size<=limit) return safe to false
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.IGNORE).onUnmappableCharacter(CodingErrorAction.IGNORE)
            .decode(ByteBuffer.wrap(bytes,0,limit)).toString() to true
    }
}
