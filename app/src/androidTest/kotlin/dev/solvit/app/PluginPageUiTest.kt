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

/** Actual Android QuickJS -> generic Compose form -> callback -> native persistent storage. */
class PluginPageUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
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
        val html = HtmlPageProbe(rule.activity)
        fun waitDom(expression: String) { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        waitDom("!!document.querySelector('[data-node=task_open_0]')")
        html.js("document.querySelector('[data-node=task_open_0]').click()")
        waitDom("document.body.textContent.includes('来源用户消息: page-task-user')")
        html.js("document.querySelector('[data-node=task_retry]').click()")
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("重试将使用原模型配置，可能产生额外费用。").assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        assertEquals(0, controls.get())
        waitDom("document.querySelector('[data-node=task_retry]')?.disabled===false")
        html.js("document.querySelector('[data-node=task_retry]').click()")
        rule.onNodeWithTag("plugin_page_confirm").performClick()
        waitDom("document.querySelector('[data-node=task_status]')?.textContent==='等待处理'")
        html.js("document.querySelector('[data-node=task_delete]').click()")
        rule.onNodeWithTag("plugin_page_confirm").performClick()
        waitDom("!!document.querySelector('[data-node=task_empty]')")
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
        val (id, relatedId) = runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            app.marketplace.disable("dev.solvit.memory")
            val first = manager.save("plugin-memory-ui-original", "fact", emptyList()).getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
            val related = manager.save("plugin-memory-ui-original related", "fact", emptyList()).getValue("fact").jsonObject.getValue("id").jsonPrimitive.content
            try {
                app.marketplace.enable("dev.solvit.memory", app.marketplace.installed.value.single { it.id == "dev.solvit.memory" }.permissions)
                app.marketplace.runTool("dev_solvit_memory_memory_link", buildJsonObject {
                    put("from", first); put("to", related); put("relation", "关联")
                }.toString(), object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor) = true
                })
            } finally { app.marketplace.disable("dev.solvit.memory") }
            first to related
        }
        var visible by mutableStateOf(true)
        fun present() { rule.activity.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            if (visible) PluginPageScreen("记忆", { manager.openPage("zh") }) { visible = false }
        } } }
        val html = HtmlPageProbe(rule.activity)
        fun waitDom(expression: String) { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        fun select() { html.js("document.querySelector('[data-memory-id=$id]').click()") }
        try {
            rule.runOnUiThread { present() }
            waitDom("window.memoryPageReady===true && !!document.querySelector('[data-memory-id=$id]') && !!document.querySelector('[data-memory-id=$relatedId]') && document.getElementById('graph').getBoundingClientRect().height>innerHeight*.9")
            val initialZoom = html.js("Number(document.getElementById('graph').dataset.zoom)").toDouble()
            assertTrue("Fitting two memory nodes must not magnify labels above their readable base size; actual zoom=$initialZoom", initialZoom > 0 && initialZoom <= 1.01)
            html.js("document.getElementById('fit').click()")
            assertTrue("Fit action must also cap automatic magnification", html.js("Number(document.getElementById('graph').dataset.zoom)").toDouble() <= 1.01)
            if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("verifyMemoryLabelBounds") == "true") {
                // Query the pinned engine's actual rendered boxes, without moving nodes or
                // imitating its layout. Container registration is only a test seam.
                repeat(8) { attempt ->
                    if (attempt > 0) {
                        rule.onNodeWithTag("plugin_page_close").performClick()
                        rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
                        rule.runOnUiThread { visible = true }
                        waitDom("window.memoryPageReady===true && !!document.querySelector('[data-memory-id=$id]') && !!document.querySelector('[data-memory-id=$relatedId]')")
                    }
                    html.screenshot("memory-label-layout-${'a' + attempt}")
                    val boxes = Json.parseToJsonElement(html.js("""(()=>{
                        const cy=document.getElementById('graph')._cyreg?.cy;
                        if(!cy)throw Error('Pinned graph instance unavailable');
                        return [${JsonPrimitive(id)},${JsonPrimitive(relatedId)}].map(id=>
                            cy.getElementById(id).renderedBoundingBox({includeLabels:true,includeOverlays:false,includeUnderlays:false}));
                    })()""")).jsonArray.map { it.jsonObject }
                    fun coordinate(index: Int, key: String) = boxes[index].getValue(key).jsonPrimitive.double.also {
                        assertTrue("Rendered graph coordinate must be finite", it.isFinite())
                    }
                    val overlapWidth = minOf(coordinate(0,"x2"),coordinate(1,"x2")) - maxOf(coordinate(0,"x1"),coordinate(1,"x1"))
                    val overlapHeight = minOf(coordinate(0,"y2"),coordinate(1,"y2")) - maxOf(coordinate(0,"y1"),coordinate(1,"y1"))
                    assertFalse("Actual node/label boxes overlap on initial layout $attempt: $boxes", overlapWidth > 1 && overlapHeight > 1)
                }
            }
            val graphFrame = html.screenshot("memory-html-graph-home")
            // The previous artifact was white although DOM/interaction checks passed.
            // Check the displayed middle of this explicitly dark page, excluding native bars.
            val samples = (2..8).flatMap { row -> (2..8).map { column ->
                graphFrame.getPixel(graphFrame.width * column / 10, graphFrame.height * row / 10)
            } }
            val darkPixels = samples.count { pixel ->
                android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) < 384
            }
            assertTrue("HTML graph must be visibly themed, not a pre-render white frame", darkPixels >= samples.size * .8)
            graphFrame.recycle()
            rule.onNodeWithTag("plugin_field_query").assertDoesNotExist()
            html.js("document.getElementById('query').value='plugin-memory-ui-original';document.getElementById('query').dispatchEvent(new Event('input'));document.getElementById('search').dispatchEvent(new Event('submit',{cancelable:true}))")
            waitDom("!document.getElementById('search-button').disabled && !!document.querySelector('[data-memory-id=$id]') && !!document.querySelector('[data-memory-id=$relatedId]')")
            html.tapMemoryNode(id)
            waitDom("!!document.querySelector('[data-node=edit]')")
            html.js("document.querySelector('[data-node=edit]').click()")
            waitDom("!!document.getElementById('field-text')")
            html.js("const input=document.getElementById('field-text');input.value='plugin-memory-ui-edited';input.dispatchEvent(new Event('input'));document.querySelector('[data-node=save]').click()")
            waitDom("document.querySelector('[data-node=body]')?.textContent==='plugin-memory-ui-edited'")
            html.screenshot("memory-html-edited-detail")
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
            rule.onNodeWithContentDescription("关闭插件界面").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible = true }
            waitDom("window.memoryPageReady===true && !!document.querySelector('[data-memory-id=$id]')")
            select()
            waitDom("document.querySelector('[data-node=body]')?.textContent==='plugin-memory-ui-edited'")
            html.js("document.querySelector('[data-node=remove]').click()")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("取消").performClick()
            assertEquals("plugin-memory-ui-edited", runBlocking { manager.snapshot().getValue("facts").jsonArray.first { it.jsonObject["id"]==JsonPrimitive(id) }.jsonObject.getValue("text").jsonPrimitive.content })
            waitDom("document.querySelector('[data-node=remove]')?.disabled===false")
            html.js("document.querySelector('[data-node=remove]').click()")
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            waitDom("!document.querySelector('[data-memory-id=$id]') && document.getElementById('sheet').hidden")
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.memory" }.enabled)
        } finally {
            rule.runOnUiThread { visible = false }
            runBlocking { runCatching { manager.delete(id) }; runCatching { manager.delete(relatedId) } }
        }
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
            rule.onNodeWithTag("plugin_permissions_details").assertDoesNotExist()
            rule.onNodeWithTag("plugin_permissions_toggle").performClick()
            rule.onNodeWithTag("plugin_permissions_details").assertExists()
            rule.onNodeWithTag("plugin_details_content").performScrollToNode(hasTestTag("open_plugin_page_${id}_main"))
            rule.onNodeWithTag("open_plugin_page_${id}_main").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_field_note").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_note").performClick()
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("plugin_field_note") and isFocused()).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_field_note").performTextReplacement("real market page note")
            rule.onNodeWithTag("plugin_node_save").performClick()
            try {
                rule.waitUntil(10_000) {
                    app.getSharedPreferences("extension_data.$id", 0).getString("note", "") == JsonPrimitive("real market page note").toString()
                }
            } catch (failure: Throwable) {
                // Only fixed fixture booleans and geometry; no arbitrary field/configuration text.
                val fieldMatches = rule.onAllNodes(hasTestTag("plugin_field_note") and hasText("real market page note")).fetchSemanticsNodes().isNotEmpty()
                val enabled = rule.onAllNodes(hasTestTag("plugin_node_save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                val buttons = rule.onAllNodesWithTag("plugin_node_save").fetchSemanticsNodes()
                val bounds = rule.runOnUiThread { buttons.map { it.boundsInWindow.toString() } }
                val ime = rule.runOnUiThread { rule.activity.window.decorView.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.ime())?.bottom ?: 0 }
                android.util.Log.i("PluginPageFixture", "NATIVE_PAGE_SAVE_WAIT|fieldMatches=$fieldMatches|enabled=$enabled|stored=${app.getSharedPreferences("extension_data.$id", 0).contains("note")}|ime=$ime|bounds=$bounds")
                runCatching {
                    val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                    for (command in listOf("mkdir -p /data/local/tmp/solvit-ui",
                        "screencap -p /data/local/tmp/solvit-ui/plugin-native-save-failure.png")) {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
                    }
                }
                throw failure
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
