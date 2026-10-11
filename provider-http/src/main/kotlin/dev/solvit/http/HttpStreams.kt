package dev.solvit.http

import dev.solvit.core.ModelException
import kotlinx.coroutines.*
import okhttp3.*
import okio.BufferedSource
import java.io.IOException
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object: Callback {
        override fun onFailure(call: Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call,response:Response) { continuation.resume(response) { _,value,_ -> value.close() } }
    })
}
suspend fun <T> Call.readResponse(block:suspend (Response)->T): T = coroutineScope {
    val call=this@readResponse
    val cancellation=launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
    try { call.awaitResponse().use { block(it) } } finally { cancellation.cancelAndJoin();call.cancel() }
}
/** Bounds both a single line and a multiline SSE frame, including unknown event kinds. */
suspend fun readSse(source:BufferedSource, onEvent:suspend (String,String)->Boolean) {
    var event="";val data=StringBuilder();var bytes=0
    suspend fun dispatch(): Boolean {
        if(data.isEmpty()) { event="";bytes=0;return true }
        val keepReading=onEvent(event,data.toString());event="";data.setLength(0);bytes=0;return keepReading
    }
    while(true) {
        currentCoroutineContext().ensureActive()
        val newline=source.indexOf('\n'.code.toByte(),0,MAX_FRAME.toLong()+1)
        if(newline<0 && source.buffer.size>MAX_FRAME) throw ModelException("模型流数据超过限制")
        val line=if(newline>=0) source.readUtf8Line()!! else if(source.buffer.size>0) source.readUtf8().removeSuffix("\r") else break
        if(line.isEmpty()) { if(!dispatch()) return }
        else if(line.startsWith("event:")) event=line.substringAfter(':').trim()
        else if(line.startsWith("data:")) {
            val value=line.substringAfter(':').removePrefix(" ")
            bytes+=value.toByteArray(Charsets.UTF_8).size+1
            if(bytes>MAX_FRAME) throw ModelException("模型流数据超过限制")
            if(data.isNotEmpty()) data.append('\n');data.append(value)
        }
    }
    dispatch()
}
private const val MAX_FRAME=2*1024*1024
