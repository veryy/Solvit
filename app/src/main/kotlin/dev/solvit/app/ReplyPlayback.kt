package dev.solvit.app

import dev.solvit.core.ModelChunk
import dev.solvit.core.ModelException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Confined to the ViewModel's Main scope, like Operit's render coordination. */
internal class ReplyPlayback(scope: CoroutineScope, private val display: (ModelChunk) -> Unit) {
    private sealed interface Pending
    private class Text(val reasoning: Boolean, val value: StringBuilder, var offset: Int = 0) : Pending
    private class Event(val chunk: ModelChunk) : Pending
    private val pending = ArrayDeque<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var queuedCharacters = 0
    private val job = scope.launch {
        for (signal in wake) {
            while (pending.isNotEmpty()) {
                when (val next = pending.first()) {
                    is Event -> { pending.removeFirst(); display(next.chunk) }
                    is Text -> {
                        val text = next.value
                        // Initial content appears immediately. Subsequent frames catch up when
                        // behind. Adjacent transport deltas share the same rendering buffer.
                        val count = if (next.offset == 0) 1 else (queuedCharacters / 60).coerceIn(4, 64)
                        var end = next.offset
                        if (next.reasoning) end = text.length
                        else repeat(count) {
                            if (end < text.length) end += Character.charCount(text.codePointAt(end))
                        }
                        val value = text.substring(next.offset, end)
                        queuedCharacters -= value.length
                        next.offset = end
                        display(if (next.reasoning) ModelChunk.Reasoning(value) else ModelChunk.Text(value))
                        if (end == text.length) pending.removeFirst()
                        delay(16)
                    }
                }
            }
        }
    }

    fun enqueue(chunk: ModelChunk) {
        val content = when (chunk) {
            is ModelChunk.Text -> chunk.value
            is ModelChunk.Reasoning -> chunk.value
            else -> null
        }
        if (content != null) {
            if (content.isEmpty()) return
            if (content.length > MAX_PENDING_CHARACTERS - queuedCharacters)
                throw ModelException("回复显示缓冲超出限制，请缩短回复或重新尝试")
            val reasoning = chunk is ModelChunk.Reasoning
            val tail = pending.lastOrNull() as? Text
            if (tail != null && tail.reasoning == reasoning) tail.value.append(content)
            else { checkEventLimit(); pending.addLast(Text(reasoning, StringBuilder(content))) }
            queuedCharacters += content.length
        } else { checkEventLimit(); pending.addLast(Event(chunk)) }
        check(wake.trySend(Unit).isSuccess) { "Playback already closed" }
    }

    suspend fun finish() { wake.close(); job.join() }
    fun cancel() { wake.cancel(); job.cancel(); pending.clear(); queuedCharacters = 0 }
    private fun checkEventLimit() {
        if (pending.size >= 256) throw ModelException("回复显示事件超出限制，请重新尝试")
    }
    private companion object { const val MAX_PENDING_CHARACTERS = 1024 * 1024 }
}
