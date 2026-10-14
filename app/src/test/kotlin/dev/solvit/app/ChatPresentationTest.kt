package dev.solvit.app

import dev.solvit.core.*
import kotlin.test.*

class ChatPresentationTest {
    @Test fun retryStreamsInOriginalBubbleAndCollapsesFailedAttemptsAfterSettlement() {
        val user=message("u",Role.USER,"question")
        val failed=message("failed",Role.ASSISTANT,"old partial").copy(status=MessageStatus.FAILED)
        val live=presentChat(listOf(user,failed),busy=true,baseline=failed.id,draft="new",retryUserId=user.id)
        assertEquals(listOf("u","reply-u"),live.map { it.id })
        assertEquals("new",live.last().messages.single().content)
        assertEquals(listOf(listOf(failed)),live.last().previousAttempts)
        val complete=message("success",Role.ASSISTANT,"new answer")
        val waiting=presentChat(listOf(user,failed,complete),busy=true,baseline=failed.id,draft="new",holdSettlement=true,retryUserId=user.id)
        assertEquals("new",waiting.last().messages.single().content)
        assertEquals(listOf(listOf(failed)),waiting.last().previousAttempts)
        val settled=presentChat(listOf(user,failed,complete))
        assertEquals(listOf("u","reply-u"),settled.map { it.id })
        assertEquals("new answer",settled.last().messages.single().content)
        assertEquals(listOf(listOf(failed)),settled.last().previousAttempts)
    }
    @Test fun recoveredExecutionUsesOriginalReplyBubbleWithoutInventingChatMessages() {
        val history=listOf(message("first",Role.USER,"task one"),message("answer",Role.ASSISTANT,"reply"),message("second",Role.USER,"run"))
        val first=ExecutionRun("r1","s","first",null,"workspace",1,status=ExecutionStatus.COMPLETE,eventCount=5)
        val second=ExecutionRun("r2","s","second",null,"workspace",2,status=ExecutionStatus.PROCESS_INTERRUPTED,eventCount=3)
        val rows=attachExecutions(presentChat(history),listOf(first,second))
        assertEquals(listOf("first","reply-first","second","reply-second"),rows.map { it.id })
        assertEquals(first,rows[1].execution);assertEquals("reply",rows[1].messages.single().content)
        assertEquals(second,rows.last().execution);assertTrue(rows.last().messages.isEmpty())
        assertFalse(rows.last().generating)
    }
    @Test fun journalDoesNotDuplicateLiveReplyOrAddDetailsToCompletedPlainChat() {
        val user=message("u",Role.USER,"question")
        val run=ExecutionRun("run","s","u",null,null,1,eventCount=1)
        val live=attachExecutions(presentChat(listOf(user),busy=true,draft="answer"),listOf(run))
        assertEquals(2,live.size);assertTrue(live.last().generating);assertEquals(run,live.last().execution)
        val done=attachExecutions(presentChat(listOf(user,message("a",Role.ASSISTANT,"answer"))),listOf(run.copy(status=ExecutionStatus.COMPLETE,eventCount=2)))
        assertEquals(2,done.size);assertNull(done.last().execution)
    }
    private fun message(id: String, role: Role, text: String = "") = ChatMessage(id,"s",role,text)
    @Test fun toolRoundsAndFinalAnswerShareOneAssistantBubble() {
        val call = ToolCall("call","clock","{}")
        val rows = presentChat(listOf(message("u",Role.USER,"time"),
            message("a",Role.ASSISTANT).copy(toolCalls=listOf(call)),
            message("t",Role.TOOL,"actual-time").copy(toolCallId=call.id),
            message("a2",Role.ASSISTANT,"final answer"), message("u2",Role.USER,"follow-up")))
        assertEquals(3,rows.size)
        assertEquals(listOf("a","t","a2"),rows[1].messages.map { it.id })
        assertEquals("u2",rows.last().user?.id)
    }
    @Test fun liveReplyNeverDuplicatesSettledTranscript() {
        val history=listOf(message("old",Role.USER),message("old-a",Role.ASSISTANT,"old answer"),message("new",Role.USER,"new question"))
        val live=presentChat(history,true,"old-a",draft="stream")
        assertEquals(4,live.size); assertTrue(live.last().generating)
        assertEquals("reply-new",live.last().id)
        val settled=presentChat(history+message("new-a",Role.ASSISTANT,"saved"),true,"old-a",draft="stream")
        assertEquals(4,settled.size); assertFalse(settled.last().generating)
        assertEquals("saved",settled.last().messages.single().content)
    }
    @Test fun partialThinkingIsHiddenAndCodeExamplesRemainLiteral() {
        assertEquals(ThinkingText("answer","plan"),splitThinking("<think>plan</think>answer"))
        assertEquals(ThinkingText("","partial"),splitThinking("<think>partial"))
        val afterThinking="Example: <think>literal</think>"
        assertEquals(ThinkingText(afterThinking,"plan"), splitThinking("<think>plan</think>"+afterThinking))
        val example="Example:\n```xml\n<think>literal</think>\n```"
        assertEquals(ThinkingText(example,""),splitThinking(example))
    }
    @Test fun persistedFullAnswerStaysHiddenUntilPlaybackFinishes() {
        val history = listOf(message("old", Role.USER), message("old-a", Role.ASSISTANT, "old answer"),
            message("new", Role.USER, "question"), message("new-a", Role.ASSISTANT, "complete answer"))
        val playing = presentChat(history, true, "old-a", draft = "com", holdSettlement = true)
        assertEquals(4, playing.size)
        assertEquals("old answer", playing[1].messages.single().content)
        assertEquals("com", playing.last().messages.single().content)
        assertTrue(playing.last().generating)
        val finished = presentChat(history, false, "old-a", holdSettlement = false)
        assertEquals("complete answer", finished.last().messages.single().content)
        assertFalse(finished.last().generating)
    }
}
