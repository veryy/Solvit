package dev.solvit.app

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real offline DOM -> origin-restricted bridge -> QuickJS -> marketplace storage. */
class PluginWebPageUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun browser(view: View): WebView? = if (view is WebView) view else
        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { browser(group.getChildAt(it)) } }

    private fun js(code: String): String = runBlocking {
        val result = CompletableDeferred<String>()
        rule.runOnUiThread {
            checkNotNull(browser(rule.activity.window.decorView)).evaluateJavascript(code) { result.complete(it) }
        }
        withTimeout(10_000) { result.await() }
    }

    @Test fun pluginHtmlSavesReopensAndRequiresActualNativeDeleteConfirmation() {
        val app = rule.activity.application as SolvitApplication
        val id = "test.web-entry"
        val prefs = app.getSharedPreferences("extension_data.$id", 0)
        val payload = buildJsonObject {
            putJsonObject("manifest") {
                put("id", id); put("name", "HTML notes fixture"); put("description", "Test")
                put("version", "1.0.0"); put("apiVersion", 10)
                put("permissions", JsonArray(listOf(JsonPrimitive("storage"))))
                put("dependencies", JsonArray(emptyList())); put("tools", JsonArray(emptyList()))
                putJsonArray("pages") { add(buildJsonObject { put("id", "main"); put("title", "HTML notes"); put("entry", "ui/index.html") }) }
            }
            putJsonObject("resources") {
                put("ui/index.html", """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                    <h1>Plugin-owned HTML notes</h1><textarea id="note"></textarea>
                    <button id="save">Save</button><button id="delete">Delete</button><script src="main.js"></script>""".trimIndent())
                put("ui/main.js", """
                    let state;
                    window.solvitPageUpdate = next => {
                      if (!state || state.token !== next.token) document.getElementById('note').value=next.view.nodes[0].value;
                      state=next; document.body.style.background=next.theme.surface;
                      document.body.style.color=next.theme.onSurface;
                      document.querySelectorAll('button').forEach(b=>b.disabled=next.busy);
                      window.pageReady=true;
                    };
                    for(const id of ['save','delete']) document.getElementById(id).onclick=()=>{
                      solvitHost.postMessage(JSON.stringify({type:'event',token:state.token,nodeId:id,fields:{note:document.getElementById('note').value}}));
                    };
                    solvitHost.postMessage(JSON.stringify({type:'ready'}));
                """.trimIndent())
            }
            put("source", """
                globalThis.solvitPlugin={tools:{},pages:{main:async(input,ctx)=>{
                  if(input.event?.action==='save')await ctx.storage.set('note',input.event.fields.note);
                  if(input.event?.action==='delete')await ctx.storage.set('note','');
                  return {title:'HTML notes',nodes:[{id:'note',kind:'field',label:'Note',value:await ctx.storage.get('note')??''}],
                    footer:[{id:'save',kind:'button',text:'Save',action:'save'},
                    {id:'delete',kind:'button',text:'Delete',action:'delete',confirm:'Delete this HTML note?'}]};
                }}};
            """.trimIndent())
        }.toString().toByteArray()
        runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            prefs.edit().clear().commit()
            app.marketplace.installLocal(payload); app.marketplace.enable(id, setOf("storage"))
        }
        var visible by mutableStateOf(true)
        fun waitReady() {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isNotEmpty() }
            rule.waitUntil(10_000) { js("window.pageReady===true") == "true" }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("HTML notes", { app.marketplace.openPage(id, "main", "en") }) { visible = false }
            } } }
            waitReady()
            rule.onNodeWithTag("plugin_field_note").assertDoesNotExist()
            assertEquals("\"Plugin-owned HTML notes\"", js("document.querySelector('h1').textContent"))
            js("document.getElementById('note').value='persistent HTML note';document.getElementById('save').click()")
            rule.waitUntil(10_000) { prefs.getString("note", null) == JsonPrimitive("persistent HTML note").toString() }
            rule.waitUntil(10_000) { js("!document.getElementById('save').disabled") == "true" }
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible = true }
            waitReady()
            assertEquals("\"persistent HTML note\"", js("document.getElementById('note').value"))
            js("document.getElementById('delete').click()")
            rule.onNodeWithText("Delete this HTML note?").assertIsDisplayed()
            assertEquals(JsonPrimitive("persistent HTML note").toString(), prefs.getString("note", null))
            rule.onNodeWithText("取消").performClick()
            assertEquals(JsonPrimitive("persistent HTML note").toString(), prefs.getString("note", null))
            js("document.getElementById('delete').click()")
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            rule.waitUntil(10_000) { prefs.getString("note", null) == JsonPrimitive("").toString() }
            rule.waitUntil(10_000) { js("document.getElementById('note').value") == "\"\"" }
            runBlocking { app.marketplace.disable(id) }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("plugin_page_error").assertIsDisplayed()
        } finally {
            rule.runOnUiThread { visible = false }
            runBlocking { app.marketplace.uninstall(id) }; prefs.edit().clear().commit()
        }
    }
}
