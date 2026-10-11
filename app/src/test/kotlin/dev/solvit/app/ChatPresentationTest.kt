package dev.solvit.app

import dev.solvit.core.*
import kotlin.test.*

class ChatPresentationTest {
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
}
