package dev.solvit.app

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReplyPlaybackTest {
    @Test fun explicitSpeedControlsDisplayWithoutBlockingAdmissionAndCanSwitchToImmediate() = runTest {
        var speed=10;val visible=StringBuilder();val events=mutableListOf<ModelChunk>()
        val playback=ReplyPlayback(this,{speed}) { chunk ->
            events+=chunk
            if(chunk is ModelChunk.Text)visible.append(chunk.value)
        }
        val body="🙂".repeat(1000)
        playback.enqueue(ModelChunk.Text(body))
        playback.enqueue(ModelChunk.Transcript(ModelMessage(Role.TOOL,"tool result",toolCallId="call")))
        runCurrent();assertEquals("🙂",visible.toString())
        advanceTimeBy(1000);runCurrent()
        assertTrue(visible.codePointCount(0,visible.length) in 10..12)
        assertTrue(events.none { it is ModelChunk.Transcript })
        speed=-1;advanceTimeBy(16);runCurrent();playback.finish()
        assertEquals(body,visible.toString())
        assertEquals(1,events.filterIsInstance<ModelChunk.Transcript>().size)
    }
    @Test fun slowDisplayCanCancelWithoutWaitingToRevealAnAlreadyReceivedReply() = runTest {
        val visible=StringBuilder()
        val playback=ReplyPlayback(this,{10}) { if(it is ModelChunk.Text)visible.append(it.value) }
        playback.enqueue(ModelChunk.Text("abc".repeat(10000)));runCurrent()
        assertEquals("a",visible.toString());playback.cancel();advanceTimeBy(1000);runCurrent()
        assertEquals("a",visible.toString())
    }
}
