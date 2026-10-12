package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException

/** Keep only bounded, redacted observations; never use this log as a replayable model transcript. */
internal class StoredExecutionJournal(private val store: ChatStore, private val run: ExecutionRun,
    secrets: List<String>, private val clock: () -> Long = System::currentTimeMillis) : ExecutionJournal {
    private val secrets=secrets.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
    private val lock=Mutex()
    private var sequence=0
    private var updatedAt=run.updatedAt
    private var finished=false
    override suspend fun append(events: List<ExecutionEvent>) = lock.withLock {
        if(finished) throw ExecutionJournalException()
        val safe=events.map { event ->
            if(listOfNotNull(event.call?.id,event.callId).any { id -> secrets.any(id::contains) }) throw ExecutionJournalException()
            val arguments=event.call?.let { bounded(it.argumentsJson,16*1024) }
            val result=event.result?.let { bounded(it,64*1024) }
            event.copy(call=event.call?.copy(argumentsJson=arguments!!.first),result=result?.first,
                truncated=event.truncated || arguments?.second==true || result?.second==true)
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
