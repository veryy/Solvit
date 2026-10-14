package dev.solvit.app

import dev.solvit.core.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test fun failedConfigurationLoadCannotSendOrQueueAndRecoveryUsesOnlyTheSavedEndpoint() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val received = mutableListOf<ModelConfig>()
            val chat = object : ChatService {
                override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
                override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
                override suspend fun createSession() = ChatSession("session", "Test", 0)
                override suspend fun deleteSession(sessionId: String) = Unit
                override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) = flow {
                    received += config; emit(ModelChunk.Text("ok")); emit(ModelChunk.Done)
                }
            }
            for (failure in listOf(java.io.IOException("fixture read failure"),
                SettingsDecryptionException(ModelConfig("https://saved.example/v1", "saved-model"), java.io.IOException("fixture key failure")))) {
                var saved: ModelConfig? = null
                val settings = object : SettingsStore {
                    override suspend fun load(): ModelConfig = throw failure
                    override suspend fun save(config: ModelConfig) { saved = config }
                }
                val vm = ChatViewModel(chat, settings)
                try {
                    runCurrent(); assertTrue(vm.ready.value)
                    val original = vm.error.value; assertNotNull(original)
                    vm.dismissError(); assertFalse(vm.send("private text")); assertEquals(original, vm.error.value)
                    assertFalse(vm.interruptAndSend("private text")); runCurrent()
                    assertTrue(vm.queuedMessages.value.isEmpty()); assertTrue(received.isEmpty())
                    val recovery = ModelConfig("https://chosen.example/v1", "chosen-model")
                    vm.restoreConfig(recovery); assertSame(recovery, saved)
                    assertTrue(vm.send("after recovery")); runCurrent()
                    assertSame(recovery, received.single()); received.clear()
                } finally { vm.viewModelScope.cancel() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun queuedAttachmentOnlyMessagesKeepAnImmutableSnapshotAndCanInterrupt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val received = mutableListOf<Pair<String, List<ChatAttachment>>>()
            val chat = object : ChatService {
                override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
                override fun messages(sessionId: String) = flowOf(emptyList<ChatMessage>())
                override suspend fun createSession() = ChatSession("session", "Test", 0)
                override suspend fun deleteSession(sessionId: String) = Unit
                override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) =
                    sendAttachments(sessionId, text, emptyList(), config, authorization)
                override fun sendAttachments(sessionId: String, text: String, attachments: List<ChatAttachment>, config: ModelConfig,
                    authorization: ToolAuthorization) = flow {
                    received += text to attachments
                    emit(ModelChunk.Text("partial")); awaitCancellation()
                }
            }
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig()
                override suspend fun save(value: ModelConfig) = Unit
            }
            val vm = ChatViewModel(chat, settings)
            try {
                runCurrent(); assertTrue(vm.send("first")); runCurrent()
                val attachment = ChatAttachment(java.util.UUID.randomUUID().toString(), "image.png", "image/png", 4, "a".repeat(64))
                val draft = mutableListOf(attachment)
                assertTrue(vm.send("", draft)); draft.clear()
                val item = vm.queuedMessages.value.single()
                assertEquals(listOf(attachment), item.attachments)
                assertTrue(vm.editQueued(item.id, ""))
                assertTrue(vm.sendQueuedNow(item.id)); runCurrent()
                assertEquals(listOf(attachment), received.last().second)
                assertEquals("", received.last().first)
                assertTrue(vm.queuedMessages.value.isEmpty())
                vm.stop(); runCurrent()
            } finally { vm.viewModelScope.cancel() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun removingQueuedAttachmentsReclaimsOnlyUnreferencedFilesAndRejectsOtherChats() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            fun attachment(name: String) = ChatAttachment(java.util.UUID.randomUUID().toString(), name, "text/plain", 1, "a".repeat(64))
            val saved = attachment("saved.txt"); val shared = attachment("shared.txt"); val unused = attachment("unused.txt")
            val removed = mutableListOf<String>()
            val history = listOf(ChatMessage("user", "session", Role.USER, "saved", attachments=listOf(saved)))
            val chat = object : ChatService {
                override fun sessions() = flowOf(listOf(ChatSession("session", "Test", 0)))
                override fun messages(sessionId: String) = flowOf(if (sessionId == "session") history else emptyList())
                override suspend fun createSession() = ChatSession("other", "Other", 0)
                override suspend fun deleteSession(sessionId: String) = Unit
                override fun send(sessionId: String, text: String, config: ModelConfig, authorization: ToolAuthorization) = flow<ModelChunk> { awaitCancellation() }
            }
            val files = object : ChatAttachmentStore {
                override suspend fun import(sessionId: String, name: String, mimeType: String, input: java.io.InputStream, authorize: suspend () -> Unit): ChatAttachment = error("unused")
                override suspend fun read(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit): ByteArray = error("unused")
                override suspend fun remove(sessionId: String, attachment: ChatAttachment, authorize: suspend () -> Unit) { removed += attachment.id }
            }
            val settings = object : SettingsStore { override suspend fun load() = ModelConfig(); override suspend fun save(value: ModelConfig) = Unit }
            val vm = ChatViewModel(chat, settings, attachmentStore=files)
            try {
                runCurrent()
                vm.attachmentDrafts.value = mapOf("session" to listOf(shared))
                vm.queuedMessages.value = listOf(ChatViewModel.QueuedMessage("discard", "session", "", listOf(saved,shared,unused)),
                    ChatViewModel.QueuedMessage("foreign", "other", "", listOf(unused)))
                vm.removeQueued("foreign"); runCurrent()
                assertEquals(2,vm.queuedMessages.value.size);assertTrue(removed.isEmpty())
                vm.removeQueued("discard");runCurrent()
                assertEquals(listOf(unused.id),removed)
                assertEquals("foreign",vm.queuedMessages.value.single().id)
                assertEquals(listOf(shared),vm.attachmentDrafts.value.getValue("session"))
            } finally { vm.viewModelScope.cancel() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun editedAttachmentCopyFailureDeletesNewBranchAndPreservesOriginalSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val image=ChatAttachment(java.util.UUID.randomUUID().toString(),"image.png","image/png",1,"a".repeat(64))
            val deleted=mutableListOf<String>()
            val chat=object:ChatService {
                override fun sessions()=flowOf(listOf(ChatSession("source","Source",0)))
                override fun messages(sessionId:String)=flowOf(listOf(ChatMessage("original",sessionId,Role.USER,"",attachments=listOf(image))))
                override suspend fun createSession()=ChatSession("source","Source",0)
                override suspend fun deleteSession(sessionId:String){deleted+=sessionId}
                override fun send(sessionId:String,text:String,config:ModelConfig,authorization:ToolAuthorization)=flowOf(ModelChunk.Done)
            }
            val branches=object:ChatBranchService{override suspend fun fork(sessionId:String,point:BranchPoint)=ChatSession("copy","Copy",0)}
            val files=object:ChatAttachmentStore {
                override suspend fun import(sessionId:String,name:String,mimeType:String,input:java.io.InputStream,authorize:suspend()->Unit):ChatAttachment=error("disk full fixture")
                override suspend fun read(sessionId:String,attachment:ChatAttachment,authorize:suspend()->Unit)=byteArrayOf(1)
                override suspend fun remove(sessionId:String,attachment:ChatAttachment,authorize:suspend()->Unit)=Unit
            }
            val settings=object:SettingsStore{override suspend fun load()=ModelConfig();override suspend fun save(value:ModelConfig)=Unit}
            val vm=ChatViewModel(chat,settings,branches=branches,attachmentStore=files)
            try {
                runCurrent();var callback=false
                vm.fork(BranchPoint("original",BranchMode.BEFORE_USER),""){callback=true};runCurrent()
                assertEquals(listOf("copy"),deleted);assertEquals("source",vm.sessionId.value)
                assertFalse(callback);assertFalse(vm.branching.value);assertTrue(vm.attachmentDrafts.value.isEmpty())
                assertNotNull(vm.branchError.value);assertNull(vm.branchInput.value)
            }finally{vm.viewModelScope.cancel()}
        }finally{Dispatchers.resetMain()}
    }

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
            assertEquals(1, vm.replyTiming.value!!.receivedTextEvents, "Display frames must not be counted as provider text events")
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
            val vm = ChatViewModel(chat, settings, nowMillis = { testScheduler.currentTime })
            runCurrent(); assertTrue(vm.send("hello")); runCurrent()
            assertTrue(received.isCompleted)
            assertEquals(0L, vm.replyTiming.value!!.receivedEndMs)
            assertEquals(0L, vm.replyTiming.value!!.firstVisibleMs)
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
            assertEquals("complete", vm.replyTiming.value!!.outcome)
            assertTrue(vm.replyTiming.value!!.displayEndMs!! > vm.replyTiming.value!!.receivedEndMs!!)
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
    @Test fun busyComposerAcceptsMessagesAndRunsThemOnceInFifoOrder() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val release=CompletableDeferred<Unit>();val received=mutableListOf<String>()
            var running=0;var maximum=0
            val chat=object:ChatService {
                override fun sessions()=flowOf(listOf(ChatSession("session","Test",0)))
                override fun messages(sessionId:String)=flowOf(emptyList<ChatMessage>())
                override suspend fun createSession()=ChatSession("new","New",0)
                override suspend fun deleteSession(sessionId:String)=Unit
                override fun send(sessionId:String,text:String,config:ModelConfig,authorization:ToolAuthorization)=flow {
                    received+=text;maximum=maxOf(maximum,++running)
                    try { if(text=="first")release.await();emit(ModelChunk.Text(text));emit(ModelChunk.Done) }
                    finally { running-- }
                }
            }
            val settings=object:SettingsStore {
                override suspend fun load()=ModelConfig(apiKey="fixture")
                override suspend fun save(value:ModelConfig)=Unit
            }
            val vm=ChatViewModel(chat,settings);runCurrent()
            assertTrue(vm.send("first"));runCurrent()
            assertTrue(vm.send("second"));assertTrue(vm.send("third"));runCurrent()
            assertEquals(listOf("first"),received)
            release.complete(Unit);advanceUntilIdle()
            assertEquals(listOf("first","second","third"),received);assertEquals(1,maximum)
            assertFalse(vm.busy.value);vm.viewModelScope.cancel()
        }finally { Dispatchers.resetMain() }
    }

    @Test fun interruptWaitsForSettlementAndQueueEditsTargetExactOccurrences() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val received=mutableListOf<String>();var settled=false;var running=0;var maximum=0
            val chat=object:ChatService {
                override fun sessions()=flowOf(listOf(ChatSession("session","Test",0)))
                override fun messages(sessionId:String)=flowOf(emptyList<ChatMessage>())
                override suspend fun createSession()=ChatSession("new","New",0)
                override suspend fun deleteSession(sessionId:String)=Unit
                override fun send(sessionId:String,text:String,config:ModelConfig,authorization:ToolAuthorization)=flow {
                    received+=text;maximum=maxOf(maximum,++running)
                    try { if(text=="first")awaitCancellation() else { assertTrue(settled);emit(ModelChunk.Text(text));emit(ModelChunk.Done) } }
                    finally { if(text=="first")withContext(NonCancellable){delay(100);settled=true};running-- }
                }
            }
            val settings=object:SettingsStore {
                override suspend fun load()=ModelConfig(apiKey="fixture")
                override suspend fun save(value:ModelConfig)=Unit
            }
            val vm=ChatViewModel(chat,settings);runCurrent();assertTrue(vm.send("first"));runCurrent()
            assertTrue(vm.send("same"));assertTrue(vm.send("same"))
            val ids=vm.queuedMessages.value.map { it.id };assertEquals(2,ids.toSet().size)
            assertTrue(vm.editQueued(ids[0],"edited"));vm.removeQueued(ids[1])
            assertTrue(vm.interruptAndSend("urgent"));runCurrent()
            assertTrue(vm.switching.value);assertFalse(settled);assertEquals(listOf("first"),received)
            assertTrue(vm.send("later"));advanceUntilIdle()
            assertEquals(listOf("first","urgent","edited","later"),received);assertEquals(1,maximum)
            assertTrue(vm.queuedMessages.value.isEmpty());assertFalse(vm.busy.value)
            vm.viewModelScope.cancel()
        }finally { Dispatchers.resetMain() }
    }
    @Test fun stopPreservesQueueAndChangingChatsCannotDispatchAnotherChatsPendingMessage() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val received=mutableListOf<Pair<String,String>>()
            val chat=object:ChatService {
                override fun sessions()=flowOf(listOf(ChatSession("session","Test",0),ChatSession("other","Other",0)))
                override fun messages(sessionId:String)=flowOf(emptyList<ChatMessage>())
                override suspend fun createSession()=ChatSession("new","New",0)
                override suspend fun deleteSession(sessionId:String)=Unit
                override fun send(sessionId:String,text:String,config:ModelConfig,authorization:ToolAuthorization)=flow {
                    received+=sessionId to text
                    if(text=="first")awaitCancellation() else emit(ModelChunk.Done)
                }
            }
            val settings=object:SettingsStore {
                override suspend fun load()=ModelConfig(apiKey="fixture")
                override suspend fun save(value:ModelConfig)=Unit
            }
            val vm=ChatViewModel(chat,settings);runCurrent();assertTrue(vm.send("first"));runCurrent()
            assertTrue(vm.send("queued"));val queued=vm.queuedMessages.value.single();vm.stop();runCurrent()
            assertFalse(vm.busy.value);assertTrue(vm.queuePaused.value);assertEquals(1,received.size)
            vm.select("other");assertFalse(vm.sendQueuedNow(queued.id));assertFalse(vm.editQueued(queued.id,"wrong"))
            assertTrue(vm.send("other-turn"));advanceUntilIdle()
            vm.resumeQueue();runCurrent();assertEquals(2,received.size)
            vm.select("session")
            assertTrue(vm.queuePaused.value,"The original stopped queue must still expose its resume action after another chat finishes")
            assertEquals(2,received.size)
            vm.resumeQueue();advanceUntilIdle()
            assertEquals(listOf("session" to "first","other" to "other-turn","session" to "queued"),received)
            vm.viewModelScope.cancel()
        }finally { Dispatchers.resetMain() }
    }

}
