package dev.solvit.http

import dev.solvit.core.ModelException
import kotlinx.coroutines.*
import okhttp3.*
import okio.BufferedSource
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Owns the response even when prompt cancellation discards its queued continuation. */
private class ResponseOwner {
    private var closed = false
    private var response: Response? = null

    fun adopt(value: Response): Boolean = synchronized(this) {
        if (closed) false else { response = value; true }
    }
    fun get(): Response = synchronized(this) { checkNotNull(response) }
    fun close() {
        val owned = synchronized(this) {
            closed = true
            response.also { response = null }
        }
        owned?.close()
    }
}

/** Reads, cancels and closes socket resources on IO, including abandoned responses. */
suspend fun <T> Call.readResponse(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend (Response) -> T
): T = withContext(ioDispatcher) {
    val call = this@readResponse
    val owner = ResponseOwner()
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    // A late callback owns its resource on OkHttp's worker. Otherwise
                    // ownership stays here; never close sockets in a cancellation handler.
                    if (owner.adopt(response)) continuation.resume(Unit) else response.close()
                }
            })
        }
        currentCoroutineContext().ensureActive()
        block(owner.get())
    } finally {
        withContext(NonCancellable + ioDispatcher) {
            try { cancellation.cancelAndJoin() } finally {
                try { call.cancel() } finally { owner.close() }
            }
        }
    }
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
