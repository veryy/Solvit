package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import dev.solvit.core.*
import dev.solvit.storage.SqliteChatStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import androidx.lifecycle.viewModelScope
import dev.solvit.chat.DefaultChatService
import org.json.JSONObject


/** Runs against the actual app and OS keyboard, not a mock inset value. */
class ChatLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        rule.waitUntil(30_000) { rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    private fun screenshot(name: String) {
        require(name.matches(Regex("[A-Za-z-]+")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    private fun openWorkspace() {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("工作区：",substring=true) and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("新建工作区").fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodes(hasText("工作区：",substring=true)).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(hasText("工作区：",substring=true)).performClick()
        } else {
            rule.onNodeWithText("新建工作区").performClick()
            rule.onNode(hasSetTextAction() and hasText("名称")).performTextInput("测试工作区")
            rule.onNodeWithText("创建并绑定").performClick()
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("shell_command").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun seedBranchConversation(app: SolvitApplication): ChatSession = runBlocking {
        val session = SqliteChatStore(app).use { store ->
            val value = store.createSession()
            store.appendBatch(listOf(
                ChatMessage("branch-user",value.id,Role.USER,"branch original question"),
                ChatMessage("branch-tool-call",value.id,Role.ASSISTANT,"",toolCalls=listOf(ToolCall("branch-call","clock","{}")),reasoning="branch thought"),
                ChatMessage("branch-result",value.id,Role.TOOL,"clock result",toolCallId="branch-call",toolStage=ToolStage.SUCCEEDED),
                ChatMessage("branch-final",value.id,Role.ASSISTANT,"branch original answer"),
                ChatMessage("branch-later-user",value.id,Role.USER,"later question"),
                ChatMessage("branch-later-answer",value.id,Role.ASSISTANT,"later answer")))
            value
        }
        app.ready.value!!.getOrThrow().createSession() // Refresh the application's storage Flow.
        session
    }
    private fun selectBranchFixture() {
        rule.onNodeWithContentDescription("对话历史").performClick()
        rule.onNodeWithText("branch original question").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("later answer").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun actualChatShowsPartialTokensBeforeCompletionAndFollowsLongReplyAboveKeyboard() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val advance = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val store = SqliteChatStore(app)
        val agent = object : AgentRuntime {
            override fun run(request: ModelRequest) = flow {
                emit(ModelChunk.Text("live-first-token"))
                advance.await()
                emit(ModelChunk.Text("\n" + (1..45).joinToString("\n") { "stream line $it" } + "\nlive-last-token"))
                finish.await()
                emit(ModelChunk.Done)
            }
        }
        lateinit var vm: ChatViewModel
        lateinit var agents: AgentLibraryViewModel
        lateinit var workspaces: WorkspaceViewModel
        lateinit var updates: UpdateViewModel
        lateinit var market: MarketplaceViewModel
        rule.runOnUiThread {
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey="fixture")
                override suspend fun save(config: ModelConfig) = Unit
                override suspend fun isConfigured() = true
            }
            vm = ChatViewModel(DefaultChatService(store,agent,EventBus()),settings)
            agents = AgentLibraryViewModel(app.roles,app.skills,app.marketplace)
            workspaces = WorkspaceViewModel(app.workspaces,app.pluginDrafts,app.terminals)
            updates = UpdateViewModel(app.updates,app.updateDownloader)
            market = MarketplaceViewModel(app.marketplace)
            rule.activity.setContent { MaterialTheme { ChatScreen(vm,updates,market,agents,workspaces) } }
        }
        try {
            ready()
            rule.onNode(hasSetTextAction()).performTextInput("stream test")
            rule.onNodeWithContentDescription("发送").performClick()
            rule.waitUntil(8000) { rule.onAllNodesWithText("live-first-token").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("live-first-token").assertIsDisplayed()
            assertTrue(vm.busy.value); assertFalse(finish.isCompleted)
            advance.complete(Unit)
            rule.waitUntil(8000) { vm.draft.value.contains("live-last-token") }
            rule.waitUntil(8000) { rule.onNodeWithTag("chat_tail").isDisplayed() }
            rule.onNodeWithTag("chat_tail").assertIsDisplayed()
            val composer = rule.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
            val tail = rule.onNodeWithTag("chat_tail").fetchSemanticsNode().boundsInRoot
            assertTrue(tail.bottom <= composer.top)
            assertTrue(vm.busy.value); assertFalse(finish.isCompleted)
            screenshot("chat-live-stream-before-done")
            finish.complete(Unit)
            rule.waitUntil(8000) { !vm.busy.value }
            rule.onNodeWithTag("streaming_body").assertDoesNotExist()
        } finally {
            rule.runOnUiThread { listOf(vm,agents,workspaces,updates,market).forEach { it.viewModelScope.cancel() } }
            store.close()
        }
    }
    @Test fun applyingRoleInSettingsPersistsAndNewChatInheritsItsPluginWhitelist() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val role = AgentRole("ui-role-switch", "UI role switch", "Answer as a Kotlin developer",pluginIds=setOf("dev.solvit.web-search"))
        runBlocking { app.roles.save(role) }
        try {
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("角色管理与 Skills").performClick()
            rule.onNodeWithText(role.name).performScrollTo()
            rule.onNode(hasText("用于此对话") and hasAnyAncestor(hasAnyDescendant(hasText(role.name)))).performClick()
            rule.waitUntil(8000) { rule.onAllNodes(hasText("已应用UI role switch",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("关闭").performClick()
            rule.onNodeWithContentDescription("返回聊天").performClick()
            rule.onNodeWithContentDescription("对话历史").performClick()
            rule.onNodeWithTag("new_chat").performClick()
            rule.waitUntil(8000) { rule.onAllNodesWithText("新建工作区").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("角色管理与 Skills").performClick()
            rule.onNodeWithText(role.name).performScrollTo()
            rule.onNodeWithText("✓ 当前使用").assertExists()
            screenshot("role-applied-new-chat")
        } finally {
            // Clear bindings before deleting a restricted role, so later fixtures stay usable.
            runBlocking {
                val store = SqliteChatStore(app)
                try { store.sessions().first().forEach { if (runCatching { app.roles.selected(it.id)?.id }.getOrNull() == role.id) app.roles.select(it.id,null) } }
                finally { store.close() }
                app.roles.delete(role.id)
            }
        }
    }
    @Test fun replyBranchCopiesToolTurnAndRoleAndWorkspaceWithoutChangingOriginal() {
        ready()
        val app=rule.activity.application as SolvitApplication
        val source=seedBranchConversation(app)
        val workspace=runBlocking {
            app.roles.save(AgentRole("branch-role","Branch role",pluginIds=emptySet()))
            app.roles.select(source.id,"branch-role")
            app.workspaces.create("Branch workspace").also {
                app.workspaces.createShell(it.id,"Shell 1"); app.workspaces.bind(source.id,it.id)
            }
        }
        try {
            selectBranchFixture()
            rule.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("message_actions_branch-final"))
            rule.onNodeWithTag("message_actions_branch-final").performClick()
            rule.onNodeWithText("从这里分支").performClick()
            rule.onNodeWithText("创建分支").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("从这条回复创建分支").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText("branch original answer").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithText("later answer").fetchSemanticsNodes().isEmpty() }
            val branch=runBlocking { SqliteChatStore(app).use { store ->
                val copy=store.sessions().first().single { it.originSessionId == source.id }
                assertEquals(4,store.history(copy.id).size); assertEquals(6,store.history(source.id).size)
                assertEquals("branch thought",store.history(copy.id)[1].reasoning)
                assertEquals("branch-call",store.history(copy.id)[2].toolCallId)
                assertEquals("branch-role",app.roles.selected(copy.id)!!.id)
                assertEquals(workspace.id,app.workspaces.bound(copy.id)!!.id)
                app.ready.value!!.getOrThrow().deleteSession(source.id)
                assertEquals(4,store.history(copy.id).size)
                copy
            } }
            rule.activityRule.scenario.recreate()
            ready()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("branch original answer").fetchSemanticsNodes().isNotEmpty() }
            screenshot("chat-reply-branch")
            runBlocking { app.ready.value!!.getOrThrow().deleteSession(branch.id) }
        } finally {
            runBlocking { app.ready.value!!.getOrThrow().deleteSession(source.id); app.roles.delete("branch-role") }
        }
    }
    @Test fun editingOldQuestionCreatesEmptyBranchAndPrefillsDraftWithoutSending() {
        ready()
        val app=rule.activity.application as SolvitApplication
        val source=seedBranchConversation(app)
        try {
            selectBranchFixture()
            rule.onNodeWithTag("chat_messages").performScrollToNode(hasTestTag("message_actions_branch-user"))
            rule.onNodeWithTag("message_actions_branch-user").performClick()
            rule.onNodeWithText("编辑并分支").performClick()
            rule.onNodeWithTag("branch_edit").performTextReplacement("edited branch question")
            rule.onNodeWithText("创建分支").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction() and hasText("edited branch question")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasSetTextAction()).assertTextEquals("edited branch question")
            runBlocking { SqliteChatStore(app).use { store ->
                val branch=store.sessions().first().single { it.originSessionId == source.id }
                assertTrue(store.history(branch.id).isEmpty())
                assertNull(app.workspaces.bound(branch.id))
                assertEquals(6,store.history(source.id).size)
                app.ready.value!!.getOrThrow().deleteSession(branch.id)
            } }
        } finally { runBlocking { app.ready.value!!.getOrThrow().deleteSession(source.id) } }
    }
    @Test fun toolsAndThinkingAreCollapsedInsideOneRenderedAssistantBubble() {
        ready()
        val call = ToolCall("clock-call", "dev_solvit_clock_current_time", "{}")
        val messages = listOf(
            ChatMessage("call", "s", Role.ASSISTANT, "", toolCalls = listOf(call), reasoning = "private reasoning details"),
            ChatMessage("result", "s", Role.TOOL, "actual native clock result", toolCallId = call.id, toolStage = ToolStage.SUCCEEDED),
            ChatMessage("answer", "s", Role.ASSISTANT, "**Rendered answer**"))
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme { ChatBubble(ChatRow("reply", messages = messages)) } } }
        rule.onAllNodesWithTag("assistant_message").assertCountEquals(1)
        rule.onNodeWithTag("tool_details").assertDoesNotExist()
        rule.onNodeWithTag("thinking_details").assertDoesNotExist()
        rule.onNodeWithText("Rendered answer").assertExists()
        rule.onNodeWithText("**Rendered answer**").assertDoesNotExist()
        rule.onNodeWithTag("tool_toggle").performClick()
        rule.onNodeWithText("actual native clock result").assertIsDisplayed()
        rule.onNodeWithTag("thinking_toggle").performClick()
        rule.onNodeWithText("private reasoning details").assertExists()
        screenshot("chat-inline-tools-thinking")
    }
    @Test fun configuredModelHidesSetupAndNewChatHasNoWorkspaceUntilCreation() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val previous = runBlocking { app.settings.load() }
        try {
            runBlocking { app.settings.save(previous) }
            rule.activityRule.scenario.recreate()
            ready()
            rule.onNodeWithContentDescription("对话历史").performClick()
            rule.onNodeWithTag("new_chat").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("新建工作区").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("配置你的模型").assertDoesNotExist()
            rule.onNode(hasText("工作区：", substring = true)).assertDoesNotExist()
            rule.onNodeWithContentDescription("新建对话").assertDoesNotExist()
            rule.onNodeWithContentDescription("设置").assertIsDisplayed()
            openWorkspace()
            rule.onNodeWithContentDescription("关闭工作区").performClick()
            rule.onNode(hasText("工作区：", substring = true)).assertIsDisplayed()
        } finally { runBlocking { app.settings.save(previous) } }
    }
    @Test fun startupFocusesInputAndFloatingComposerStaysAboveRealKeyboard() {
        ready()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.waitUntil(10_000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        // isVisible flips before the IME animation and Compose inset layout finish.
        rule.waitUntil(10_000) {
            val decor = rule.activity.window.decorView
            val inset = decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
            val composer = rule.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
            inset > 0 && composer.bottom <= decor.height - inset + 1
        }
        val bounds = rule.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
        val decor = rule.activity.window.decorView
        val ime = decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        assertTrue("Composer must float with margins", bounds.left > 0 && bounds.right < decor.width)
        assertTrue("Composer overlaps keyboard: $bounds, IME=$ime", bounds.bottom <= decor.height - ime + 1)
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
        rule.onNode(hasText("管理角色与 Skills", substring = true)).assertDoesNotExist()
        screenshot("chat-keyboard-floating")
    }
    @Test fun wideDrawerOpensSettingsAndLastSettingsRowCanBeSeen() {
        ready()
        rule.onNodeWithContentDescription("对话历史").performClick()
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("chat_drawer").fetchSemanticsNode().boundsInRoot
        assertEquals(rule.activity.window.decorView.width * 0.75f, bounds.width, 3f)
        rule.onNodeWithText("设置", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText("关于 Solvit"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。"))
        rule.onNodeWithText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。").performScrollTo()
        screenshot("settings-bottom-insets")
        rule.onNodeWithText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。").assertIsDisplayed()
    }
    @Test fun pluginPageCanReachBottomAndReturnToChat() {
        ready()
        rule.onNodeWithContentDescription("插件市场").performClick()
        rule.onNode(hasText("已安装 (", substring = true)).performClick()
        val last = (rule.activity.application as SolvitApplication).marketplace.installed.value.last().name
        rule.onNodeWithTag("plugins_list").performScrollToNode(hasText(last))
        rule.onNodeWithText(last).assertIsDisplayed()
        rule.onAllNodesWithText("卸载").onLast().assertIsDisplayed()
        screenshot("plugins-bottom-insets")
        rule.onNodeWithContentDescription("关闭插件页面").performClick()
        rule.onNodeWithTag("chat_composer").assertIsDisplayed()
    }
    @Test fun workspaceTerminalRunsRealAndroidShellInTheBoundWorkspace() {
        ready()
        openWorkspace()
        rule.onNodeWithTag("shell_command").performClick().performTextInput("printf hello-solvit")
        rule.onNodeWithText("运行").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("hello-solvit").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("hello-solvit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("＋ Shell").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Shell 2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Shell 2").assertIsDisplayed()
        screenshot("workspace-shell-real-process")
        rule.onNodeWithText("工作目录").performClick()
        rule.onNodeWithText("新建文本").assertIsDisplayed()
        rule.onNodeWithText("插件草稿", substring = true).assertIsDisplayed()
        screenshot("workspace-directory")
    }
    @Test fun javascriptPluginCanCreateShellAndRunActualAndroidCommand() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking(Dispatchers.IO) {
            val old = app.marketplace.installed.value.single { it.id == "dev.solvit.workspace" }.enabled
            app.systemExecution.setMode("dev.solvit.workspace", ExecutionMode.APP)
            try {
                if (!old) app.marketplace.enable("dev.solvit.workspace", setOf("workspace", "shell"))
                val workspace = app.workspaces.forChat("javascript-emulator-test")
                val authorization = object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor): Boolean {
                        assertEquals(ExecutionMode.APP, descriptor.executionMode)
                        return true
                    }
                }
                withContext(ConversationContext("javascript-emulator-test", workspace.id)) {
                    val shell = JSONObject(app.marketplace.runTool("dev_solvit_workspace_create_shell", """{"name":"JS Shell"}""", authorization)).getString("id")
                    val job = JSONObject(app.marketplace.runTool("dev_solvit_workspace_shell_start", JSONObject()
                        .put("shellId", shell).put("command", "printf js-native-shell").toString(), authorization)).getString("id")
                    val result = withTimeout(10_000) { app.workspaces.jobs.first { list -> list.any { it.id == job && it.stage == ShellStage.COMPLETED } } }.single { it.id == job }
                    assertEquals("js-native-shell", result.output.joinToString("") { it.text })
                    assertEquals("dev.solvit.workspace", result.owner)
                    assertEquals(workspace.id, result.workspaceId)
                    assertEquals(shell, result.shellSessionId)
                }
            } finally { if (!old) app.marketplace.disable("dev.solvit.workspace") }
        }
    }
    @Test fun javascriptPluginUsesPersistentPtyAndDisableClosesItsProcess() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking(Dispatchers.IO) {
            val plugin = "dev.solvit.terminal"
            app.systemExecution.setMode(plugin, ExecutionMode.APP)
            val old = app.marketplace.installed.value.single { it.id == plugin }.enabled
            try {
                if (!old) app.marketplace.enable(plugin, setOf("workspace", "shell"))
                val workspace = app.workspaces.forChat("javascript-pty-emulator-test")
                val shell = app.workspaces.state.value.shells.first { it.workspaceId == workspace.id }
                val authorization = object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor): Boolean {
                        assertEquals(ExecutionMode.APP, descriptor.executionMode); return true
                    }
                }
                withContext(ConversationContext("javascript-pty-emulator-test", workspace.id) + AgentExecutionContext()) {
                    suspend fun invoke(name: String, args: JSONObject) = JSONObject(app.marketplace.runTool("dev_solvit_terminal_$name", args.toString(), authorization))
                    val id = invoke("terminal_open", JSONObject().put("shellId", shell.id)).getString("id")
                    assertEquals(id, invoke("terminal_open", JSONObject().put("shellId", shell.id)).getString("id"))
                    invoke("terminal_input", JSONObject().put("id",id).put("text","stty -echo; export JS_PTY_STATE=retained; printf '__JS_%s__\\n' READY\n"))
                    withTimeout(8000) { app.terminals.sessions.first { list -> list.any { it.id == id && "__JS_READY__" in it.output } } }
                    invoke("terminal_input", JSONObject().put("id",id).put("text","read answer; printf 'js-result:%s:%s\\n' \"\$JS_PTY_STATE\" \"\$answer\"\n"))
                    invoke("terminal_input", JSONObject().put("id",id).put("text","interactive\n"))
                    withTimeout(8000) { app.terminals.sessions.first { list -> list.any { it.id == id && "js-result:retained:interactive" in it.output } } }
                    val status = invoke("terminal_status",JSONObject().put("id",id))
                    assertTrue(status.getString("output").contains("js-result:retained:interactive"))
                    assertTrue(status.getBoolean("active"))
                    app.marketplace.disable(plugin)
                    assertFalse(app.terminals.sessions.value.single { it.id == id }.active)
                }
            } finally {
                if (app.marketplace.installed.value.single { it.id == plugin }.enabled) app.marketplace.disable(plugin)
                if (old) app.marketplace.enable(plugin, setOf("workspace", "shell"))
            }
        }
    }
    @Test fun interactiveTerminalUiKeepsEnvironmentAndDisplaysOutput() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking { app.systemExecution.setMode(TERMINAL_OWNER,ExecutionMode.APP) }
        openWorkspace()
        rule.onNodeWithText("交互终端").performClick()
        rule.onNodeWithTag("shell_command").performTextInput("stty -echo; export UI_PTY_STATE=retained; printf '__UI_%s__\\n' READY")
        rule.onNodeWithText("发送输入").assertIsEnabled().performClick()
        rule.waitUntil(8000) { rule.onAllNodes(hasText("__UI_READY__", substring=true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("shell_command").performClick().performTextInput("printf 'ui-result:%s\\n' \"\$UI_PTY_STATE\"")
        try {
            rule.waitUntil(8000) { rule.onAllNodes(hasText("发送输入") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(8000) { rule.onNodeWithText("发送输入").isDisplayed() }
            rule.onNodeWithText("发送输入").assertIsDisplayed().performClick()
            rule.waitUntil(8000) { rule.onAllNodes(hasText("ui-result:retained", substring=true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Exception) {
            screenshot("workspace-interactive-failure")
            throw AssertionError("PTY UI state=" + app.terminals.sessions.value.filter { it.owner == TERMINAL_OWNER } +
                "\n" + rule.onRoot().printToString(), e)
        }
        rule.onNodeWithTag("terminal_output").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Ctrl-C").performScrollTo().assertIsDisplayed()
        screenshot("workspace-interactive-pty")
        rule.onNodeWithText("关闭终端").performScrollTo().performClick()
        rule.waitUntil(8000) { rule.onAllNodes(hasText("终端已关闭 ·", substring=true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
