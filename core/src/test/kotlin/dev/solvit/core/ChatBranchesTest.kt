package dev.solvit.core

import kotlin.test.*

class ChatBranchesTest {
    private val history = listOf(
        ChatMessage("u1", "s", Role.USER, "question"),
        ChatMessage("a1", "s", Role.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "clock", "{}"))),
        ChatMessage("t1", "s", Role.TOOL, "actual time", toolCallId = "call"),
        ChatMessage("a2", "s", Role.ASSISTANT, "answer"),
        ChatMessage("u2", "s", Role.USER, "follow up"),
        ChatMessage("a3", "s", Role.ASSISTANT, "partial", MessageStatus.INTERRUPTED))
    @Test fun assistantAnchorCopiesEntireToolTurnButNoLaterQuestion() {
        assertEquals(history.take(4), branchPrefix(history, BranchPoint("a1", BranchMode.AFTER_REPLY)))
        assertEquals(history.take(4), branchPrefix(history, BranchPoint("a2", BranchMode.AFTER_REPLY)))
    }
    @Test fun editingExcludesOldQuestionAndItsWholeReply() {
        assertEquals(history.take(4), branchPrefix(history, BranchPoint("u2", BranchMode.BEFORE_USER)))
        assertTrue(branchPrefix(history, BranchPoint("u1", BranchMode.BEFORE_USER)).isEmpty())
    }
    @Test fun missingOrWrongRoleAnchorCannotCreateDanglingToolHistory() {
        for (point in listOf(BranchPoint("missing", BranchMode.AFTER_REPLY), BranchPoint("t1", BranchMode.AFTER_REPLY),
            BranchPoint("a1", BranchMode.BEFORE_USER), BranchPoint("u1", BranchMode.AFTER_REPLY))) {
            assertFailsWith<IllegalArgumentException> { branchPrefix(history, point) }
        }
    }
}
