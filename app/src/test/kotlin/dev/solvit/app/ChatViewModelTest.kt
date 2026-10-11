package dev.solvit.app

import dev.solvit.core.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test fun receivingALargeDeltaDoesNotDelayProviderOrToolStatus() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val providerWaiting = CompletableDeferred<Unit>()
            val chat = object : ChatService {
                override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
                override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
                override suspend fun createSession() = ChatSession("session", "Test", 0)
                override suspend fun deleteSession(sessionId: String) = Unit
                override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) = flow {
                    emit(ModelChunk.Text("字".repeat(5_000)))
                    emit(ModelChunk.Reasoning("next reasoning"))
                    emit(ModelChunk.ToolActivity(ToolCall("call", "clock", "{}"), ToolStage.RUNNING))
                    providerWaiting.complete(Unit)
                    awaitCancellation()
                }
            }
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey = "fixture")
                override suspend fun save(value: ModelConfig) = Unit
            }
            val vm = ChatViewModel(chat, settings)
            runCurrent(); assertTrue(vm.send("hello")); advanceTimeBy(16); runCurrent()
            assertTrue(providerWaiting.isCompleted, "Rendering must not block subsequent provider events")
            assertEquals(ToolStage.RUNNING, vm.toolActivity.value.single().stage)
            assertTrue(vm.draft.value.length in 1 until 5_000)
            vm.stop(); runCurrent(); assertFalse(vm.busy.value)
            vm.viewModelScope.cancel()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun aCompletedProviderDoesNotSkipPlaybackAndToolRoundsStayOrdered() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val received = CompletableDeferred<Unit>()
            val call = ToolCall("call", "clock", "{}")
            val chat = object : ChatService {
                override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
                override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
                override suspend fun createSession() = ChatSession("session", "Test", 0)
                override suspend fun deleteSession(sessionId: String) = Unit
                override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) = flow {
                    emit(ModelChunk.Text("before tool"))
                    emit(ModelChunk.Transcript(ModelMessage(Role.ASSISTANT, "before tool", listOf(call))))
                    emit(ModelChunk.Transcript(ModelMessage(Role.TOOL, "result", toolCallId = call.id)))
                    emit(ModelChunk.Reasoning("second round"))
                    emit(ModelChunk.Text("后".repeat(5_000)))
                    received.complete(Unit)
                    emit(ModelChunk.Done)
                }
            }
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey = "fixture")
                override suspend fun save(value: ModelConfig) = Unit
            }
            val vm = ChatViewModel(chat, settings)
            runCurrent(); assertTrue(vm.send("hello")); runCurrent()
            assertTrue(received.isCompleted)
            assertTrue(vm.busy.value, "Playback is still active even after model completion")
            assertEquals("b", vm.draft.value)
            advanceTimeBy(100); runCurrent()
            assertEquals(listOf(Role.ASSISTANT, Role.TOOL), vm.liveTranscript.value.map { it.role })
            assertEquals("before tool", vm.liveTranscript.value.first().content)
            assertEquals("second round", vm.reasoning.value)
            assertTrue(vm.draft.value.all { it == '后' })
            assertTrue(vm.draft.value.length in 1 until 5_000)
            advanceTimeBy(5_000); runCurrent()
            assertFalse(vm.busy.value, "A large delta must not impose 20 seconds of artificial latency")
            vm.viewModelScope.cancel()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun adjacentTinyDeltasAreBatchedWithoutRevealingTheWholeBurst() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val chat = object : ChatService {
                override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
                override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
                override suspend fun createSession() = ChatSession("session", "Test", 0)
                override suspend fun deleteSession(sessionId: String) = Unit
                override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) = flow {
                    repeat(100) { emit(ModelChunk.Text("字")) }
                    awaitCancellation()
                }
            }
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey = "fixture")
                override suspend fun save(value: ModelConfig) = Unit
            }
            val vm = ChatViewModel(chat, settings)
            runCurrent(); assertTrue(vm.send("hello")); runCurrent()
            assertEquals("字", vm.draft.value)
            advanceTimeBy(16); runCurrent(); assertEquals(5, vm.draft.value.length)
            vm.stop(); runCurrent(); assertFalse(vm.busy.value)
            vm.viewModelScope.cancel()
        } finally { Dispatchers.resetMain() }
    }
    private class Chat(private val firstText: String = "first token") : ChatService {
        val list = MutableStateFlow(listOf(ChatSession("original","Original",0)))
        val continueReply = CompletableDeferred<Unit>()
        override fun sessions(): Flow<List<ChatSession>> = list
        override fun messages(sessionId: String): Flow<List<ChatMessage>> = flowOf(emptyList())
        override suspend fun createSession(): ChatSession = ChatSession("new-${list.value.size}","New",0).also { list.value += it }
        override suspend fun deleteSession(sessionId: String) = Unit
        override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) = flow {
            emit(ModelChunk.Text(firstText)); continueReply.await(); emit(ModelChunk.Text(" second token")); emit(ModelChunk.Done)
        }
    }
    @Test fun aWholeResponseBlockIsRevealedByUnicodeCodePointAndCanBeStopped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey="fixture")
                override suspend fun save(value: ModelConfig) = Unit
            }
            val chat = Chat("你好🙂abcdef"); val vm = ChatViewModel(chat, settings)
            runCurrent(); assertTrue(vm.send("hello")); runCurrent()
            assertEquals("你", vm.draft.value)
            advanceTimeBy(16); runCurrent()
            assertEquals("你好🙂ab", vm.draft.value)
            assertFalse(vm.draft.value.contains("abcdef")); assertTrue(vm.busy.value)
            vm.stop(); runCurrent()
            assertFalse(vm.busy.value); assertFalse(chat.continueReply.isCompleted)
            vm.viewModelScope.cancel()
        } finally { Dispatchers.resetMain() }
    }
    @Test fun partialTextIsExposedWhileProviderIsStillWaitingAndNewChatInheritsRole() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bindings = mutableMapOf("original" to AgentRole("coder","Coder","Kotlin"))
            val roles = object : RoleService {
                override fun roles() = flowOf(bindings.values.toList())
                override suspend fun save(role: AgentRole) = Unit
                override suspend fun delete(id: String) = Unit
                override suspend fun selected(sessionId: String) = bindings[sessionId]
                override suspend fun select(sessionId: String, roleId: String?) { bindings[sessionId] = bindings.values.first { it.id == roleId } }
            }
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey="fixture")
                override suspend fun save(value: ModelConfig) = Unit
            }
            val chat = Chat(); val vm = ChatViewModel(chat,settings,roles)
            runCurrent()
            assertTrue(vm.send("hello")); runCurrent()
            assertTrue(vm.busy.value); assertEquals("f",vm.draft.value)
            advanceTimeBy(16); runCurrent()
            assertEquals("first",vm.draft.value)
            advanceTimeBy(200); runCurrent()
            assertEquals("first token",vm.draft.value)
            assertFalse(chat.continueReply.isCompleted)
            chat.continueReply.complete(Unit); advanceTimeBy(300); runCurrent()
            assertFalse(vm.busy.value)
            vm.newChat(); runCurrent()
            assertEquals("coder",bindings[vm.sessionId.value]!!.id)
            assertNotEquals("original",vm.sessionId.value)
            // ViewModel has eager state collectors; release their scope explicitly.
            vm.viewModelScope.cancel()
        } finally { Dispatchers.resetMain() }
    }
}
