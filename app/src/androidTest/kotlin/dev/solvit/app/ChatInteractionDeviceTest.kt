package dev.solvit.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import dev.solvit.chat.DefaultChatService
import dev.solvit.chat.ToolAgentRuntime
import dev.solvit.market.AndroidRoleService
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/** Real Compose/IME -> VM -> native roles/SQLite -> Agent/gate, with a scripted provider. */
class ChatInteractionDeviceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private lateinit var context: Context
    private lateinit var directory: File
    private val preferences=mutableSetOf<String>()
    @Before fun isolate() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="chat-interaction-"+UUID.randomUUID()
        directory=File(app.cacheDir,prefix).apply { check(mkdirs()) }
        context=object:ContextWrapper(app) {
            override fun getApplicationContext(): Context=this
            override fun getFilesDir(): File=File(directory,"files").apply { check(mkdirs()||isDirectory) }
            override fun getDatabasePath(name:String): File=File(File(directory,"db").apply { check(mkdirs()||isDirectory) },name)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?):SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory)
            override fun openOrCreateDatabase(name:String,mode:Int,factory:SQLiteDatabase.CursorFactory?,errorHandler:DatabaseErrorHandler?):SQLiteDatabase=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,errorHandler)
            override fun deleteDatabase(name:String):Boolean=SQLiteDatabase.deleteDatabase(getDatabasePath(name))
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                val scoped="$prefix-$name";preferences+=scoped;return super.getSharedPreferences(scoped,mode)
            }
        }
        rule.waitUntil(10_000) { (rule.activity.application as SolvitApplication).ready.value!=null }
    }
    @After fun cleanup() {
        preferences.forEach { InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences(it,0).edit().clear().commit() }
        directory.deleteRecursively()
    }
    private fun screenshot(name:String) {
        val target=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"ui-screenshots/$name.png")
        target.parentFile!!.mkdirs()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun inlineRememberedApprovalAndEditableQueueInterruptThenNewChatKeepTheActualRole() {
        val app=rule.activity.application as SolvitApplication
        val roles=AndroidRoleService(context)
        val role=AgentRole("queue-role","星辰开发助手","You are the Star coding assistant",setOf("test.queue"))
        runBlocking { roles.save(role);roles.selectForNewChats("explicit-choice",role.id) }
        val approvals=ToolApprovalStore(context)
        val requests=ConcurrentLinkedQueue<ModelRequest>();val executed=ConcurrentLinkedQueue<String>()
        val events=EventBus();val tools=ToolRegistry(events,approvals)
        tools.register(object:SolvitTool {
            override val descriptor=ToolDescriptor("queue_fixture","Fixture approval","{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}",pluginId="test.queue",pluginVersion="1.0.0",pluginDigest="a".repeat(64))
            override suspend fun execute(argumentsJson:String):String { executed.add(argumentsJson);return "approved fixture result" }
        })
        val provider=object:ModelProvider {
            override val id="fixture"
            override fun stream(request:ModelRequest)=flow {
                requests.add(request)
                val text=request.messages.last { it.role==Role.USER }.content
                val callId="call-$text"
                if(request.messages.none { it.role==Role.TOOL&&it.toolCallId==callId }) {
                    emit(ModelChunk.ToolCalls(listOf(ToolCall(callId,"queue_fixture","{}"))));emit(ModelChunk.Done)
                } else {
                    emit(ModelChunk.Text("response:$text"))
                    if(text=="first")awaitCancellation() else emit(ModelChunk.Done)
                }
            }
        }
        val store=SqliteChatStore(context)
        val models=mutableListOf<androidx.lifecycle.ViewModel>()
        lateinit var vm:ChatViewModel;lateinit var agents:AgentLibraryViewModel
        try {
            rule.runOnUiThread {
                val settings=object:SettingsStore {
                    override suspend fun load()=ModelConfig(apiKey="fixture")
                    override suspend fun save(value:ModelConfig)=Unit
                    override suspend fun isConfigured()=true
                }
                vm=ChatViewModel(DefaultChatService(store,ToolAgentRuntime(provider,tools),events,roles),settings,roles,approvals=approvals)
                agents=AgentLibraryViewModel(roles,app.skills,app.marketplace)
                val workspaces=WorkspaceViewModel(app.workspaces,app.pluginDrafts,app.terminals)
                val updates=UpdateViewModel(app.updates,app.updateDownloader);val market=MarketplaceViewModel(app.marketplace)
                models+=listOf(vm,agents,workspaces,updates,market)
                rule.activity.setContent { MaterialTheme { ChatScreen(vm,updates,market,agents,workspaces) } }
            }
            rule.waitUntil(10_000) { vm.ready.value&&agents.activeRole.value==role }
            val original=vm.sessionId.value!!
            rule.onNodeWithTag("chat_role_title").assertTextEquals(role.name)
            fun send(text:String) {
                rule.onNodeWithTag("chat_input").performTextReplacement(text)
                rule.waitUntil(10_000) {
                    val decor=rule.activity.window.decorView
                    val insets=decor.rootWindowInsets
                    val ime=insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                    val composer=rule.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
                    insets?.isVisible(WindowInsets.Type.ime())==true && ime>0 && composer.bottom<=decor.height-ime+1
                }
                rule.waitForIdle();rule.onNodeWithTag("chat_send").assertIsEnabled().performClick()
            }
            send("first")
            rule.waitUntil(10_000) { vm.toolApproval.value!=null }
            rule.onNodeWithTag("chat_tool_approval").assertIsDisplayed()
            assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
            rule.onNodeWithTag("tool_allow_remember").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { requests.size==2&&executed.size==1 }
            send("same");send("same")
            rule.waitUntil(10_000) { vm.queuedMessages.value.size==2 }
            val queued=vm.queuedMessages.value
            rule.onNode(hasText("编辑") and hasAnyAncestor(hasTestTag("queued_${queued[0].id}"))).performClick()
            rule.onNodeWithTag("queue_edit_input").performTextReplacement("edited")
            rule.onNode(hasText("保存") and hasAnyAncestor(hasTestTag("queued_${queued[0].id}"))).performClick()
            rule.onNode(hasText("移除") and hasAnyAncestor(hasTestTag("queued_${queued[1].id}"))).performClick()
            rule.onNodeWithTag("queue_send_${queued[0].id}").performClick()
            rule.waitUntil(10_000) { !vm.busy.value&&vm.queuedMessages.value.isEmpty()&&executed.size==2 }
            assertEquals(listOf("first","first","edited","edited"),requests.map { it.messages.last { message -> message.role==Role.USER }.content })
            assertTrue(requests.all { it.agentRole==role })
            rule.onNodeWithTag("chat_tool_approval").assertDoesNotExist()
            assertTrue(runBlocking { store.history(original) }.any { it.role==Role.ASSISTANT&&it.status==MessageStatus.INTERRUPTED })
            rule.onNodeWithContentDescription("对话历史").performClick();rule.onNodeWithTag("new_chat").performClick()
            rule.waitUntil(10_000) { vm.sessionId.value!=original&&agents.activeRole.value==role }
            rule.onNodeWithTag("chat_role_title").assertTextEquals(role.name)
            assertEquals(role,runBlocking { AndroidRoleService(context).selected(vm.sessionId.value!!) })
            screenshot("chat-inline-approval-queue-role")
        }finally {
            if(models.isNotEmpty()) {
                rule.runOnUiThread { vm.stop() };rule.waitUntil(10_000) { !vm.busy.value }
                rule.runOnUiThread { models.forEach { it.viewModelScope.cancel() } }
            }
            store.close()
        }
    }
    @Test fun actualChatAppearanceControlsChangePreviewAndSurviveCloseReload() {
        val store=AppearanceStore(context)
        var open by mutableStateOf(true)
        var displayedStore by mutableStateOf(store)
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme { if(open)ChatAppearanceScreen(displayedStore){open=false} } } }
        val before=rule.onNodeWithTag("chat_font_size").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("chat_font_size").performTouchInput { swipeRight() }
        try { rule.waitUntil(8000) { store.chat.value.fontSize>20f } }
        catch(failure:Throwable) {
            screenshot("chat-font-slider-failure")
            throw AssertionError("[DEBUG-chat-font] saved=${store.chat.value.fontSize} before=$before after=${rule.onNodeWithTag("chat_font_size").fetchSemanticsNode().boundsInRoot}\n"+rule.onRoot().printToString(),failure)
        }
        rule.onNodeWithTag("chat_appearance_settings").performScrollToNode(hasTestTag("reply_speed_30"))
        rule.onNodeWithTag("reply_speed_30").performClick()
        rule.onNodeWithTag("chat_appearance_settings").performScrollToNode(hasTestTag("chat_style_plain"))
        rule.onNodeWithTag("chat_style_plain").performClick()
        rule.onNodeWithTag("chat_appearance_settings").performScrollToNode(hasText("你好，帮我整理一下今天的计划。"))
        val layouts=mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("你好，帮我整理一下今天的计划。").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(store.chat.value.fontSize,layouts.single().layoutInput.style.fontSize.value)
        assertEquals("plain",store.chat.value.style);assertEquals(30,store.chat.value.replySpeed)
        screenshot("chat-appearance-large-plain")
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.waitForIdle();assertFalse(open)
        val reloaded=AppearanceStore(context);assertEquals(store.chat.value,reloaded.chat.value)
        rule.runOnUiThread { displayedStore=reloaded;open=true };rule.waitForIdle()
        rule.onNodeWithTag("chat_appearance_settings").assertExists()
        rule.onNodeWithContentDescription("返回设置").performClick()
    }
}
