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
    @Test fun plainChatDoesNotCreateWorkspaceAndExplicitBindingIsUsedNextTurn() = runTest {
        val workspace = Workspace("workspace", "Explicit")
        val state = MutableStateFlow(WorkspaceState())
        val service = object : WorkspaceService {
            override val state = state
            override val jobs = MutableStateFlow<List<ShellJob>>(emptyList())
            override suspend fun forChat(sessionId: String): Workspace = error("Chat must not auto-create a workspace")
            override suspend fun bind(sessionId: String, workspaceId: String) { state.value = WorkspaceState(listOf(workspace), mapOf(sessionId to workspaceId)) }
            override suspend fun unbind(sessionId: String) = Unit
            override suspend fun create(name: String): Workspace = error("Explicit UI operation only")
            override suspend fun createShell(workspaceId: String, name: String): ShellSession = error("Not used")
            override suspend fun read(workspaceId: String, path: String): String = error("Not used")
            override suspend fun write(workspaceId: String, path: String, text: String) = Unit
            override suspend fun run(workspaceId: String, shellId: String, command: String, timeoutMs: Long): ShellJob = error("Not used")
            override suspend fun cancel(workspaceId: String, jobId: String) = Unit
            override suspend fun cancelOwner(owner: String) = Unit
        }
        var conversation: ConversationContext? = null
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> {
                conversation = request.conversation
                return flowOf(ModelChunk.Text("answer"), ModelChunk.Done)
            }
        }
        val chat = DefaultChatService(MemoryStore(), agent, EventBus(), workspaces = service)
        chat.send("session", "plain question", ModelConfig()).toList()
        assertNull(conversation)
        assertTrue(state.value.workspaces.isEmpty())
        assertTrue(state.value.shells.isEmpty())
        service.bind("session", workspace.id)
        chat.send("session", "bound question", ModelConfig()).toList()
        assertEquals(workspace.id, conversation!!.workspaceId)
    }
    @Test fun reasoningIsStreamedAndStoredButExcludedFromNextModelContext() = runTest {
        val store = MemoryStore()
        var observed: ModelRequest? = null
        var first = true
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> {
                observed = request
                return if (first) flowOf(ModelChunk.Reasoning("private-thought"), ModelChunk.Text("answer"), ModelChunk.Done)
                    else flowOf(ModelChunk.Text("next"), ModelChunk.Done)
            }
        }
        val chat = DefaultChatService(store, agent, EventBus())
        val chunks = chat.send("session", "question", ModelConfig()).toList()
        assertEquals(ModelChunk.Reasoning("private-thought"), chunks.first())
        assertEquals("private-thought", store.saved.last().reasoning)
        first = false
        chat.send("session", "follow-up", ModelConfig()).toList()
        assertTrue(observed!!.messages.none { "private-thought" in it.content || it.reasoning.isNotEmpty() })
    }
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

    @Test fun successfulToolTranscriptSurvivesNextTurnAndInterruptedTraceIsExcluded() = runTest {
        for (finish in listOf(true, false)) {
            val store = MemoryStore()
            val call = ToolCall("call", "clock", "{}")
            val agent = object : AgentRuntime {
                override fun run(request: ModelRequest) = flow {
                    emit(ModelChunk.Transcript(ModelMessage(Role.ASSISTANT, "", listOf(call))))
                    emit(ModelChunk.Transcript(ModelMessage(Role.TOOL, "UTC", toolCallId = call.id)))
                    emit(ModelChunk.Text("answer"))
                    if (finish) emit(ModelChunk.Done) else throw ModelException("disconnected")
                }
            }
            val chat = DefaultChatService(store, agent, EventBus())
            if (finish) chat.send("session", "question", ModelConfig()).toList()
            else assertFailsWith<ModelException> { chat.send("session", "question", ModelConfig()).toList() }
            assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), store.saved.map { it.role })
            assertEquals(if (finish) MessageStatus.COMPLETE else MessageStatus.FAILED, store.saved[1].status)
            val next = object : AgentRuntime {
                override fun run(request: ModelRequest): Flow<ModelChunk> {
                    assertEquals(finish, request.messages.any { it.toolCalls.isNotEmpty() })
                    assertEquals(finish, request.messages.any { it.role == Role.TOOL && it.toolCallId == call.id })
                    return flowOf(ModelChunk.Text("next"), ModelChunk.Done)
                }
            }
            DefaultChatService(store, next, EventBus()).send("session", "continue", ModelConfig()).toList()
        }
    }
}
