package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import dev.solvit.core.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry

/** Actual Android QuickJS -> generic Compose form -> callback -> native persistent storage. */
class PluginPageUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    /** Failure-only fixture diagnostics; never suppresses the original assertion. */
    private fun captureMemoryFilterFailure(stage: String, failure: Throwable) {
        val marker = "[DEBUG-memory-filter]"
        runCatching {
            val tags = listOf("plugin_page_content", "plugin_field_query", "plugin_node_filter", "plugin_node_open_0", "plugin_page_error")
            val nodes = tags.joinToString("\n") { tag ->
                val matches = rule.onAllNodesWithTag(tag).fetchSemanticsNodes()
                "$tag: count=${matches.size}, bounds=${matches.map { it.boundsInRoot }}"
            }
            val focused = rule.onAllNodes(hasTestTag("plugin_field_query") and isFocused()).fetchSemanticsNodes().size
            Log.e("SolvitMemoryUi", "$marker stage=$stage queryFocused=$focused\n$nodes")
        }.onFailure { failure.addSuppressed(it) }
        runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys input_method")).use { input ->
                val state = input.bufferedReader().readLines().filter {
                    it.contains("mInputShown") || it.contains("mShowRequested") || it.contains("mImeWindowVis")
                }.joinToString("\n")
                Log.e("SolvitMemoryUi", "$marker $state")
            }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/memory-filter-failure.png")).use { it.readBytes() }
        }.onFailure { failure.addSuppressed(it) }
    }
    @Test fun packagedTaskPageShowsSourcesAndUsesNativeCostConfirmationBeforeServiceControl() {
        val app = rule.activity.application as SolvitApplication
        runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            app.marketplace.disable("dev.solvit.memory")
        }
        val target = PostTurnTarget("dev.solvit.memory", "1.3.0", "a".repeat(64))
        val turn = CompletedChatTurn("page-task-answer", "page-task-chat", "page-task-user", "page-task-answer",
            null, null, null, null, "fixture", "fixture", 1, listOf(target))
        val row = AtomicReference<PostTurnJob?>(PostTurnJob("page-task-answer:dev.solvit.memory", turn, target,
            PostTurnJobStatus.FAILED, 1, 2, PostTurnFailure.MODEL))
        val controls = AtomicInteger()
        val service = object : PostTurnTaskService {
            override suspend fun list(pluginId: String, before: PostTurnJobCursor?, limit: Int, authorize: suspend () -> Unit): PostTurnTaskPage {
                assertEquals(target.pluginId, pluginId); authorize()
                return PostTurnTaskPage(listOfNotNull(row.get()), null)
            }
            override suspend fun control(pluginId: String, action: PostTurnTaskControl, authorize: suspend () -> Unit): Int {
                assertEquals(target.pluginId, pluginId); authorize()
                val current = row.get() ?: return 0
                when (action) {
                    is PostTurnTaskControl.Retry -> {
                        assertEquals(current.id, action.id); assertEquals(current.attempt, action.attempt)
                        assertEquals(PostTurnJobStatus.FAILED, current.status)
                        row.set(current.copy(status = PostTurnJobStatus.WAITING, failure = null))
                    }
                    is PostTurnTaskControl.Delete -> {
                        assertEquals(current.id, action.id); assertEquals(current.status, action.status)
                        row.set(null)
                    }
                    else -> error("Unexpected task action")
                }
                controls.incrementAndGet(); return 1
            }
            override suspend fun retired(pluginId: String, turnId: String, authorize: suspend () -> Unit): Boolean {
                authorize(); return false
            }
        }
        val manager = dev.solvit.market.BuiltinMemoryManager(app)
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
            PluginPageScreen("自动记忆任务", { manager.openPage("zh", service, "tasks") }) {}
        } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_node_task_open_0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("plugin_node_task_open_0").performClick()
        rule.onNodeWithTag("plugin_page_content").performScrollToNode(hasTestTag("plugin_node_task_user"))
        rule.onNodeWithText("来源用户消息: page-task-user").assertIsDisplayed()
        rule.onNodeWithTag("plugin_node_task_retry").performClick()
        rule.onNodeWithText("重试将使用原模型配置，可能产生额外费用。").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        assertEquals(0, controls.get())
        rule.onNodeWithTag("plugin_node_task_retry").performClick()
        rule.onNodeWithTag("plugin_page_confirm").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_node_task_retry").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("plugin_page_content").performScrollToNode(hasTestTag("plugin_node_task_status"))
        rule.onNode(hasTestTag("plugin_node_task_status") and hasText("等待处理")).assertIsDisplayed()
        rule.onNodeWithTag("plugin_node_task_delete").performClick()
        rule.onNodeWithTag("plugin_page_confirm").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_node_task_empty").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, controls.get()); assertNull(row.get())
        assertFalse(app.marketplace.installed.value.first { it.id == target.pluginId }.enabled)
    }
    @Test fun nativeTaskControlShowsHostWarningAndRequiresExplicitConfirmation() {
        val view = ScriptPageView.parse("""{"title":"Tasks","nodes":[],"footer":[
            {"id":"retry","kind":"button","text":"Continue","action":"retry",
             "hostAction":{"kind":"postTurn.retry","jobId":"task-a","attempt":1}}]}""")
        var accepted: PluginPageInteraction? = null
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
            PluginPageContent(view, false) { accepted = it }
        } } }
        rule.onNodeWithTag("plugin_node_retry").performClick()
        rule.onNodeWithText("重试将使用原模型配置，可能产生额外费用。").assertIsDisplayed()
        rule.onNodeWithText("task-a").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertNull(accepted) }
        rule.onNodeWithTag("plugin_node_retry").performClick()
        rule.onNodeWithTag("plugin_page_confirm").performClick()
        rule.runOnIdle {
            assertTrue(accepted!!.confirmed); assertEquals("retry", accepted!!.nodeId)
            assertSame(view, accepted!!.view)
        }
    }
    @Test fun packagedMemoryPageFiltersEditsAndReopensWithoutEnablingPlugin() {
        val app = rule.activity.application as SolvitApplication
        val manager = dev.solvit.market.BuiltinMemoryManager(app)
        val id = runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            app.marketplace.disable("dev.solvit.memory")
            manager.save("plugin-memory-ui-original", "fact", emptyList()).getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
        }
        var visible by mutableStateOf(true)
        fun present() { rule.activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            if (visible) PluginPageScreen("记忆", { manager.openPage("zh") }) { visible = false }
        } } }
        var stage = "initial-open"
        try {
            rule.runOnUiThread { present() }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_field_query").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_query").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_query") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_query").performTextReplacement("plugin-memory-ui-original")
            stage = "initial-scroll"
            rule.onNodeWithTag("plugin_page_content").performScrollToNode(hasTestTag("plugin_node_filter"))
            stage = "initial-filter-click"
            rule.onNodeWithTag("plugin_node_filter").performClick()
            stage = "initial-open-result"
            rule.onNodeWithTag("plugin_page_content").performScrollToNode(hasTestTag("plugin_node_open_0"))
            rule.onNodeWithTag("plugin_node_open_0").performClick()
            rule.onNodeWithTag("plugin_node_edit").performClick()
            rule.onNodeWithTag("plugin_field_text").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_text") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_text").performTextReplacement("plugin-memory-ui-edited")
            rule.onNodeWithTag("plugin_node_save").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_node_body") and hasText("plugin-memory-ui-edited")).fetchSemanticsNodes().isNotEmpty() }
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
            rule.onNodeWithContentDescription("关闭插件界面").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_content").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible = true }
            stage = "reopened-query"
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_field_query").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_query").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_query") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_query").performTextReplacement("plugin-memory-ui-edited")
            stage = "reopened-scroll"
            rule.onNodeWithTag("plugin_page_content").performScrollToNode(hasTestTag("plugin_node_filter"))
            stage = "reopened-filter-click"
            rule.onNodeWithTag("plugin_node_filter").performClick()
            stage = "reopened-open-result"
            rule.onNodeWithTag("plugin_page_content").performScrollToNode(hasTestTag("plugin_node_open_0"))
            rule.onNodeWithTag("plugin_node_open_0").performClick()
            rule.onNodeWithTag("plugin_node_remove").performClick()
            rule.onNodeWithText("取消").performClick()
            assertEquals("plugin-memory-ui-edited", runBlocking { manager.snapshot().getValue("facts").jsonArray.first { it.jsonObject["id"]==JsonPrimitive(id) }.jsonObject.getValue("text").jsonPrimitive.content })
            rule.onNodeWithTag("plugin_node_remove").performClick()
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_node_empty").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
        } catch (failure: Throwable) {
            captureMemoryFilterFailure(stage, failure)
            throw failure
        } finally { rule.runOnUiThread { visible = false }; runBlocking { runCatching { manager.delete(id) } } }
    }
    @Test fun installedPluginOpensGenericPageFromDetailsAndPersistsThroughRealMarket() {
        val app = rule.activity.application as SolvitApplication
        val id = "test.page-entry"
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", id); put("name", "Page entry fixture"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", 9)
                put("permissions", JsonArray(listOf(JsonPrimitive("storage"))))
                put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
                putJsonArray("pages") { add(buildJsonObject { put("id", "main"); put("title", "Entry notes") }) }
            }
            put("source", """
                globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
                  if(input.event?.action==='save')await ctx.storage.set('note',input.event.fields.note);
                  return {title:'Entry notes',nodes:[{id:'note',kind:'field',label:'Note',value:await ctx.storage.get('note')??''}],
                    footer:[{id:'save',kind:'button',text:'Save entry',action:'save'}]};
                }}};
            """.trimIndent())
        }.toString().toByteArray()
        runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            app.getSharedPreferences("extension_data.$id", 0).edit().clear().commit()
            app.marketplace.installLocal(payload); app.marketplace.enable(id, setOf("storage"))
        }
        val vm = MarketplaceViewModel(app.marketplace)
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme { PluginMarketplaceDialog(vm) {} } } }
            rule.onNode(hasText("已安装 (", substring = true)).performClick()
            rule.onNodeWithTag("plugins_list").performScrollToNode(hasTestTag("installed_plugin_$id"))
            rule.onNodeWithTag("installed_plugin_$id").performClick()
            rule.onNodeWithTag("open_plugin_page_${id}_main").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_field_note").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_note").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_note") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_note").performTextReplacement("real market page note")
            rule.onNodeWithTag("plugin_node_save").performClick()
            rule.waitUntil(10_000) {
                app.getSharedPreferences("extension_data.$id", 0).getString("note", "") == JsonPrimitive("real market page note").toString()
            }
            rule.onNodeWithContentDescription("关闭插件界面").assertExists().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_field_note").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithContentDescription("关闭插件页面").assertExists()
            rule.onNodeWithTag("installed_plugin_$id").performClick()
            rule.onNodeWithTag("open_plugin_page_${id}_main").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_note") and hasText("real market page note")).fetchSemanticsNodes().isNotEmpty() }
        } finally {
            runBlocking { app.marketplace.uninstall(id) }
            app.getSharedPreferences("extension_data.$id", 0).edit().clear().commit()
        }
    }
    @Test fun pluginFormPersistsAndReopensAndDestructiveActionNeedsConfirmation() {
        val prefs = rule.activity.getSharedPreferences("plugin-page-ui-fixture", 0)
        prefs.edit().clear().putString("note", "old note").commit()
        val pkg = ScriptPackage.parse(buildJsonObject {
            putJsonObject("manifest") {
                put("id", "test.page-ui"); put("name", "Page fixture"); put("description", "Test"); put("version", "1.0.0"); put("apiVersion", 9)
                put("permissions", JsonArray(listOf(JsonPrimitive("storage"))))
                put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
                putJsonArray("pages") { add(buildJsonObject { put("id", "main"); put("title", "Notes") }) }
            }
            put("source", """
                globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
                  if(input.event?.action==='save')await ctx.storage.set('note',input.event.fields.note);
                  if(input.event?.action==='delete')await ctx.storage.set('note','');
                  const value=await ctx.storage.get('note')??'';
                  return {title:'Notes',nodes:[{id:'note',kind:'field',label:'Note',value,multiline:true}],
                    footer:[{id:'save',kind:'button',text:'Save note',action:'save'},
                      {id:'delete',kind:'button',text:'Delete note',action:'delete',confirm:'Delete this note?'}]};
                }}};
            """.trimIndent())
        }.toString().toByteArray())
        val host = ScriptHost { _, operation, args ->
            assertEquals(JsonPrimitive("note"), args["key"])
            when (operation) {
                "storage.get" -> JsonPrimitive(prefs.getString("note", "").orEmpty())
                "storage.set" -> { assertTrue(prefs.edit().putString("note", args.getValue("value").jsonPrimitive.content).commit()); JsonNull }
                else -> error("Unexpected capability")
            }
        }
        var session = ScriptPageSession(pkg, "main", "en", host) {}
        var view by mutableStateOf(runBlocking { session.open() })
        var busy by mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                FullScreenPage({}) {
                    PluginPageContent(view, busy, Modifier.fillMaxSize()) { event ->
                        scope.launch {
                            busy = true
                            try { view = session.dispatch(event.view, event.nodeId, event.value, event.fields, event.confirmed) }
                            finally { busy = false }
                        }
                    }
                }
            } } }
            rule.onNodeWithTag("plugin_field_note").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_note") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_note").performTextReplacement("persistent plugin note")
            rule.onNodeWithTag("plugin_node_save").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { prefs.getString("note", "") == "persistent plugin note" && !busy }
            runBlocking { session.close(); session = ScriptPageSession(pkg, "main", "en", host) {} }
            val reopened = runBlocking { session.open() }
            rule.runOnIdle { view = reopened }
            rule.onNodeWithTag("plugin_field_note").assertTextContains("persistent plugin note")
            rule.onNodeWithTag("plugin_node_delete").performClick()
            rule.onNodeWithText("Delete this note?").assertIsDisplayed()
            assertEquals("persistent plugin note", prefs.getString("note", ""))
            rule.onNodeWithText("取消").performClick()
            assertEquals("persistent plugin note", prefs.getString("note", ""))
            rule.onNodeWithTag("plugin_node_delete").performClick()
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            rule.waitUntil(10_000) { prefs.getString("note", "") == "" && !busy }
            rule.onNodeWithTag("plugin_field_note").assert(hasText("", substring = false))
        } finally {
            scope.cancel(); runBlocking { session.close() }; prefs.edit().clear().commit()
        }
    }
    @Test fun relationCanvasAndAccessibleControlsSelectSuppliedNodes() {
        val view = ScriptPageView.parse("""{"title":"Relations","nodes":[{"id":"relations","kind":"graph","action":"details",
            "nodes":[{"id":"a","label":"Fact A"},{"id":"b","label":"Fact B"}],
            "edges":[{"from":"a","to":"b","label":"relates"}]}]}""")
        var selected: PluginPageInteraction? = null
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
            FullScreenPage({}) { PluginPageContent(view, false) { selected = it } }
        } } }
        val density = rule.activity.resources.displayMetrics.density
        rule.onNodeWithTag("plugin_graph_canvas_relations").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(85 * density, 45 * density))
        }
        rule.runOnIdle { assertEquals("a", selected?.value); assertSame(view, selected?.view) }
        rule.onNodeWithText("Fact B").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("b", selected?.value) }
    }
}
