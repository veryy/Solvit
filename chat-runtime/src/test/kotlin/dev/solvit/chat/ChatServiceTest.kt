package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A replacement ChatStore: runtime and UI need no SQLite implementation. */
private class MemoryStore : ChatStore {
    val saved = mutableListOf<ChatMessage>()
    override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
    override fun messages(sessionId: String) = flowOf(saved.toList())
    override suspend fun createSession() = ChatSession("session", "Test", 0)
    override suspend fun history(sessionId: String) = saved.filter { it.sessionId == sessionId }
    override suspend fun append(message: ChatMessage) { saved += message }
    override suspend fun deleteSession(sessionId: String) { saved.removeAll { it.sessionId == sessionId } }
}

class ChatServiceTest {
    @Test fun savesUserAndCompletedAssistantAndBuildsContextThroughInterfaces() = runTest {
        val store = MemoryStore()
        var sent: ModelRequest? = null
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> {
                sent = request
                return flowOf(ModelChunk.Text("hello"), ModelChunk.Done)
            }
        }
        val chat = DefaultChatService(store, agent, EventBus())
        assertEquals(listOf(ModelChunk.Text("hello"), ModelChunk.Done), chat.send("session", "question", ModelConfig()).toList())
        assertEquals(listOf(Role.USER, Role.ASSISTANT), store.saved.map { it.role })
        assertEquals("hello", store.saved.last().content)
        assertEquals(MessageStatus.COMPLETE, store.saved.last().status)
        assertEquals("question", sent!!.messages.last().content)
        assertEquals(Role.SYSTEM, sent!!.messages.first().role)
    }

    @Test fun cancelledPartialReplyIsStoredButExcludedFromNextModelRequest() = runTest {
        val store = MemoryStore()
        val started = CompletableDeferred<Unit>()
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest) = flow {
                emit(ModelChunk.Text("partial"))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val chat = DefaultChatService(store, agent, EventBus())
        val generation = launch { chat.send("session", "question", ModelConfig()).collect() }
        started.await()
        assertFailsWith<IllegalStateException> { chat.deleteSession("session") }
        generation.cancelAndJoin()
        assertEquals(MessageStatus.INTERRUPTED, store.saved.last().status)
        assertEquals("partial", store.saved.last().content)
        val nextAgent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> {
                assertFalse(request.messages.any { it.content == "partial" })
                return flowOf(ModelChunk.Text("next"), ModelChunk.Done)
            }
        }
        DefaultChatService(store, nextAgent, EventBus()).send("session", "continue", ModelConfig()).toList()
    }

    @Test fun failureRetainsPartialReplyAndUnlocksNextTurn() = runTest {
        val store = MemoryStore()
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest) = flow {
                emit(ModelChunk.Text("partial"))
                throw ModelException("connection interrupted")
            }
        }
        val chat = DefaultChatService(store, agent, EventBus())
        assertFailsWith<ModelException> { chat.send("session", "first", ModelConfig()).toList() }
        assertEquals(MessageStatus.FAILED, store.saved.last().status)
        assertFailsWith<ModelException> { chat.send("session", "second", ModelConfig()).toList() }
        assertEquals(4, store.saved.size)
    }

    @Test fun missingCompletionMarkerIsAFailedAttempt() = runTest {
        val store = MemoryStore()
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest) = flowOf<ModelChunk>(ModelChunk.Text("partial"))
        }
        assertFailsWith<IllegalStateException> {
            DefaultChatService(store, agent, EventBus()).send("session", "question", ModelConfig()).toList()
        }
        assertEquals(MessageStatus.FAILED, store.saved.last().status)
    }
}
