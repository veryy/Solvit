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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import androidx.lifecycle.viewModelScope
import dev.solvit.chat.DefaultChatService
import dev.solvit.chat.ToolAgentRuntime
import org.json.JSONObject


/** Runs against the actual app and OS keyboard, not a mock inset value. */
class ChatLayoutTest {
    private data class Geometry(val bounds: androidx.compose.ui.geometry.Rect, val width: Int,
        val height: Int, val ime: Int, val keyboardVisible: Boolean)
    private fun geometry(tag: String): Geometry {
        // Collection lookup avoids fetchOneOrThrow's diagnostic tree dump on the test thread.
        val node = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().single()
        return rule.runOnUiThread {
            val decor = rule.activity.window.decorView
            val insets = decor.rootWindowInsets
            Geometry(node.boundsInRoot, decor.width, decor.height,
                insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0,
                insets?.isVisible(WindowInsets.Type.ime()) == true)
        }
    }

    private fun scrollSettingsToLanguage() {
        // Use the list's public scroll action on the UI thread, without 1.7.6's
        // off-thread diagnostic traversal or a system swipe's asynchronous fling.
        repeat(12) {
            rule.waitForIdle()
            val list = geometry("settings_list")
            val target = rule.onAllNodesWithTag("language_settings").fetchSemanticsNodes().singleOrNull()
            val visible = rule.runOnUiThread {
                target?.boundsInRoot?.let { bounds ->
                    bounds.width > 0 && bounds.height > 0 && bounds.top >= list.bounds.top &&
                        bounds.bottom <= list.bounds.bottom
                } == true
            }
            if (visible) return
            val container = rule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().single()
            rule.runOnUiThread {
                val action = container.config[androidx.compose.ui.semantics.SemanticsActions.ScrollBy].action
                check(action?.invoke(0f, list.bounds.height * .7f) == true) { "Settings did not accept scrolling" }
            }
        }
        fail("Language settings were not fully visible after scrolling")
    }

