package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import dev.solvit.core.ShellStage
import dev.solvit.core.ExtensionPageSession
import dev.solvit.core.ExtensionPageSnapshot
import dev.solvit.core.PageWorkspaceAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
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
        fun observedSession(): ExtensionPageSession {
            val bridge=WorkspacePageBridge(app.workspaces, app.pluginDrafts, chat)
            val observed=object : PageWorkspaceAccess by bridge {
                override suspend fun query(operation: String, argumentsJson: String, authorize: suspend () -> Unit): String {
                    try { return bridge.query(operation,argumentsJson,authorize) }
                    catch (failure: Throwable) {
                        android.util.Log.i("WORKSPACE_PAGE_DIAG", "query=$operation type=${failure.javaClass.name} at=${failure.stackTrace.take(8).joinToString()}")
                        throw failure
                    }
                }
            }
            val actual = manager.openPage("zh", observed)
            return object : ExtensionPageSession by actual {
                override suspend fun dispatch(revision: Long, nodeId: String, value: String?, fields: Map<String, String>, confirmed: Boolean): ExtensionPageSnapshot {
                    try { return actual.dispatch(revision, nodeId, value, fields, confirmed) }
                    catch (failure: Throwable) {
                        // Types and code locations only; never echo arbitrary host/script messages or field values.
                        var cause: Throwable? = failure
                        repeat(4) {
                            cause?.let { current ->
                                android.util.Log.i("WORKSPACE_PAGE_DIAG", "dispatch=$nodeId revision=$revision type=${current.javaClass.name} at=${current.stackTrace.take(8).joinToString()}")
                                cause=current.cause
                            }
                        }
                        throw failure
                    }
                }
            }
        }
        val html = HtmlPageProbe(rule.activity)
        val initialText = "<script>window.executed=true</script>\n第一版🙂\n" + "const value = 1; // 中文\n".repeat(3000)
        val marker = "IME_EDIT"
        var visible by mutableStateOf(true)
        // This fixture contains no real chat or configuration data.
        fun imeState(phase: String) {
            val snapshot = html.js("""(()=>{const e=window.workspaceEditor,s=e?.state,d=s?.doc.toString()??'';
                const line=document.querySelector('.cm-line:last-child'),r=line?.getBoundingClientRect();
                return {phase:${JsonPrimitive(phase)},length:d.length,marker:d.indexOf('$marker'),tail:d.slice(-100),
                  selection:s?.selection.main.toJSON(),focused:e?.hasFocus,composing:e?.composing,
                  viewport:e?.viewport,active:document.activeElement?.className,
                  lastLine:line?.textContent,lineTop:r?.top,lineBottom:r?.bottom,height:innerHeight}})()""")
            android.util.Log.i("WORKSPACE_IME_DIAG", snapshot)
        }
        fun pageState(phase: String) {
            android.util.Log.i("WORKSPACE_PAGE_DIAG", html.js("""(()=>{const s=window.workspaceDiagnosticState;
                return {phase:${JsonPrimitive(phase)},token:s?.token,busy:s?.busy,
                  directory:document.querySelector('[data-node=directory]')?.textContent,
                  first:document.querySelector('[data-node=file_name_0]')?.textContent,
                  buttons:Array.from(document.querySelectorAll('button')).map(b=>[b.dataset.node,b.disabled]),
                  alerts:Array.from(document.querySelectorAll('[role=alert]')).map(n=>n.textContent),
                  message:document.querySelector('[data-node=message]')?.textContent,
                  reply:window.workspaceDiagnosticReply}})()"""))
        }
        fun waitDom(expression: String) {
            try { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
            catch (failure: Throwable) {
                runCatching { pageState(expression);html.screenshot("workspace-editor-page-failed").recycle() }
                throw failure
            }
        }
        fun click(id: String) {
            waitDom("!!document.querySelector('[data-node=$id]') && !document.querySelector('[data-node=$id]').disabled")
            if(id=="file_0")pageState("before-file-open")
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
                if (visible) PluginPageScreen("工作区", { observedSession() }) { visible = false }
            } } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=create]')")
            html.js("""(()=>{const update=window.solvitPageUpdate,onmessage=solvitHost.onmessage;
                window.solvitPageUpdate=s=>{window.workspaceDiagnosticState=s;update(s)};
                solvitHost.onmessage=e=>{window.workspaceDiagnosticReply=e.data;onmessage(e)}})()""")
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
            imeState("before-tap")
            html.tapElement(".cm-line:last-child")
            rule.waitUntil(10_000) { html.keyboardVisible() }
            imeState("keyboard-visible")
            InstrumentationRegistry.getInstrumentation().sendStringSync(marker)
            try {
                waitDom("window.workspaceEditor.state.doc.toString().endsWith('$marker')")
            } catch (failure: Throwable) {
                runCatching { imeState("input-failed"); html.screenshot("workspace-editor-ime-failed").recycle() }
                throw failure
            }
            imeState("input-complete")
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
            assertEquals(initialText+marker,Json.parseToJsonElement(html.js("window.workspaceEditor.state.doc.toString()")).jsonPrimitive.content)
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
            runBlocking { app.workspaces.write(workspace.id,"docs/readme.md","tree-document") }
            waitDom("!!document.getElementById('tree-toggle') && !document.getElementById('tree-toggle').disabled")
            html.tapElement("#tree-toggle")
            waitDom("document.getElementById('file-tree').classList.contains('open')")
            html.tapElement("#tree-refresh")
            waitDom("!!document.querySelector('[data-expand=docs]')")
            html.tapElement("[data-expand=docs]")
            waitDom("!!document.querySelector('[data-open=\"docs/readme.md\"]')")
            assertEquals("\"shell-content\"",html.js("window.workspaceEditor.state.doc.toString()"))
            html.screenshot("workspace-html-expanded-file-tree")
            html.tapElement("[data-open=\"docs/readme.md\"]")
            waitDom("window.workspaceEditor?.state.doc.toString()==='tree-document'")
            waitDom("!document.getElementById('file-tree').classList.contains('open')")
            html.screenshot("workspace-html-tree-selected-document")
            assertFalse(app.marketplace.installed.value.first { it.id == "dev.solvit.workspace" }.enabled)
        } finally {
            rule.runOnUiThread { visible = false }
            runBlocking { app.workspaces.unbind(chat) }
        }
    }
}
