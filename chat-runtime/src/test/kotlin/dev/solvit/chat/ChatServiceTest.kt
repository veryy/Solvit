package dev.solvit.chat

import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** A replacement ChatStore: runtime and UI need no SQLite implementation. */
private class MemoryStore : ChatStore {
    val saved = mutableListOf<ChatMessage>()
    val turns = mutableListOf<CompletedChatTurn>()
    override val supportsCompletedTurns = true
    override suspend fun appendCompletedTurn(messages: List<ChatMessage>, turn: CompletedChatTurn) {
        super<ChatStore>.appendCompletedTurn(messages, turn)
        turns += turn
    }
    override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
    override fun messages(sessionId: String) = flowOf(saved.toList())
    override suspend fun createSession() = ChatSession("session", "Test", 0)
    override suspend fun history(sessionId: String) = saved.filter { it.sessionId == sessionId }
    override suspend fun append(message: ChatMessage) { saved += message }
    override suspend fun deleteSession(sessionId: String) { saved.removeAll { it.sessionId == sessionId } }
}

class ChatServiceTest {
    @Test fun characterReplacesBuiltInIntroductionButKeepsExplicitUserRules() {
        val role = AgentRole("star", "星辰", "使用简洁中文回答")
        val default = ChatContextComposer.render(ChatContextComposer.sections(role, ModelConfig().systemPrompt))
        assertFalse(default.contains(ModelConfig().systemPrompt))
        assertTrue(default.contains("星辰"))
        assertTrue(default.contains(role.prompt))
        assertFalse(ChatContextComposer.sections(role, ModelConfig().systemPrompt).any { it.name == "user-rules" })
        val custom = "仅用中文回答，不编造事实。"
        assertTrue(ChatContextComposer.render(ChatContextComposer.sections(role, custom)).contains(custom))
        assertTrue(ChatContextComposer.render(ChatContextComposer.sections(null, ModelConfig().systemPrompt))
            .contains(ModelConfig().systemPrompt))
    }

