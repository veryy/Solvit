package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.plugins.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Actual Android QuickJS -> generic Compose form -> callback -> native persistent storage. */
class PluginPageUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
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
