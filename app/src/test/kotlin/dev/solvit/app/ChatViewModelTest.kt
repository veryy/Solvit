package dev.solvit.app

import dev.solvit.core.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
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
            advanceTimeBy(24); runCurrent()
            assertEquals("你好🙂", vm.draft.value)
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
            advanceTimeBy(24); runCurrent()
            assertEquals("fir",vm.draft.value)
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
