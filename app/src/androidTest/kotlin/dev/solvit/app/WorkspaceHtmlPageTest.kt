package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import dev.solvit.core.ShellStage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class WorkspaceHtmlPageTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun htmlCreatesBindsBrowsesAndSavesThroughActualNativeConfirmationAndRejectsStaleFiles() {
        val app = rule.activity.application as SolvitApplication
        val chat = "html-workspace-" + java.util.UUID.randomUUID()
        runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            app.marketplace.disable("dev.solvit.workspace")
        }
        val manager = BuiltinWorkspaceManager(app)
        val html = HtmlPageProbe(rule.activity)
        val initialText = "<script>window.executed=true</script>\n第一版🙂\n" + "const value = 1; // 中文\n".repeat(3000)
        val marker = "IME_EDIT"
        var visible by mutableStateOf(true)
        fun waitDom(expression: String) { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        fun click(id: String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun field(id: String, text: String) { html.js("(()=>{const input=document.getElementById('field-$id');input.value=${JsonPrimitive(text)};input.dispatchEvent(new Event('input'))})()") }
        fun document(text: String) {
            waitDom("!!window.workspaceEditor")
            html.js("(()=>{const editor=window.workspaceEditor;editor.dispatch({changes:{from:0,to:editor.state.doc.length,insert:${JsonPrimitive(text)}},selection:{anchor:${text.length}},scrollIntoView:true})})()")
        }
        fun native(confirm: Boolean) {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_page_confirm").assertIsDisplayed()
            if (confirm) rule.onNodeWithTag("plugin_page_confirm").performClick() else rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("工作区", { manager.openPage("zh", WorkspacePageBridge(app.workspaces, app.pluginDrafts, chat)) }) { visible = false }
            } } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=create]')")
            assertNull(runBlocking { app.workspaces.bound(chat) })
            click("create"); waitDom("!!document.getElementById('field-name')")
            field("name", "HTML 工作目录"); click("prepareCreate")
            waitDom("!!document.querySelector('[data-node=confirm]')")
            assertNull(runBlocking { app.workspaces.bound(chat) })
            click("confirm"); native(false); assertNull(runBlocking { app.workspaces.bound(chat) })
            click("confirm"); native(true)
            waitDom("!!document.querySelector('[data-node=directory]')")
            val workspace = checkNotNull(runBlocking { app.workspaces.bound(chat) })
            assertEquals("HTML 工作目录", workspace.name)
            assertEquals(1, app.workspaces.state.value.shells.count { it.workspaceId == workspace.id })
            click("directory"); waitDom("!!document.querySelector('[data-node=new]')")
            click("new"); waitDom("!!window.workspaceEditor && !!document.querySelector('.cm-content[contenteditable=true]')")
            field("path", "src/note.ts");document(initialText)
            waitDom("(()=>{const line=document.querySelector('.cm-line:last-child');if(!line)return false;const r=line.getBoundingClientRect();return r.top>=0 && r.bottom<document.getElementById('actions').getBoundingClientRect().top})()")
            html.tapElement(".cm-line:last-child")
            rule.waitUntil(10_000) { html.keyboardVisible() }
            InstrumentationRegistry.getInstrumentation().sendStringSync(marker)
            waitDom("window.workspaceEditor.state.doc.toString().endsWith('$marker')")
            html.hideKeyboard()
            rule.waitUntil(10_000) { !html.keyboardVisible() }
            click("prepareSave"); waitDom("!!document.querySelector('[data-node=confirm]')")
            assertEquals("\"undefined\"", html.js("typeof window.executed"))
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            click("confirm"); native(false)
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            click("confirm"); native(true)
            waitDom("!!document.querySelector('[data-node=file_0]')")
            assertEquals(initialText+marker, runBlocking { app.workspaces.read(workspace.id, "src/note.ts") })
            click("file_0"); waitDom("document.querySelector('[data-node=directory]')?.textContent==='src'")
            click("file_0");waitDom("!!window.workspaceEditor && !!document.querySelector('.cm-content[contenteditable=false]')")
            assertEquals(JsonPrimitive(initialText+marker).toString(),html.js("window.workspaceEditor.state.doc.toString()"))
            assertEquals("true",html.js("document.querySelectorAll('.cm-lineNumbers .cm-gutterElement').length>1"))
            html.screenshot("workspace-html-code-preview")
            html.js("document.getElementById('toggle-edit').click()")
            waitDom("!!document.querySelector('.cm-content[contenteditable=true]')")
            document("页面修改")
            html.hideKeyboard()
            click("prepareSave"); waitDom("!!document.querySelector('[data-node=confirm]')")
            runBlocking { app.workspaces.write(workspace.id, "src/note.ts", "外部修改") }
            click("confirm"); native(true)
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('文件已变化')===true")
            assertEquals("外部修改", runBlocking { app.workspaces.read(workspace.id, "src/note.ts") })
            click("cancel");click("backToList");click("backToList")
            waitDom("!!document.querySelector('[data-node=file_0]')")
            waitDom("innerHeight>300 && document.getElementById('content').getBoundingClientRect().height>innerHeight*.5 && document.getElementById('actions').getBoundingClientRect().top>innerHeight*.7")
            html.screenshot("workspace-html-directory")
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible = true }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=directory]')")
            click("directory"); click("file_0"); click("file_0")
            waitDom("window.workspaceEditor?.state.doc.toString()==='外部修改'")
            html.screenshot("workspace-html-editor")
            click("backToList");click("rename_0")
            waitDom("!!document.getElementById('field-destination')")
            field("destination","src/renamed.ts");click("prepareMove");click("confirm");native(true)
            waitDom("document.querySelector('[data-node=file_name_0]')?.textContent==='renamed.ts'")
            assertEquals("外部修改",runBlocking { app.workspaces.read(workspace.id,"src/renamed.ts") })
            assertFalse(runBlocking { app.workspaces.list(workspace.id,"src") }.any { it.name=="note.ts" })
            click("delete_0");click("confirm");native(false)
            assertEquals("外部修改",runBlocking { app.workspaces.read(workspace.id,"src/renamed.ts") })
            click("confirm");native(true)
            waitDom("!!document.querySelector('[data-node=empty]')")
            click("mkdir");waitDom("!!document.getElementById('field-destination')")
            field("destination","src/child");click("prepareDirectory");click("confirm");native(true)
            waitDom("document.querySelector('[data-node=file_name_0]')?.textContent.includes('child')===true")
            runBlocking {
                val shell=app.workspaces.state.value.shells.single { it.workspaceId==workspace.id }
                val job=app.workspaces.run(workspace.id,shell.id,"printf shell-content > src/child/from-shell.txt")
                val completed=withTimeout(10_000) { app.workspaces.jobs.first { list->list.any { it.id==job.id && it.stage in setOf(ShellStage.COMPLETED,ShellStage.FAILED) } } }.single { it.id==job.id }
                assertEquals(ShellStage.COMPLETED,completed.stage)
                assertEquals("shell-content",app.workspaces.read(workspace.id,"src/child/from-shell.txt"))
            }
            click("file_0");waitDom("document.querySelector('[data-node=directory]')?.textContent==='src/child'")
            click("file_0");waitDom("window.workspaceEditor?.state.doc.toString()==='shell-content'")
            html.screenshot("workspace-html-shell-file-preview")
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.workspace" }.enabled)
        } catch(error:Throwable) {
            runCatching {
                println("WORKSPACE_EDITOR_STATE="+html.js("JSON.stringify({editor:!!window.workspaceEditor,tail:window.workspaceEditor?.state.doc.toString().slice(-30),status:document.getElementById('editor-status')?.textContent,contentEditable:document.querySelector('.cm-content')?.contentEditable,active:document.activeElement?.className,height:innerHeight})"))
                html.screenshot("workspace-html-editor-failure")
            }
            throw error
        } finally {
            rule.runOnUiThread { visible = false }
            runBlocking { app.workspaces.unbind(chat) }
        }
    }
}