    @Test fun enablingAfterTurnStartsCannotRetroactivelyAdmitThatConversation() = runTest {
        val store = MemoryStore()
        val registry = PostTurnHookRegistry()
        var enabled = false
        val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
        registry.register(object : PostTurnHook {
            override val target = target
            override suspend fun enabled() = enabled
            override suspend fun process(input: PostTurnInput, model: PostTurnModel) = Unit
        })
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest) = flow {
                enabled = !enabled
                emit(ModelChunk.Text("reply")); emit(ModelChunk.Done)
            }
        }
        val chat = DefaultChatService(store, agent, EventBus(), postTurnHooks = registry)
        chat.send("session", "first", ModelConfig()).toList()
        assertTrue(store.turns[0].targets.isEmpty())
        chat.send("session", "second", ModelConfig()).toList()
        assertEquals(listOf(target), store.turns[1].targets)
        assertFalse(enabled)
    }
    @Test fun completedTurnKeepsOriginalRoleAndMessageSourcesWithoutPromptOrCredentials() = runTest {
        val store = MemoryStore()
        val bindings = mutableSetOf("dev.solvit.memory")
        var selected = AgentRole("original", "Original", "private role prompt", pluginIds = bindings)
        val roles = object : RoleService {
            override fun roles() = flowOf(listOf(selected))
            override suspend fun save(role: AgentRole) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun selected(sessionId: String) = selected
            override suspend fun select(sessionId: String, roleId: String?) = Unit
        }
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest) = flow {
                bindings.clear()
                selected = AgentRole("next", "Next")
                emit(ModelChunk.Text("private answer")); emit(ModelChunk.Done)
            }
        }
        DefaultChatService(store, agent, EventBus(), roles).send("session", "private question", ModelConfig(apiKey = "private-key")).toList()
        val record = store.turns.single()
        assertEquals("original", record.roleId)
        assertEquals(setOf("dev.solvit.memory"), record.allowedPluginIds)
        assertEquals(store.saved.first().id, record.userMessageId)
        assertEquals(store.saved.last().id, record.assistantMessageId)
        assertEquals(record.id, record.assistantMessageId)
        assertFalse(record.toString().contains("private"))
        assertNull(record.requestedProfileId)
    }
    @Test fun providerMetadataIsStoredAndReturnedInNextContextWithoutLeakingIntoText() = runTest {
        val store = MemoryStore()
        val native = ProviderTranscript("anthropic","test-model","""[{"type":"thinking","signature":"opaque"}]""")
        var count = 0
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> {
                if (count++ > 0) {
                    val previous = request.messages.first { it.role == Role.ASSISTANT }
                    assertEquals(native,previous.providerTranscript)
                    assertEquals("answer",previous.content)
                    assertEquals("",previous.reasoning)
                }
                return flowOf(ModelChunk.Text("answer"),ModelChunk.NativeTranscript(native),ModelChunk.Done)
            }
        }
        val chat = DefaultChatService(store,agent,EventBus())
        chat.send("session","first",ModelConfig()).toList()
        assertEquals(native,store.saved.last().providerTranscript)
        chat.send("session","second",ModelConfig()).toList()
    }
    @Test fun defaultRoleIdentityIsAlwaysPresentWithoutAnyRoleBinding() = runTest {
        var observed: ModelRequest? = null
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> { observed = request; return flowOf(ModelChunk.Text("Solvit"), ModelChunk.Done) }
        }
        DefaultChatService(MemoryStore(), agent, EventBus()).send("session", "你是谁？", ModelConfig(systemPrompt="Be brief")).toList()
        val instructions = observed!!.messages.single { it.role == Role.SYSTEM }.content
        assertTrue(instructions.contains("角色名称是“Solvit”"))
        assertTrue(instructions.contains("不需要搜索"))
        assertTrue(instructions.indexOf("角色名称") < instructions.indexOf("Be brief"))
        assertEquals(1, Regex("角色名称是“Solvit”").findAll(instructions).count(), "Identity must have one authoritative contribution")
        assertEquals("session", observed!!.sessionId)
        assertEquals(listOf("identity", "user-rules", "role-instructions"), observed!!.promptSources.map { it.name })
        assertTrue(observed!!.promptSources.none { "Be brief" in it.toString() })
    }
    @Test fun changingRoleAffectsNextRequestAndDoesNotEraseTranscript() = runTest {
        var role: AgentRole? = AgentRole("first", "First", "Speak briefly")
        val roles = object : RoleService {
            override fun roles() = flowOf(listOfNotNull(role))
            override suspend fun save(role: AgentRole) = Unit
            override suspend fun delete(id: String) = Unit
            override suspend fun selected(sessionId: String) = role
            override suspend fun select(sessionId: String, roleId: String?) = Unit
        }
        val store = MemoryStore()
        val requests = mutableListOf<ModelRequest>()
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest): Flow<ModelChunk> { requests += request; return flowOf(ModelChunk.Text("reply"),ModelChunk.Done) }
        }
        val chat = DefaultChatService(store,agent,EventBus(),roles=roles)
        chat.send("session","first question",ModelConfig()).toList()
        role = AgentRole("coder","Coder","Answer as a Kotlin developer",pluginIds=setOf("test.code"))
        chat.send("session","second question",ModelConfig()).toList()
        val next = requests.last()
        assertEquals("coder",next.agentRole!!.id)
        assertEquals(1,next.messages.count { it.role == Role.SYSTEM })
        assertTrue(next.messages.first().content.contains("Answer as a Kotlin developer"))
        assertFalse(next.messages.first().content.contains("Speak briefly"))
        assertTrue(next.messages.any { it.content == "first question" })
        assertEquals(4,store.saved.size)
    }
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
        assertTrue(store.turns.isEmpty())
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
        assertTrue(store.turns.isEmpty())
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
