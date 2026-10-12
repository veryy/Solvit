package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

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
        val initialText = "<script>window.executed=true</script>\n第一版\n" + "line\n".repeat(700)
        var visible by mutableStateOf(true)
        fun waitDom(expression: String) { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        fun click(id: String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            html.js("document.querySelector('[data-node=$id]').click()")
        }
        fun field(id: String, text: String) { html.js("(()=>{const input=document.getElementById('field-$id');input.value=${JsonPrimitive(text)};input.dispatchEvent(new Event('input'))})()") }
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
            click("new"); waitDom("!!document.getElementById('field-content')")
            field("path", "src/note.txt"); field("content", initialText)
            click("prepareSave"); waitDom("!!document.querySelector('[data-node=confirm]')")
            assertEquals("\"undefined\"", html.js("typeof window.executed"))
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            click("confirm"); native(false)
            assertTrue(runBlocking { app.workspaces.list(workspace.id) }.isEmpty())
            click("confirm"); native(true)
            waitDom("!!document.querySelector('[data-node=file_0]')")
            assertEquals(initialText, runBlocking { app.workspaces.read(workspace.id, "src/note.txt") })
            click("file_0"); waitDom("document.querySelector('[data-node=directory]')?.textContent==='src'")
            click("file_0"); waitDom("!!document.getElementById('field-content')")
            field("content", "页面修改")
            runBlocking { app.workspaces.write(workspace.id, "src/note.txt", "外部修改") }
            click("prepareSave"); waitDom("!!document.querySelector('[data-node=confirm]')")
            click("confirm"); native(true)
            waitDom("document.querySelector('[data-node=message]')?.textContent.includes('文件已变化')===true")
            assertEquals("外部修改", runBlocking { app.workspaces.read(workspace.id, "src/note.txt") })
            click("cancel"); click("backToList")
            waitDom("!!document.querySelector('[data-node=file_0]')")
            waitDom("innerHeight>300 && document.getElementById('content').getBoundingClientRect().height>innerHeight*.5 && document.getElementById('actions').getBoundingClientRect().top>innerHeight*.7")
            html.screenshot("workspace-html-directory")
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible = true }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=directory]')")
            click("directory"); click("file_0"); click("file_0")
            waitDom("document.getElementById('field-content')?.value==='外部修改'")
            html.screenshot("workspace-html-editor")
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.workspace" }.enabled)
        } finally {
            rule.runOnUiThread { visible = false }
            runBlocking { app.workspaces.unbind(chat) }
        }
    }
}