    @Test fun actualApplicationCompactsOldHistoryBeforeRequestAndRetainsOriginalChat() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking {
            val chat = app.ready.value!!.getOrThrow(); val session = chat.createSession()
            val role = AgentRole("context-" + java.util.UUID.randomUUID(), "Context fixture")
            var summaryCalls = 0; var mainCalls = 0
            val provider = object : ModelProvider {
                override val id = "android-context-fixture"
                override fun stream(request: ModelRequest): Flow<ModelChunk> {
                    if (request.config.systemPrompt.contains("旧对话压缩")) {
                        summaryCalls++; assertTrue(request.tools.isEmpty())
                        return kotlinx.coroutines.flow.flowOf(ModelChunk.Text("Previous tasks completed. Preserve remaining task."), ModelChunk.Done)
                    }
                    mainCalls++
                    assertTrue(request.contextMeasurement!!.fits)
                    if (mainCalls == 4) assertTrue(request.messages.any { it.content.contains("旧聊天历史摘要") })
                    return kotlinx.coroutines.flow.flowOf(ModelChunk.Text(if (mainCalls < 4) "answer" + "x".repeat(2_300) else "final answer"), ModelChunk.Done)
                }
            }
            val unregister = app.modelProviders.register(ModelProviderInfo(provider.id, "Context fixture", "https://example.com", "fixture"), provider)
            try {
                app.roles.save(role); app.roles.select(session.id, role.id)
                val config = ModelConfig(providerId = provider.id, contextBudget = ContextBudgetPolicy(8_192, 1_024, 512))
                repeat(4) { chat.send(session.id, "task $it", config).toList() }
                assertEquals(4, mainCalls); assertTrue(summaryCalls > 0)
                val summary = app.contextPreparation.summary(session.id)
                assertNotNull(summary)
                assertEquals(8, chat.messages(session.id).first().size)
                assertTrue(chat.messages(session.id).first().any { it.content.length > 2_300 })
                assertFalse(app.requestInspection.latest.value.toString().contains("Previous tasks"))
            } finally { unregister(); chat.deleteSession(session.id); app.roles.delete(role.id) }
        }
    }

    @Test fun memoryManagementEditsAndDeletesWithPluginDisabled() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val manager = dev.solvit.market.BuiltinMemoryManager(app)
        val id = runBlocking {
            app.marketplace.disable("dev.solvit.memory")
            manager.save("memory-ui-original", "fact", emptyList()).getValue("fact").let {
                kotlinx.serialization.json.Json.parseToJsonElement(it.toString()).let { element ->
                    (element as kotlinx.serialization.json.JsonObject).getValue("id").toString().trim('"')
                }
            }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme { MemoryManagementScreen(manager, app.postTurnWorker) {} } } }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("memory-ui-original").fetchSemanticsNodes().isNotEmpty() }
            rule.onNode(hasText("修改") and hasAnyAncestor(hasTestTag("memory_fact_$id"))).performClick()
            rule.onNodeWithTag("memory_edit_text").performTextReplacement("memory-ui-edited")
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("memory-ui-edited").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
            rule.onNode(hasText("删除") and hasAnyAncestor(hasTestTag("memory_fact_$id"))).performClick()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("memory-ui-edited").assertIsDisplayed()
            rule.onNode(hasText("删除") and hasAnyAncestor(hasTestTag("memory_fact_$id"))).performClick()
            rule.onNodeWithText("确认删除").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("memory_fact_$id").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("memory_cleanup").performClick()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithTag("memory_cleanup").performClick()
            rule.onNodeWithText("确认清理").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("memory_cleanup") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
            screenshot("memory-management-disabled-plugin")
        } finally {
            runBlocking { runCatching { manager.delete(id) } }
        }
    }

    @Test fun memoryTaskRetryRequiresConfirmationAndDisplaysSource() {
        ready()
        val target = PostTurnTarget("dev.solvit.memory", "1.2.0", "a".repeat(64))
        val turn = CompletedChatTurn("fixture-answer", "fixture-session", "fixture-user", "fixture-answer",
            "coder", "Coder", setOf(target.pluginId), null, "fixture", "fixture-model", 1, listOf(target))
        var record = PostTurnJob("fixture-job", turn, target, PostTurnJobStatus.FAILED, 1, 2, PostTurnFailure.MODEL)
        var retries = 0
        var retired = false
        val store = object : ChatStore {
            override fun sessions() = kotlinx.coroutines.flow.flowOf(emptyList<ChatSession>())
            override fun messages(sessionId: String) = kotlinx.coroutines.flow.flowOf(emptyList<ChatMessage>())
            override suspend fun createSession(): ChatSession = error("Not a chat operation")
            override suspend fun history(sessionId: String) = emptyList<ChatMessage>()
            override suspend fun append(message: ChatMessage) = error("Not a chat operation")
            override suspend fun deleteSession(sessionId: String) = error("Not a chat operation")
            override suspend fun recentPostTurnJobs(before: PostTurnJobCursor?, limit: Int) = if (retired) emptyList() else listOf(record)
            override suspend fun deletePostTurnJob(id: String, expectedAttempt: Int, expectedStatus: PostTurnJobStatus): Boolean {
                assertEquals(record.id, id); assertEquals(1, expectedAttempt); assertEquals(PostTurnJobStatus.WAITING, expectedStatus)
                retired = true; return true
            }
            override suspend fun retryPostTurnJob(id: String, expectedAttempt: Int, now: Long): Boolean {
                assertEquals(record.id, id); assertEquals(1, expectedAttempt)
                retries++; record = record.copy(status = PostTurnJobStatus.WAITING, failure = null)
                return true
            }
        }
        val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest): Flow<ModelChunk> = error("UI must not invoke model")
        }
        val worker = dev.solvit.chat.PostTurnWorker(store, PostTurnHookRegistry(), provider, { null }, { null }, taskScope)
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme { MemoryTasksScreen(worker) {} } } }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("处理失败").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("模型请求失败。").assertIsDisplayed()
            rule.onNodeWithText("查看详情").performClick()
            rule.onNodeWithTag("memory_tasks").performScrollToNode(hasText("来源消息：fixture-user"))
            rule.onNodeWithText("来源消息：fixture-user").assertIsDisplayed()
            rule.onNodeWithText("重试").performClick()
            assertEquals(0, retries)
            rule.onNodeWithText("取消").performClick()
            assertEquals(0, retries)
            rule.onNodeWithText("重试").performClick()
            rule.onNodeWithText("确认重试").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("等待处理").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, retries)
            screenshot("memory-task-confirmed-retry")
            rule.onNodeWithText("删除任务").performClick()
            rule.onNodeWithText("取消").performClick()
            assertFalse(retired)
            rule.onNodeWithText("删除任务").performClick()
            rule.onNodeWithText("确认删除").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("暂无自动记忆任务").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(retired)

        } finally { taskScope.cancel() }
    }

    @Test fun contextBudgetDialogValidatesAndReturnsSavedValues() {
        ready()
        var saved: ContextBudgetPolicy? = null
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme { ContextBudgetDialog(ContextBudgetPolicy(), {}) { saved = it } } } }
        rule.onNodeWithTag("context_window").performTextReplacement("4096")
        rule.onNodeWithTag("save_context_budget").assertIsNotEnabled()
        rule.onNodeWithTag("context_output").performTextReplacement("512")
        rule.onNodeWithTag("context_automatic").performClick()
        rule.onNodeWithTag("save_context_budget").performClick()
        assertEquals(ContextBudgetPolicy(4_096, 512, 2_048, false), saved)
    }
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
    private fun openWorkspace(commandJobs: Boolean = true) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("工作区：",substring=true) and isEnabled()).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodes(hasText("新建工作区") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        if (rule.onAllNodes(hasText("工作区：",substring=true) and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
            rule.onNode(hasText("工作区：",substring=true) and isEnabled()).performClick()
        } else {
            rule.onNode(hasText("新建工作区") and isEnabled()).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("workspace_create_name").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("workspace_create_name").performTextInput("测试工作区")
            rule.onNodeWithText("创建并绑定").performClick()
        }
        // These callers test command jobs, so explicitly select that retained mode.
        // The separate real-PTY test verifies the default remains interactive.
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("workspace_terminal_mode").fetchSemanticsNodes().isNotEmpty() }
        if(commandJobs) {
            if(rule.onAllNodesWithText("命令任务").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithTag("workspace_terminal_mode").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("shell_command").fetchSemanticsNodes().isNotEmpty() }
        }
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
        try {
            rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("chat_drawer")))
                .performScrollToNode(hasText("branch original question"))
            rule.onNodeWithText("branch original question").assertIsDisplayed().performClick()
        } catch (failure:AssertionError) {
            throw AssertionError("Branch history before selection:\n"+rule.onRoot(useUnmergedTree=true).printToString(),failure)
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("later answer").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun actualAndroidTsProjectRulesRefreshWithoutChangingRoleOrCreatingUnboundWorkspace() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking {
            val id = "dev.solvit.workspace"
            val wasEnabled = app.marketplace.installed.value.single { it.id == id }.enabled
            val configuration = app.marketplace.configuration(id)
            val chat = app.ready.value!!.getOrThrow()
            val role = AgentRole("project-rules-" + java.util.UUID.randomUUID(), "Project identity", "Retain this role identity", setOf(id))
            var session: ChatSession? = null
            var unregister: (() -> Unit)? = null
            val requests = mutableListOf<ModelRequest>()
            try {
                if (wasEnabled) app.marketplace.disable(id)
                app.marketplace.configure(id, mapOf("projectRules" to "true"))
                app.marketplace.enable(id, setOf("workspace", "shell"))
                assertEquals("1.10.0", app.marketplace.installed.value.single { it.id == id }.version)
                val provider = object : ModelProvider {
                    override val id = "android-project-rules-fixture"
                    override fun stream(request: ModelRequest): Flow<ModelChunk> {
                        requests += request
                        return kotlinx.coroutines.flow.flowOf(ModelChunk.Text("fixture response"), ModelChunk.Done)
                    }
                }
                unregister = app.modelProviders.register(ModelProviderInfo(provider.id, "Rules fixture", "https://example.com", "fixture"), provider)
                app.roles.save(role); session = chat.createSession(); app.roles.select(session!!.id, role.id)
                val workspace = app.workspaces.create("Project rule device fixture")
                app.workspaces.bind(session!!.id, workspace.id)
                val text = "DEVICE_FIRST_RULE\n" + "安卓规则🙂".repeat(500)
                assertTrue(text.toByteArray().size > 6 * 1024)
                app.workspaces.write(workspace.id, "AGENTS.md", text)
                suspend fun send() {
                    val chunks = chat.send(session!!.id, "Inspect project rules", ModelConfig(providerId = provider.id)).toList()
                    assertTrue(chunks.none { it is ModelChunk.ToolActivity })
                    assertTrue(requests.last().contextProblems.toString(), requests.last().contextProblems.isEmpty())
                    val system = requests.last().messages.single { it.role == Role.SYSTEM }.content
                    assertTrue(system.contains(role.name)); assertTrue(system.contains(role.prompt)); assertFalse(system.contains("DEVICE_"))
                }
                send()
                assertTrue(requests.last().messages.last { it.role == Role.USER }.content.contains("DEVICE_FIRST_RULE"))
                assertTrue(requests.last().messages.last { it.role == Role.USER }.content.contains("安卓规则🙂"))
                app.workspaces.write(workspace.id, "AGENTS.md", "DEVICE_CHANGED_RULE")
                send()
                assertTrue(requests.last().messages.last { it.role == Role.USER }.content.contains("DEVICE_CHANGED_RULE"))
                assertFalse(requests.last().messages.any { it.content.contains("DEVICE_FIRST_RULE") })
                assertTrue(java.io.File(app.workspaces.location(workspace.id), "AGENTS.md").delete())
                send(); assertFalse(requests.last().messages.any { it.content.contains("DEVICE_CHANGED_RULE") })
                app.workspaces.unbind(session!!.id); send(); assertNull(app.workspaces.bound(session!!.id))
                assertEquals(4, requests.size)
                assertFalse(chat.messages(session!!.id).first().any { it.content.contains("DEVICE_FIRST_RULE") || it.content.contains("DEVICE_CHANGED_RULE") })
            } finally {
                unregister?.invoke()
                session?.let { chat.deleteSession(it.id) }; app.roles.delete(role.id)
                if (app.marketplace.installed.value.single { it.id == id }.enabled) app.marketplace.disable(id)
                app.marketplace.configure(id, configuration)
                if (wasEnabled) app.marketplace.enable(id, setOf("workspace", "shell"))
            }
        }
    }

    @Test fun actualAndroidJsMemoryReachesPreparedModelRequestWithoutSearchToolCalls() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking {
            val id = "dev.solvit.memory"
            val original = java.io.File(app.filesDir, "extensions/$id.solvit.json").readBytes()
            val wasEnabled = app.marketplace.installed.value.single { it.id == id }.enabled
            val configuration = app.marketplace.configuration(id)
            val chat = app.ready.value!!.getOrThrow()
            val role = AgentRole("memory-" + java.util.UUID.randomUUID(), "Recall fixture", pluginIds = setOf(id))
            val allow = object : ToolAuthorization { override suspend fun authorize(descriptor: ToolDescriptor) = true }
            var session: ChatSession? = null
            var record: String? = null
            var unregister: (() -> Unit)? = null
            try {
                if (wasEnabled) app.marketplace.disable(id)
                app.marketplace.installLocal(app.assets.open("builtin-plugins/$id.solvit.json").use { it.readBytes() })
                app.marketplace.configure(id, mapOf("autoRecall" to "true"))
                app.marketplace.enable(id, setOf("storage", "model"))
                record = JSONObject(app.marketplace.runTool("dev_solvit_memory_memory_put",
                    """{"text":"My preferred editor is Emacs","category":"preference"}""", allow)).getJSONObject("fact").getString("id")
                var requests = 0
                val provider = object : ModelProvider {
                    override val id = "android-memory-fixture"
                    override fun stream(request: ModelRequest): Flow<ModelChunk> {
                        requests++
                        assertFalse(request.messages.single { it.role == Role.SYSTEM }.content.contains("My preferred editor is Emacs"))
                        assertTrue(request.messages.single { it.role == Role.USER }.content.contains("My preferred editor is Emacs"))
                        assertTrue(request.promptSources.any { it.source.contains("dev.solvit.memory") })
                        assertFalse(app.requestInspection.latest.value.toString().contains("Emacs"))
                        return kotlinx.coroutines.flow.flowOf(ModelChunk.Text("You prefer Emacs"), ModelChunk.Done)
                    }
                }
                unregister = app.modelProviders.register(ModelProviderInfo(provider.id, "Memory fixture", "https://example.com", "fixture"), provider)
                app.roles.save(role)
                session = chat.createSession(); app.roles.select(session!!.id, role.id)
                val chunks = chat.send(session!!.id, "What is my preferred editor?", ModelConfig(providerId = provider.id), allow).toList()
                assertEquals(1, requests)
                assertTrue(chunks.none { it is ModelChunk.ToolActivity })
                assertTrue(chunks.filterIsInstance<ModelChunk.Text>().single().value.contains("Emacs"))
            } finally {
                unregister?.invoke()
                session?.let { chat.deleteSession(it.id) }; app.roles.delete(role.id)
                if (record != null && app.marketplace.installed.value.single { it.id == id }.enabled)
                    app.marketplace.runTool("dev_solvit_memory_memory_delete", JSONObject().put("id", record).toString(), allow)
                if (app.marketplace.installed.value.single { it.id == id }.enabled) app.marketplace.disable(id)
                app.marketplace.installLocal(original); app.marketplace.configure(id, configuration)
                if (wasEnabled) app.marketplace.enable(id, setOf("storage", "model"))
            }
        }
    }

    @Test fun nativeMemoryRecallSwitchSavesAndReopensWithoutTextEntry() {
        ready()
        val extension = InstalledExtension(id = "dev.solvit.memory", name = "本地记忆", version = "1.1.0",
            permissions = setOf("storage"), enabled = false, tools = emptyList(),
            settings = listOf(ExtensionSetting("autoRecall", "自动将相关记忆用于聊天", "boolean", defaultValue = "true")), contextHooks = setOf("beforeModel"))
        val saved = MutableStateFlow(mapOf("autoRecall" to "true"))
        val service = object : MarketplaceService {
            override val installed = MutableStateFlow(listOf(extension))
            override suspend fun catalog() = emptyList<MarketEntry>()
            override suspend fun install(entry: MarketEntry) = error("not used")
            override suspend fun enable(id: String, grantedPermissions: Set<String>) = error("not used")
            override suspend fun disable(id: String) = error("not used")
            override suspend fun uninstall(id: String) = error("not used")
            override suspend fun configuration(id: String) = saved.value
            override suspend fun configure(id: String, values: Map<String, String>) { saved.value = values.toMap() }
            override suspend fun runTool(name: String, argumentsJson: String, authorization: ToolAuthorization) = error("not used")
        }
        lateinit var vm: MarketplaceViewModel
        rule.runOnUiThread {
            vm = MarketplaceViewModel(service)
            rule.activity.setContent { MaterialTheme { PluginMarketplaceDialog(vm) {} } }
            vm.openConfiguration(extension)
        }
        try {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_setting_autoRecall").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_setting_autoRecall").assertIsOn().performClick().assertIsOff()
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil(10_000) { vm.configuring.value == null }
            assertEquals("false", saved.value["autoRecall"])
            rule.runOnUiThread { vm.openConfiguration(extension) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_setting_autoRecall").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_setting_autoRecall").assertIsOff()
        } finally { rule.runOnUiThread { vm.viewModelScope.cancel() } }
    }

    @Test fun actualChatShowsPartialTokensBeforeCompletionAndFollowsLongReplyAboveKeyboard() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val advance = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        // A pending model must not hold a second helper on the app's shared database.
        // The real runtime and UI still run; storage is replaced through its interface.
        val store = object : ChatStore {
            val stored = MutableStateFlow<List<ChatMessage>>(emptyList())
            val session = ChatSession("stream-fixture", "Stream fixture", 0)
            override fun sessions() = flow { emit(listOf(session)) }
            override fun messages(sessionId: String) = stored.map { list -> list.filter { it.sessionId == sessionId } }
            override suspend fun createSession() = session
            override suspend fun history(sessionId: String) = stored.value.filter { it.sessionId == sessionId }
            override suspend fun append(message: ChatMessage) { stored.value += message }
            override suspend fun appendBatch(messages: List<ChatMessage>) { stored.value += messages }
            override suspend fun deleteSession(sessionId: String) { stored.value = stored.value.filterNot { it.sessionId == sessionId } }
        }
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest) = flow {
                emit(ModelChunk.Text("**live-first-token**"))
                advance.await()
                emit(ModelChunk.Text("\n" + (1..45).joinToString("\n") { "stream line $it" } + "\nlive-last-token"))
                finish.await()
                emit(ModelChunk.Done)
            }
        }
        val optionalHooks = ContextHookRegistry().apply {
            register("test.broken", ContextHook { error("PRIVATE_SECRET_SENTINEL") })
        }
        val driver = dev.solvit.plugins.ScriptAgentDriver(app.assets.open("agent/chat-driver.js").bufferedReader().use { it.readText() })
        val agent = ToolAgentRuntime(provider, ToolRegistry(EventBus()), hooks = optionalHooks, inspection = app.requestInspection, driver = driver)
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
            rule.onNodeWithText("**live-first-token**").assertDoesNotExist()
            assertTrue(vm.busy.value); assertFalse(finish.isCompleted)
            advance.complete(Unit)
            rule.waitUntil(20000) { vm.draft.value.contains("live-last-token") }
            rule.waitUntil(8000) { rule.onNodeWithTag("chat_tail").isDisplayed() }
            rule.onNodeWithTag("chat_tail").assertIsDisplayed()
            val composer = geometry("chat_composer").bounds
            val tail = geometry("chat_tail").bounds
            assertTrue(tail.bottom <= composer.top)
            assertTrue(vm.busy.value); assertFalse(finish.isCompleted)
            screenshot("chat-live-stream-before-done")
            finish.complete(Unit)
            rule.waitUntil(8000) { !vm.busy.value }
            rule.onNodeWithTag("streaming_body").assertDoesNotExist()
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithTag("settings_list").performScrollToIndex(6)
            rule.onNodeWithText("最近请求诊断").performClick()
            rule.onNodeWithText("实际角色：Solvit").assertIsDisplayed()
            rule.onNodeWithTag("request_diagnostics").performScrollToNode(hasText("插件资料未获得：test.broken"))
            rule.onNodeWithText("插件资料未获得：test.broken").assertIsDisplayed()
            rule.onNodeWithText("读取失败").assertIsDisplayed()
            rule.onNodeWithText("PRIVATE_SECRET_SENTINEL").assertDoesNotExist()
            rule.onNodeWithTag("request_diagnostics").performScrollToNode(hasText("提供的工具数：0"))
            rule.onNodeWithText("提供的工具数：0").assertExists()
            rule.onNodeWithTag("request_diagnostics").performScrollToNode(hasText("首段文字显示：", substring = true))
            rule.onNode(hasText("首段文字显示：", substring = true)).assertExists()
            screenshot("request-diagnostics")
        } catch (e: Throwable) {
            screenshot("chat-live-stream-failure")
            throw AssertionError(rule.onRoot().printToString(), e)
        } finally {
            advance.complete(Unit); finish.complete(Unit)
            rule.runOnUiThread { vm.stop() }
            rule.waitUntil(8000) { !vm.busy.value }
            rule.runOnUiThread { listOf(vm,agents,workspaces,updates,market).forEach { it.viewModelScope.cancel() } }
        }
    }
    @Test fun roleAppliedThroughSettingsReachesProviderAndNewChatRequest() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val previousDefault = runBlocking { app.roles.forNewChat(null) }
        val role = AgentRole("ui-request-" + java.util.UUID.randomUUID(), "星辰请求验证", "专门检查 Kotlin 代码，先说明检查依据。")
        runBlocking { app.roles.save(role) }
        val sessions = MutableStateFlow(listOf(ChatSession("role-chat-" + java.util.UUID.randomUUID(), "Role fixture", 0)))
        val saved = MutableStateFlow<List<ChatMessage>>(emptyList())
        val store = object : ChatStore {
            override fun sessions() = sessions
            override fun messages(sessionId: String) = saved.map { it.filter { message -> message.sessionId == sessionId } }
            override suspend fun history(sessionId: String) = saved.value.filter { it.sessionId == sessionId }
            override suspend fun createSession(): ChatSession {
                val session = ChatSession("role-chat-" + java.util.UUID.randomUUID(), "New role fixture", 0)
                sessions.value = listOf(session) + sessions.value
                return session
            }
            override suspend fun append(message: ChatMessage) { saved.value += message }
            override suspend fun appendBatch(messages: List<ChatMessage>) { saved.value += messages }
            override suspend fun deleteSession(sessionId: String) { saved.value = saved.value.filterNot { it.sessionId == sessionId } }
        }
        val requests = java.util.concurrent.ConcurrentLinkedQueue<ModelRequest>()
        val provider = object : ModelProvider {
            override val id = "fixture"
            override fun stream(request: ModelRequest) = flow {
                requests.add(request)
                // This response does not pretend to validate real-model role adherence.
                emit(ModelChunk.Text("fixture response")); emit(ModelChunk.Done)
            }
        }
        lateinit var vm: ChatViewModel
        lateinit var agents: AgentLibraryViewModel
        lateinit var workspaces: WorkspaceViewModel
        lateinit var updates: UpdateViewModel
        lateinit var market: MarketplaceViewModel
        rule.runOnUiThread {
            val settings = object : SettingsStore {
                override suspend fun load() = ModelConfig(apiKey = "fixture")
                override suspend fun save(config: ModelConfig) = Unit
                override suspend fun isConfigured() = true
            }
            vm = ChatViewModel(DefaultChatService(store, ToolAgentRuntime(provider, ToolRegistry(EventBus())),
                EventBus(), app.roles), settings, app.roles)
            agents = AgentLibraryViewModel(app.roles, app.skills, app.marketplace)
            workspaces = WorkspaceViewModel(app.workspaces, app.pluginDrafts, app.terminals)
            updates = UpdateViewModel(app.updates, app.updateDownloader)
            market = MarketplaceViewModel(app.marketplace)
            rule.activity.setContent { MaterialTheme { ChatScreen(vm, updates, market, agents, workspaces) } }
        }
        var tapBounds = ""
        fun tapRoleSend() {
            // Android marks IME visible before Compose has moved the composer. A physical
            // tap during that interval hits its old position and never invokes send().
            // Keep the real tap and all request/context assertions; wait for the real layout.
            rule.waitUntil(10_000) {
                val sample = geometry("chat_composer")
                sample.keyboardVisible && sample.ime > 0 && sample.bounds.bottom <= sample.height - sample.ime + 1
            }
            rule.waitForIdle()
            val button = rule.onNodeWithTag("chat_send").assertIsEnabled()
            val before = geometry("chat_send").bounds
            button.performClick()
            tapBounds = "before=$before after=${geometry("chat_send").bounds}"
        }
        fun awaitRoleRequest(expected: Int) {
            try {
                rule.waitUntil(8000) { requests.size == expected && !vm.busy.value }
            } catch (failure: Throwable) {
                // Failure details contain fixture state only, without credentials or remote errors.
                val state = "Role request expected=$expected actual=${requests.size} ready=${vm.ready.value} " +
                    "busy=${vm.busy.value} hasError=${vm.error.value != null} " +
                    "agentBusy=${agents.busy.value} missingRole=${agents.missingRole.value} " +
                    "branching=${vm.branching.value} invalidConfig=${vm.config.value.validate() != null} " +
                    "outcome=${vm.replyTiming.value?.outcome} saved=${saved.value.size} $tapBounds"
                screenshot("role-settings-to-provider-failure")
                throw AssertionError(state + "\n" + rule.onRoot().printToString(), failure)
            }
        }
        try {
            ready()
            rule.waitUntil(8000) { vm.ready.value && agents.activeRole.value == null }
            val originalSession = vm.sessionId.value!!
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("角色管理与 Skills").performClick()
            rule.onNodeWithText(role.name).performScrollTo()
            rule.onNodeWithTag("apply_role_${role.id}").performClick()
            rule.waitUntil(8000) { !agents.busy.value && agents.activeRole.value?.id == role.id }
            rule.onNodeWithContentDescription("关闭").performClick()
            rule.onNodeWithContentDescription("返回聊天").performClick()
            rule.onNode(hasSetTextAction()).performTextInput("你是什么角色？")
            tapRoleSend()
            awaitRoleRequest(1)
            rule.onNodeWithContentDescription("对话历史").performClick()
            rule.onNodeWithTag("new_chat").performClick()
            rule.waitUntil(8000) { vm.sessionId.value != originalSession && agents.activeRole.value?.id == role.id }
            rule.onNode(hasSetTextAction()).performTextInput("新对话中你是谁？")
            tapRoleSend()
            awaitRoleRequest(2)
            val sent = requests.toList()
            assertNotEquals(sent[0].sessionId, sent[1].sessionId)
            sent.forEach { request ->
                assertEquals(role, request.agentRole)
                val system = request.messages.single { it.role == Role.SYSTEM }.content
                assertTrue(system.contains(role.name)); assertTrue(system.contains(role.prompt))
                assertFalse(system.contains(ModelConfig().systemPrompt))
            }
            screenshot("role-settings-to-provider-new-chat")
        } catch (e: Exception) {
            screenshot("role-settings-to-provider-failure")
            throw AssertionError(rule.onRoot().printToString(), e)
        } finally {
            rule.runOnUiThread { vm.stop(); listOf(vm, agents, workspaces, updates, market).forEach { it.viewModelScope.cancel() } }
            runBlocking {
                sessions.value.forEach { app.roles.select(it.id, null) }
                val restoreId="restore-default-"+java.util.UUID.randomUUID()
                app.roles.selectForNewChats(restoreId, previousDefault?.id)
                app.roles.select(restoreId, null)
                app.roles.delete(role.id)
            }
        }
    }

    @Test fun applyingRoleInSettingsPersistsAndNewChatInheritsItsPluginWhitelist() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val previousDefault = runBlocking { app.roles.forNewChat(null) }
        val role = AgentRole("ui-role-switch", "UI role switch", "Answer as a Kotlin developer",pluginIds=setOf("dev.solvit.web-search"))
        runBlocking { app.roles.save(role) }
        try {
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("角色管理与 Skills").performClick()
            rule.onNodeWithTag("apply_role_${role.id}").performScrollTo()
            rule.onNodeWithTag("apply_role_${role.id}").performClick()
            rule.waitUntil(8000) { rule.onAllNodes(hasText("已应用UI role switch",substring=true)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("关闭").performClick()
            rule.onNodeWithContentDescription("返回聊天").performClick()
            rule.onNodeWithContentDescription("对话历史").performClick()
            rule.onNodeWithTag("new_chat").performClick()
            rule.waitUntil(8000) { rule.onAllNodesWithText("新建工作区").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("角色管理与 Skills").performClick()
            rule.onNodeWithTag("apply_role_${role.id}").performScrollTo()
            rule.onNodeWithTag("apply_role_${role.id}").assertTextEquals("✓ 当前使用")
            screenshot("role-applied-new-chat")
        } finally {
            // Clear bindings before deleting a restricted role, so later fixtures stay usable.
            runBlocking {
                val store = SqliteChatStore(app)
                try { store.sessions().first().forEach { if (runCatching { app.roles.selected(it.id)?.id }.getOrNull() == role.id) app.roles.select(it.id,null) } }
                finally { store.close() }
                val restoreId="restore-default-"+java.util.UUID.randomUUID()
                app.roles.selectForNewChats(restoreId, previousDefault?.id)
                app.roles.select(restoreId, null)
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
        val previous = app.modelProfiles.state.value
        try {
            // Use the live service, as settings UI does. Direct persistence does not
            // notify its StateFlow when this test runs in a fresh app process.
            runBlocking { app.modelProfiles.save(previous.selected(ModelTask.CHAT)!!) }
            repeat(3) {
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
            }
        } finally { runBlocking { app.modelProfiles.replace(previous) } }
    }
    @Test fun startupFocusesInputAndFloatingComposerStaysAboveRealKeyboard() {
        ready()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.waitUntil(10_000) { geometry("chat_composer").keyboardVisible }
        // isVisible flips before the IME animation and Compose inset layout finish.
        rule.waitUntil(10_000) {
            val sample = geometry("chat_composer")
            sample.ime > 0 && sample.bounds.bottom <= sample.height - sample.ime + 1
        }
        val sample = geometry("chat_composer")
        assertTrue("Composer must float with margins", sample.bounds.left > 0 && sample.bounds.right < sample.width)
        assertTrue("Composer overlaps keyboard: ${sample.bounds}, IME=${sample.ime}", sample.bounds.bottom <= sample.height - sample.ime + 1)
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
        rule.onNode(hasText("管理角色与 Skills", substring = true)).assertDoesNotExist()
        screenshot("chat-keyboard-floating")
    }
    @Test fun wideDrawerOpensSettingsAndLastSettingsRowCanBeSeen() {
        ready()
        rule.onNodeWithContentDescription("对话历史").performClick()
        rule.waitForIdle()
        val bounds = geometry("chat_drawer").bounds
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
        rule.onNodeWithTag("plugins_list").performScrollToIndex((rule.activity.application as SolvitApplication).marketplace.installed.value.lastIndex)
        rule.onNodeWithText(last).assertIsDisplayed()
        rule.onNodeWithText(last).performClick()
        rule.onNodeWithText("卸载").assertIsDisplayed()
        rule.onAllNodes(hasText("试运行", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("关闭").performClick()
        screenshot("plugins-bottom-insets")
        rule.onNodeWithContentDescription("关闭插件页面").performClick()
        rule.onNodeWithTag("chat_composer").assertIsDisplayed()
    }
    @Test fun workspaceTerminalRunsRealAndroidShellInTheBoundWorkspace() {
        ready()
        openWorkspace()
        rule.onNodeWithTag("shell_command").performClick().performTextInput("printf hello-solvit")
        rule.onNodeWithText("运行").assertIsEnabled().performClick()
        try { rule.waitUntil(10_000) { rule.onAllNodesWithText("hello-solvit").fetchSemanticsNodes().isNotEmpty() } }
        catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val app = rule.activity.application as SolvitApplication
            throw AssertionError("Shell fixture job states: ${app.workspaces.jobs.value.map { Triple(it.stage, it.exitCode, it.failure) }}", timeout)
        }
        rule.onNodeWithText("hello-solvit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("＋ Shell").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Shell 2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Shell 2").assertIsDisplayed()
        screenshot("workspace-shell-real-process")
        rule.onNodeWithText("工作目录").performClick()
        val html = HtmlPageProbe(rule.activity)
        rule.waitUntil(10_000) { runCatching { html.js("window.pluginPageReady===true && !!document.querySelector('[data-node=drafts]') && !!document.querySelector('[data-node=directory]') && !document.querySelector('[data-node=directory]').disabled") == "true" }.getOrDefault(false) }
        html.tapElement("[data-node=directory]")
        rule.waitUntil(10_000) { runCatching { html.js("!!document.querySelector('[data-node=new]') && !document.querySelector('[data-node=new]').disabled") == "true" }.getOrDefault(false) }
        html.screenshot("workspace-directory")
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
    @Test fun nativeProtocolSettingsSaveOptionsAndReopenThroughProviderDeclarations() {
        ready()
        val app=rule.activity.application as SolvitApplication
        val previous=app.modelProfiles.state.value
        try {
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("模型与连接").performClick()
            rule.onNodeWithTag("new_model_profile").performScrollTo().performClick()
            rule.onNodeWithTag("model_profile_name").performTextInput("Native UI model")
            rule.onNodeWithTag("model_protocol").performScrollTo().performClick()
            rule.onNodeWithTag("model_protocol_anthropic").performClick()
            // Choosing a non-text control must release the edited name before later scrolling.
            rule.onAllNodes(hasTestTag("model_profile_name") and isFocused()).assertCountEquals(0)
            rule.onNodeWithTag("save_model_profile").assertIsNotEnabled()
            rule.onNodeWithTag("model_settings_list").performScrollToKey("model_name")
            // Focusing a lazy-list field can replace its semantics node during IME layout.
            // Establish focus first, then acquire the current node for text input.
            rule.onNodeWithTag("model_name").performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag("model_name") and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("model_name").performTextInput("native-test-model")
            rule.onNodeWithTag("model_settings_list").performScrollToKey("provider_option_anthropic_max_tokens")
            rule.onNodeWithTag("provider_option_max_tokens").performClick().performTextReplacement("16384")
            rule.onNodeWithTag("model_settings_list").performScrollToKey("provider_option_anthropic_thinking_mode")
            rule.onNodeWithTag("provider_option_thinking_mode").performClick()
            rule.onNodeWithText("自适应思考").performClick()
            rule.onNodeWithTag("save_model_profile").assertIsEnabled().performClick()
            rule.waitUntil(8000) { app.modelProfiles.state.value.profiles.any { it.name=="Native UI model" } }
            val profile=app.modelProfiles.state.value.profiles.single { it.name=="Native UI model" }
            val stored=runBlocking { app.settings.loadProfiles() }.profiles.single { it.id==profile.id }.config
            assertEquals("anthropic",stored.providerId)
            assertEquals("adaptive",stored.providerOptions["thinking_mode"])
            assertEquals("16384",stored.providerOptions["max_tokens"])
            repeat(3) {
                rule.onNodeWithTag("model_profiles_list").performScrollToKey(profile.id)
                rule.onNodeWithTag("edit_model_${profile.id}").performClick()
                rule.onNodeWithTag("model_protocol").assertTextContains("Claude / Anthropic")
                rule.onNodeWithTag("model_settings_list").performScrollToKey("provider_option_anthropic_max_tokens")
                rule.onNodeWithTag("provider_option_max_tokens").assertTextContains("16384")
                rule.onNodeWithTag("model_settings_list").performScrollToKey("provider_option_anthropic_thinking_mode")
                rule.onNodeWithTag("provider_option_thinking_mode").assertTextContains("自适应思考")
                rule.onNodeWithContentDescription("关闭设置").performClick()
            }
        } finally { runBlocking { app.modelProfiles.replace(previous) } }
    }
    @Test fun largeNativeTranscriptReopensAndBranchesThroughRealAndroidCursorWindows() = runBlocking<Unit> {
        ready()
        val app=rule.activity.application as SolvitApplication
        val text="🚀中文".repeat(125_000)
        val native=ProviderTranscript("anthropic","test",org.json.JSONArray().put(JSONObject().put("type","text").put("text",text)).toString())
        lateinit var session:ChatSession
        SqliteChatStore(app).use { store ->
            session=store.createSession()
            store.append(ChatMessage("large-native-user",session.id,Role.USER,"large native record"))
            store.append(ChatMessage("large-native-assistant",session.id,Role.ASSISTANT,text,providerTranscript=native))
        }
        // Prove the ordinary whole-row cursor cannot carry this valid transcript.
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath("solvit.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT content,provider_transcript FROM messages WHERE id=?",arrayOf("large-native-assistant")).use { cursor ->
                (cursor as android.database.sqlite.SQLiteCursor).window=android.database.CursorWindow("bounded-native-row",256*1024L)
                assertThrows(android.database.sqlite.SQLiteBlobTooBigException::class.java) { cursor.moveToFirst() }
            }
        }
        SqliteChatStore(app).use { store ->
            try {
                val loaded=store.history(session.id).last()
                assertEquals(text,loaded.content);assertEquals(native,loaded.providerTranscript)
                val branch=store.forkSession(session.id,BranchPoint("large-native-assistant",BranchMode.AFTER_REPLY))
                try { assertEquals(native,store.history(branch.id).last().providerTranscript) } finally { store.deleteSession(branch.id) }
            } finally { store.deleteSession(session.id) }
        }
    }
    @Test fun modelProfilesUiSavesAndAssignsASeparateToolModel() {
        ready()
        val app=rule.activity.application as SolvitApplication
        val previous=app.modelProfiles.state.value
        try {
            rule.onNodeWithContentDescription("设置").performClick()
            rule.onNodeWithText("模型与连接").performClick()
            rule.onNodeWithTag("new_model_profile").performScrollTo().performClick()
            rule.onNodeWithTag("model_profile_name").performTextInput("UI task model")
            rule.onNodeWithTag("save_model_profile").assertIsEnabled().performClick()
            rule.waitUntil(8000) { app.modelProfiles.state.value.profiles.any { it.name=="UI task model" } }
            val profile=app.modelProfiles.state.value.profiles.single { it.name=="UI task model" }
            // Saving publishes the data before the editor coroutine dismisses its window.
            rule.waitUntil(8000) { rule.onAllNodesWithTag("save_model_profile").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("model_profiles_list").performScrollToNode(hasTestTag("model_task_TOOL_FOLLOW_UP"))
            rule.waitUntil(8000) { rule.onAllNodes(hasTestTag("model_task_TOOL_FOLLOW_UP") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("model_task_TOOL_FOLLOW_UP").performClick()
            rule.waitUntil(8000) { rule.onAllNodesWithTag("model_task_choices").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("model_task_choices").performScrollToNode(hasTestTag("choose_model_${profile.id}"))
            rule.onNodeWithTag("choose_model_${profile.id}").assertIsEnabled().performClick()
            rule.waitUntil(8000) { app.modelProfiles.state.value.routes[ModelTask.TOOL_FOLLOW_UP]==profile.id }
            val stored=runBlocking { app.settings.loadProfiles() }
            assertEquals(profile.id,stored.routes[ModelTask.TOOL_FOLLOW_UP])
            rule.onNodeWithTag("model_profiles_list").performScrollToNode(hasTestTag("edit_model_${profile.id}"))
            rule.onNodeWithTag("edit_model_${profile.id}").performClick()
            rule.onNodeWithTag("model_profile_name").assertTextContains("UI task model")
        } finally { runBlocking { app.modelProfiles.replace(previous) } }
    }
    @Test fun languageSwitchesImmediatelyAndSurvivesRecreation() {
        ready()
        val app = rule.activity.application as SolvitApplication
        try {
            rule.onNodeWithContentDescription("设置").performClick()
            scrollSettingsToLanguage()
            rule.onNodeWithTag("language_en").assertDoesNotExist()
            rule.onNodeWithTag("language_settings").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("language_en").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithTag("language_en").performClick()
            rule.onNodeWithTag("settings_title").assertTextEquals("Settings").assertIsDisplayed()
            rule.onNodeWithContentDescription("Back to chat").assertExists()
            assertEquals("en", LanguageStore(app).language.value)
            rule.onNodeWithTag("language_settings").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("language_ja").fetchSemanticsNodes().size == 1 }
            rule.onNodeWithTag("language_ja").performClick()
            rule.onNodeWithTag("settings_title").assertTextEquals("設定").assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("設定").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("設定").performClick()
            rule.onNodeWithTag("settings_title").assertTextEquals("設定").assertIsDisplayed()
            assertEquals("ja", LanguageStore(app).language.value)
        } finally { rule.runOnIdle { app.language.set("zh") } }
    }
    @Test fun interactiveTerminalUiKeepsEnvironmentAndDisplaysOutput() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking { app.systemExecution.setMode(TERMINAL_OWNER,ExecutionMode.APP) }
        openWorkspace(commandJobs=false)
        val page = HtmlPageProbe(rule.activity)
        fun js(code: String) = page.js(code)
        fun waitPage() {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(10_000) { js("document.getElementById('shells').children.length>0") == "true" }
        }
        fun openTerminal() {
            js("document.getElementById('open').click()")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("terminal_page_allow").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("terminal_page_allow").performClick()
            rule.waitUntil(10_000) { js("document.querySelector('.terminal-container:not([hidden]) .xterm-helper-textarea')!==null") == "true" }
        }
        fun command(text: String) {
            val value = kotlinx.serialization.json.JsonPrimitive(text).toString()
            rule.waitUntil(8000) { js("!document.getElementById('send').disabled") == "true" }
            js("document.getElementById('command').value=$value;document.getElementById('input-form').requestSubmit()")
        }
        fun displayed(marker: String) = js("Array.from(document.querySelectorAll('.terminal-container:not([hidden]) .xterm-rows')).some(e=>e.textContent.includes(${kotlinx.serialization.json.JsonPrimitive(marker)}))") == "true"
        try {
            rule.onNodeWithTag("workspace_terminal_mode").assertTextEquals("命令任务");waitPage()
            val dialogCount = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size
            val before = app.terminals.sessions.value.map { it.id }.toSet()
            js("document.getElementById('open').click()")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("terminal_page_allow").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("terminal_page_approval").assertIsDisplayed()
            assertEquals(dialogCount,rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
            assertEquals(before, app.terminals.sessions.value.map { it.id }.toSet())
            rule.onNodeWithText("取消").performClick()
            rule.waitUntil(8000) { js("!document.getElementById('open').disabled") == "true" }
            openTerminal()
            assertEquals("false", js("document.getElementById('session-manager').open"))
            assertEquals("true", js("document.getElementById('extra-keys').hidden"))
            rule.waitUntil(8000) { js("document.getElementById('terminals').getBoundingClientRect().height>innerHeight*.5") == "true" }
            js("document.getElementById('toggle-keys').click()")
            assertEquals("false", js("document.getElementById('extra-keys').hidden"))
            js("document.getElementById('toggle-keys').click()")
            command("stty -echo; export UI_PTY_STATE=retained; printf '__UI_%s__\\n' READY")
            rule.waitUntil(8000) { displayed("__UI_READY__") }
            command("printf 'ui-result:%s\\n' \"\$UI_PTY_STATE\"")
            rule.waitUntil(8000) { displayed("ui-result:retained") }
            // Real xterm DOM input -> onData -> page bridge -> real Android PTY.
            val raw = kotlinx.serialization.json.JsonPrimitive("printf '__RAW_%s__\\n' KEYS\n").toString()
            js("document.querySelector('.terminal-container:not([hidden]) .xterm-helper-textarea').dispatchEvent(new InputEvent('input',{data:$raw,inputType:'insertText',bubbles:true}))")
            rule.waitUntil(8000) { displayed("__RAW_KEYS__") }
            // Wait for the foreground child itself, not just a queued PTY write.
            // An early Ctrl-C can interrupt the previous prompt before sleep starts.
            command("sh -c 'printf \"__SLEEP_%s__\\n\" STARTED; exec sleep 30'")
            rule.waitUntil(8000) { displayed("__SLEEP_STARTED__") }
            js("document.querySelector('[data-key]').click()")
            // Ctrl-C flushes pending terminal input: don't enqueue the next line
            // until the shell has returned from the foreground job.
            rule.waitUntil(8000) {
                app.terminals.sessions.value.any { it.active &&
                    it.output.substringAfterLast("__SLEEP_STARTED__", "").contains("\$ ") }
            }
            command("printf '__INTERRUPT_%s__\\n' OK")
            rule.waitUntil(8000) { displayed("__INTERRUPT_OK__") }
            js("document.getElementById('add').click();document.getElementById('shell-name').value='Second HTML Shell';document.getElementById('new-shell').close('create')")
            rule.waitUntil(8000) { rule.onAllNodesWithTag("terminal_page_allow").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("terminal_page_allow").performClick()
            rule.waitUntil(8000) { js("Array.from(document.getElementById('shells').children).some(e=>e.textContent==='Second HTML Shell' && e.classList.contains('selected'))") == "true" }
            openTerminal();command("stty -echo; printf '__SECOND_%s__\\n' \"\${UI_PTY_STATE:-empty}\"")
            rule.waitUntil(8000) { displayed("__SECOND_empty__") }
            js("document.getElementById('shells').children[0].click()")
            rule.waitUntil(8000) { displayed("ui-result:retained") }

            // Preserve the existing ability to inspect AI-created sessions in this chat.
            val human=app.terminals.sessions.value.first { it.owner==TERMINAL_OWNER && it.chatId!=null && it.active }
            val modelOwner="test.html-terminal.model"
            val descriptor=ToolDescriptor(name="test",description="test",inputSchema="{}",pluginId=modelOwner,
                capabilities=setOf("workspace","shell"),executionMode=ExecutionMode.APP)
            val model=runBlocking {
                withContext(ConversationContext(checkNotNull(human.chatId),human.workspaceId)+ToolInvocationContext(descriptor,
                    ToolCall("html-model-open","open",JSONObject().put("shellId",human.shellId).toString()))) {
                    app.terminals.open(human.workspaceId,human.shellId)
                }
            }

            runBlocking { app.workspaces.write(human.workspaceId,"pty-large.txt","x ".repeat(34000)) }
            val large="stty -echo; cat pty-large.txt; printf '__LONG_%s__\\n' OK\n"
            runBlocking {
                withContext(ConversationContext(checkNotNull(human.chatId),human.workspaceId)+ToolInvocationContext(descriptor,
                    ToolCall("html-model-input","input",JSONObject().put("id",model.id).put("text",large).toString()))) {
                    app.terminals.input(human.workspaceId,model.id,large)
                }
            }
            rule.waitUntil(15_000) { app.terminals.sessions.value.any { it.id==model.id && "__LONG_OK__" in it.output && it.outputOffset>0 } }
            js("document.getElementById('manage').click()")
            assertEquals("true", js("document.getElementById('session-manager').open"))
            rule.waitUntil(8000) { js("Array.from(document.getElementById('running').children).some(e=>e.textContent.includes('test.html-terminal.model'))") == "true" }
            assertEquals("true", js("(()=>{const bounds=document.getElementById('session-manager').getBoundingClientRect();return Array.from(document.querySelectorAll('#session-manager button')).every(button=>{const r=button.getBoundingClientRect();return r.width>0 && r.left>=bounds.left && r.right<=bounds.right && r.top>=bounds.top && r.bottom<=bounds.bottom})})()"))
            page.screenshot("workspace-terminal-session-manager")
            js("document.getElementById('running').value=${kotlinx.serialization.json.JsonPrimitive(model.id)};document.getElementById('attach').click()")
            rule.waitUntil(8000) { rule.onAllNodesWithTag("terminal_page_allow").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("terminal_page_allow").performClick()
            rule.waitUntil(8000) { displayed("__LONG_OK__") }
            assertEquals("true",js("document.getElementById('status').textContent.includes('部分旧输出已回收')"))
            command("stty -echo; printf '__MODEL_PAGE_%s__\\n' OK")
            rule.waitUntil(8000) { displayed("__MODEL_PAGE_OK__") }
            page.screenshot("workspace-interactive-html-pty")
            page.tapElement("#command")
            rule.waitUntil(8000) { page.keyboardVisible() }
            rule.waitUntil(8000) { js("document.querySelector('footer').getBoundingClientRect().bottom<=innerHeight+1 && document.getElementById('terminals').getBoundingClientRect().height>=40") == "true" }
            assertEquals("true", js("document.getElementById('compact-interrupt').getBoundingClientRect().width>0 && getComputedStyle(document.getElementById('keys')).display==='none'"))
            page.screenshot("workspace-terminal-keyboard")
            command("sh -c 'printf \"__KEYBOARD_SLEEP_%s__\\n\" STARTED; exec sleep 30'")
            rule.waitUntil(8000) { app.terminals.sessions.value.any { it.id==model.id && it.output.contains("__KEYBOARD_SLEEP_STARTED__") } }
            page.tapElement("#compact-interrupt")
            rule.waitUntil(8000) { app.terminals.sessions.value.any { it.id==model.id &&
                it.output.substringAfterLast("__KEYBOARD_SLEEP_STARTED__", "").contains("\$ ") } }
            js("document.getElementById('manage').click()")
            rule.waitUntil(8000) { js("(()=>{const r=document.getElementById('dismiss').getBoundingClientRect();return document.getElementById('session-manager').open && r.height>0 && r.top>=0 && r.bottom<=innerHeight})()") == "true" }
            page.screenshot("workspace-terminal-keyboard-menu")
            js("document.getElementById('dismiss').click()")
            page.hideKeyboard()
            rule.waitUntil(8000) { !page.keyboardVisible() }
            js("document.getElementById('manage').click()")
            assertEquals("true", js("document.getElementById('session-manager').open"))
            js("document.getElementById('close').click()")
            rule.waitUntil(8000) { js("document.getElementById('close').disabled") == "true" }
            assertTrue(app.terminals.sessions.value.any { it.id==model.id && !it.active })
        } catch (e: Throwable) {
            val metrics=runCatching { js("JSON.stringify({viewport:[innerWidth,innerHeight],body:document.body.getBoundingClientRect().toJSON(),terminal:document.getElementById('terminals').getBoundingClientRect().toJSON(),rows:document.querySelector('.xterm-rows')?.textContent,status:document.getElementById('status').textContent})") }.getOrNull()
            runCatching { page.screenshot("workspace-interactive-html-failure") }
            throw AssertionError("HTML metrics="+metrics+"\nPTY state=" + app.terminals.sessions.value.filter { it.owner==TERMINAL_OWNER }, e)
        }
    }

}
