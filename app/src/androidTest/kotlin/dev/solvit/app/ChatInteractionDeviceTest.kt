package dev.solvit.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
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
        require(name.matches(Regex("[A-Za-z-]+")))
        val largeText = (InstrumentationRegistry.getArguments().getString("chatFontScale")?.toFloatOrNull() ?: 1f) > 1f
        val filename = (if (largeText) "large-text-" else "") + name
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$filename.png")).use { it.readBytes() }
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
        var phase = "prepare"
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
                val fontScale = InstrumentationRegistry.getArguments().getString("chatFontScale")?.toFloatOrNull()
                require(fontScale == null || fontScale in 1f..2f)
                rule.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                        MaterialTheme { ChatScreen(vm,updates,market,agents,workspaces) }
                    }
                }
            }
            rule.waitUntil(10_000) { vm.ready.value&&agents.activeRole.value==role }
            val original=vm.sessionId.value!!
            rule.onNodeWithTag("chat_role_title").assertTextEquals(role.name)
            fun send(text:String) {
                rule.onNodeWithTag("chat_input").performClick().performTextReplacement(text)
                rule.waitUntil(10_000) {
                    // Collection lookup avoids diagnostic tree traversal on the test thread.
                    val node=rule.onAllNodesWithTag("chat_composer").fetchSemanticsNodes().singleOrNull()
                        ?: return@waitUntil false
                    rule.runOnUiThread {
                        val decor=rule.activity.window.decorView
                        val insets=decor.rootWindowInsets
                        val ime=insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                        val composer=node.boundsInRoot
                        insets?.isVisible(WindowInsets.Type.ime())==true && ime>0 &&
                            composer.width>0 && composer.height>0 && composer.bottom<=decor.height-ime+1
                    }
                }
                rule.waitForIdle();rule.onNodeWithTag("chat_send").assertIsEnabled().performClick()
            }
            send("first")
            rule.waitUntil(10_000) { vm.toolApproval.value!=null }
            rule.onNodeWithTag("chat_tool_approval").assertIsDisplayed()
            assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
            screenshot("chat-approval-keyboard")
            // Exercise the missing combined state: waiting for approval while another message is queued.
            send("queued-during-approval")
            rule.waitUntil(10_000) { vm.queuedMessages.value.size==1 }
            val pendingQueueId=vm.queuedMessages.value.single().id
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("queue_toggle").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            screenshot("chat-approval-queue-keyboard")
            val regions=listOf("chat_role_title","chat_tool_approval","tool_allow_once","tool_allow_remember","chat_queue","queue_toggle","chat_composer").associateWith { tag ->
                val node=rule.onAllNodesWithTag(tag).fetchSemanticsNodes().single()
                rule.runOnUiThread { node.boundsInWindow }
            }
            android.util.Log.i("ChatDockFixture", "CHAT_DOCK_BOUNDS|"+regions.entries.joinToString { "${it.key}=${it.value}" })
            assertTrue("Approval must stay below the chat title",regions.getValue("chat_tool_approval").top>=regions.getValue("chat_role_title").bottom)
            rule.onNodeWithTag("tool_allow_once").assertIsDisplayed()
            rule.onNodeWithTag("tool_allow_remember").assertIsDisplayed()
            rule.onNodeWithTag("chat_send").assertIsDisplayed()
            val pendingName = rule.onNode(hasText("queue_fixture") and hasAnyAncestor(hasTestTag("chat_tool_approval")))
            pendingName.assertIsDisplayed()
            val nameLayouts = mutableListOf<TextLayoutResult>()
            pendingName.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
            assertEquals(1, nameLayouts.size)
            val nameBounds = pendingName.fetchSemanticsNode().boundsInWindow
            assertTrue("Pending tool name must show a complete line, not a clipped sliver: $nameBounds / ${nameLayouts.single().size}",
                nameBounds.height >= nameLayouts.single().size.height - 1)
            val density = rule.activity.resources.displayMetrics.density
            assertTrue("Queue header must retain a usable button, not a clipped strip: $regions",
                regions.getValue("queue_toggle").height >= 40 * density - 1)
            assertTrue(regions.getValue("queue_toggle").bottom <= regions.getValue("chat_composer").top)
            rule.onNodeWithTag("tool_approval_details").assertDoesNotExist()
            rule.onNodeWithTag("tool_details_toggle").performClick()
            rule.waitUntil(10_000) {
                rule.runOnUiThread { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false }
            }
            rule.onNodeWithTag("tool_approval_details").assertIsDisplayed()
                .performScrollToNode(hasTestTag("tool_approval_arguments"))
            rule.onNodeWithTag("tool_approval_arguments").assertIsDisplayed().assertTextEquals("{}")
            screenshot("chat-approval-expanded-arguments")
            val scopeWarning="不再询问仅用于此角色和当前版本的此工具，后续不同参数也可执行。可在设置 → 权限与执行方式清除。"
            rule.onNodeWithTag("tool_approval_details").performScrollToNode(hasText(scopeWarning))
            val scopeNode=rule.onNodeWithText(scopeWarning).assertIsDisplayed()
            val scopeLayouts=mutableListOf<TextLayoutResult>()
            scopeNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(scopeLayouts) }
            assertEquals(1, scopeLayouts.size)
            assertTrue("Authorization scope must be readable in full after scrolling",
                scopeNode.fetchSemanticsNode().boundsInWindow.height >= scopeLayouts.single().size.height - 1)
            screenshot("chat-approval-expanded-scope")
            assertEquals("Opening details must not authorize a tool", 1, requests.size)
            assertTrue(executed.isEmpty())
            assertNotNull(vm.toolApproval.value)
            assertTrue(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
            rule.onNodeWithTag("tool_details_toggle").performClick()
            rule.onNodeWithTag("tool_approval_details").assertDoesNotExist()
            rule.onNodeWithTag("chat_input").performClick()
            rule.waitUntil(10_000) {
                rule.runOnUiThread { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            }
            rule.onNodeWithTag("queue_toggle").assertIsDisplayed().performClick()
            rule.onNodeWithTag("chat_queue_items").performScrollToNode(hasTestTag("queue_menu_$pendingQueueId"))
            rule.onNodeWithTag("queue_menu_$pendingQueueId").performClick()
            rule.onNodeWithText("移除").performClick()
            rule.waitUntil(10_000) { vm.queuedMessages.value.isEmpty() }
            // The Flow value changes before the collected UI removes the queue and reflows
            // the approval card. Wait for that actual screen boundary before a touch.
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("chat_queue").fetchSemanticsNodes().isEmpty() }
            rule.waitForIdle()
            phase = "remembered-approval-click"
            val rememberButton = rule.onNodeWithTag("tool_allow_remember").assertIsDisplayed()
            val rememberSemantics = rememberButton.fetchSemanticsNode()
            val originalWindowCallback = rule.runOnUiThread { checkNotNull(rule.activity.window.callback) }
            // Fixture-only coordinates identify a stale test touch during native IME reflow.
            // Keep the exact original touch and result deadline; never retry authorization.
            rule.runOnUiThread {
                android.util.Log.i("ChatDockFixture", "CHAT_DOCK_TOUCH_BEFORE|bounds=${rememberSemantics.boundsInWindow}")
                rule.activity.window.callback = object : android.view.Window.Callback by originalWindowCallback {
                    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                            val bounds = runCatching { rememberSemantics.boundsInWindow }.getOrNull()
                            val insets = rule.activity.window.decorView.rootWindowInsets
                            android.util.Log.i("ChatDockFixture", "CHAT_DOCK_TOUCH|action=${event.actionMasked}|x=${event.x}|y=${event.y}|bounds=$bounds|ime=${insets?.isVisible(WindowInsets.Type.ime())}|imeBottom=${insets?.getInsets(WindowInsets.Type.ime())?.bottom}")
                        }
                        return originalWindowCallback.dispatchTouchEvent(event)
                    }
                }
            }
            try { rememberButton.performClick() }
            finally { rule.runOnUiThread { rule.activity.window.callback = originalWindowCallback } }
            phase = "remembered-approval-result"
            rule.waitUntil(10_000) { requests.size==2&&executed.size==1 }
            phase = "queue-edit-and-interrupt"
            send("same");send("same")
            rule.waitUntil(10_000) { vm.queuedMessages.value.size==2 }
            val queued=vm.queuedMessages.value
            rule.onNodeWithTag("chat_queue_items").assertDoesNotExist()
            rule.onNodeWithTag("chat_send").assertIsDisplayed()
            screenshot("chat-queue-collapsed-keyboard")
            rule.onNodeWithTag("queue_toggle").performClick()
            rule.onNodeWithTag("chat_queue_items").performScrollToNode(hasTestTag("queue_menu_${queued[0].id}"))
            rule.onNodeWithTag("queue_menu_${queued[0].id}").performClick()
            rule.onNodeWithText("编辑").performClick()
            rule.onNodeWithTag("queue_edit_input").performTextReplacement("edited")
            rule.onNode(hasText("保存") and hasAnyAncestor(hasTestTag("queued_${queued[0].id}"))).performClick()
            rule.onNodeWithTag("chat_queue_items").performScrollToNode(hasTestTag("queue_menu_${queued[1].id}"))
            rule.onNodeWithTag("queue_menu_${queued[1].id}").performClick()
            rule.onNodeWithText("移除").performClick()
            rule.onNodeWithTag("chat_queue_items").performScrollToNode(hasTestTag("queue_send_${queued[0].id}"))
            screenshot("chat-queue-expanded-keyboard")
            rule.onNodeWithTag("queue_send_${queued[0].id}").assertIsDisplayed().performClick()
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
        }catch(failure:Throwable) {
            // Fixture-only state distinguishes missed touch/reflow from a stuck gate or provider.
            // Never include prompts, tool arguments, exception messages or configured credentials.
            runCatching {
                val pending = vm.toolApproval.value
                android.util.Log.i("ChatDockFixture", "CHAT_DOCK_FAILURE|phase=$phase|requests=${requests.size}|executed=${executed.size}|queued=${vm.queuedMessages.value.size}|pending=${pending != null}|resolving=${pending?.resolving?.value}|decided=${pending?.decision?.isCompleted}|busy=${vm.busy.value}")
                listOf("tool_allow_remember", "queue_toggle", "chat_composer").forEach { tag ->
                    val nodes = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
                    nodes.forEach { node ->
                        val bounds = rule.runOnUiThread { node.boundsInWindow }
                        android.util.Log.i("ChatDockFixture", "CHAT_DOCK_FAILURE_BOUNDS|$tag=$bounds")
                    }
                }
            }
            runCatching { screenshot("chat-dock-layout-failure") }
            throw failure
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
        rule.onNodeWithTag("chat_font_size").performTouchInput {
            // Drag the displayed thumb (16 in 12..24), rather than beginning at the track edge.
            swipe(start=Offset(width/3f,center.y),end=Offset(width*.9f,center.y),durationMillis=400)
        }
        rule.waitUntil(8000) { store.chat.value.fontSize>20f }
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
