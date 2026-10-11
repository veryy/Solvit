package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ChatBranchServiceTest {
    private class Store : ChatStore {
        val records = linkedMapOf("source" to mutableListOf(ChatMessage("user", "source", Role.USER, "question"),
            ChatMessage("answer", "source", Role.ASSISTANT, "answer")))
        var sequence = 0
        override fun sessions() = flowOf(records.keys.map { ChatSession(it, it, 0) })
        override fun messages(sessionId: String) = flowOf(records[sessionId].orEmpty().toList())
        override suspend fun history(sessionId: String) = records[sessionId].orEmpty().toList()
        override suspend fun createSession(): ChatSession = error("Not used")
        override suspend fun append(message: ChatMessage) { records.getValue(message.sessionId).add(message) }
        override suspend fun deleteSession(sessionId: String) { records.remove(sessionId) }
        override suspend fun forkSession(sessionId: String, point: BranchPoint): ChatSession {
            val id = "branch-${++sequence}"
            records[id] = branchPrefix(history(sessionId), point).mapIndexed { index, message -> message.copy(id="$id-$index", sessionId=id) }.toMutableList()
            return ChatSession(id, "Branch", 0, sessionId, point.messageId)
        }
    }
    private class Roles : RoleService {
        val role = AgentRole("restricted", "Restricted", pluginIds=emptySet())
        val selections = mutableMapOf("source" to role.id)
        var failBinding = false
        override fun roles() = flowOf(listOf(role))
        override suspend fun save(role: AgentRole) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun selected(sessionId: String) = role.takeIf { selections[sessionId] == it.id }
        override suspend fun select(sessionId: String, roleId: String?) {
            if (failBinding && sessionId != "source" && roleId != null) error("Binding failed")
            if (roleId == null) selections.remove(sessionId) else selections[sessionId]=roleId
        }
    }
    @Test fun replacementStoreIsUsedAndRestrictedRoleIsCopied() = runTest {
        val store=Store(); val roles=Roles()
        val branch=DefaultChatBranchService(store,roles).fork("source",BranchPoint("answer",BranchMode.AFTER_REPLY))
        assertEquals(roles.role,roles.selected(branch.id))
        assertEquals("answer",store.history(branch.id).last().content)
        assertEquals("source",branch.originSessionId)
        store.deleteSession("source")
        assertEquals(2,store.history(branch.id).size)
    }
    @Test fun bindingFailureRollsBackTheBranchAndPreservesOriginal() = runTest {
        val store=Store(); val roles=Roles().apply { failBinding=true }
        assertFailsWith<IllegalStateException> { DefaultChatBranchService(store,roles).fork("source",BranchPoint("answer",BranchMode.AFTER_REPLY)) }
        assertEquals(setOf("source"),store.records.keys)
        assertEquals(mapOf("source" to roles.role.id),roles.selections)
        assertEquals(2,store.history("source").size)
    }
}
