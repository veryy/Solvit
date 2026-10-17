package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import dev.solvit.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.market.BuiltinWorkspaceManager
import dev.solvit.market.WorkspacePageBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LinuxEnvironmentHtmlTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun environmentHtmlWorksBeforeWorkspaceCreationAndDeclinedDownloadDoesNothing() {
        val app = rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val chat = "linux-page-${java.util.UUID.randomUUID()}"
        assertNull(runBlocking { app.workspaces.bound(chat) })
        assertTrue("APK must package an executable for x86_64", app.linuxEnvironment.state.value.supported)
        assertFalse(app.linuxEnvironment.state.value.installed)
        val manager = BuiltinWorkspaceManager(app, "dev.solvit.environment")
        val html = HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true)
        fun waitDom(expression: String) { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("Linux", { manager.openPage("zh",
                    WorkspacePageBridge(app.workspaces, app.pluginDrafts, chat, linux = app.linuxEnvironment)) }) { visible = false }
            } } }
            waitDom("window.pluginPageReady===true && !!document.querySelector('[data-node=install]')")
            waitDom("document.querySelector('[data-node=status]').textContent.includes('尚未安装')")
            assertEquals("false", html.js("!!document.querySelector('[data-node=newShell]')"))
            // DOM existence alone passed even when WebView painted only the footer.
            // Check real bounds and hit-testing, then use a screen touch rather than JS click.
            val geometry = "(()=>{const main=document.querySelector('main'),b=document.querySelector('[data-node=install]'),r=b.getBoundingClientRect();return JSON.stringify({viewport:innerHeight,body:document.body.getBoundingClientRect().height,main:main.getBoundingClientRect().height,button:{top:r.top,bottom:r.bottom,height:r.height},dvh:CSS.supports('height','100dvh')})})()"
            android.util.Log.i("SolvitLinuxPage", "LAYOUT_GEOMETRY|" + html.js(geometry))
            waitDom("(()=>{const b=document.querySelector('[data-node=install]'),r=b.getBoundingClientRect();return r.height>20 && r.top>=0 && r.bottom<innerHeight && document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)===b})()")
            html.screenshot("linux-plugin-before-install")
            html.tapElement("[data-node=install]")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("下载和安装需要网络与手机存储空间。继续？").assertIsDisplayed()
            rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
            waitDom("!document.querySelector('[data-node=install]').disabled")
            assertEquals("IDLE", app.linuxEnvironment.state.value.stage)
            assertFalse(app.linuxEnvironment.state.value.installed)
            assertNull(runBlocking { app.workspaces.bound(chat) })
        } finally { rule.runOnUiThread { visible = false } }
    }
    @Test fun environmentWorkbenchKeepsDisclosureLogAndHonestProgressWithoutInstallingOnRefresh() {
        val app=rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
        val chat="linux-workbench-${java.util.UUID.randomUUID()}"
        val mutations=java.util.concurrent.atomic.AtomicInteger()
        val output=(1..300).joinToString("\n") { "fixture installation line $it" }
        val service=object:LinuxEnvironmentService {
            override val state=MutableStateFlow(LinuxEnvironmentState(true,true,"READY",output=output))
            override suspend fun install() { mutations.incrementAndGet() }
            override suspend fun installSoftware(software:LinuxSoftware) { mutations.incrementAndGet() }
            override suspend fun cancel() { mutations.incrementAndGet() }
            override fun command(workspaceDirectory:String,command:String,cwd:String):HostProcessPlan=error("UI must not start commands")
            override fun terminal(workspaceDirectory:String):HostProcessPlan=error("UI must not start a terminal")
            override fun process(workspaceDirectory:String,executable:String,arguments:List<String>,environment:Map<String,String>,cwd:String):HostProcessPlan=error("UI must not start processes")
        }
        val manager=BuiltinWorkspaceManager(app,"dev.solvit.environment")
        val html=HtmlPageProbe(rule.activity)
        var visible by mutableStateOf(true);var dark by mutableStateOf(false)
        fun waitDom(expression:String) { rule.waitUntil(10_000) { runCatching { html.js(expression)=="true" }.getOrDefault(false) } }
        fun tap(selector:String) {
            html.js("document.querySelector(${kotlinx.serialization.json.JsonPrimitive(selector)}).scrollIntoView({block:'center'})")
            waitDom("(()=>{const e=document.querySelector(${kotlinx.serialization.json.JsonPrimitive(selector)}),r=e.getBoundingClientRect();return r.height>=44&&r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight&&e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()")
            html.tapElement(selector)
        }
        fun refresh() { tap("[data-node=refresh]") }
        fun decline() {
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
            waitDom("!document.querySelector('[data-node=refresh]').disabled")
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) {
                if(visible)PluginPageScreen("Linux",{manager.openPage("zh",WorkspacePageBridge(app.workspaces,app.pluginDrafts,chat,linux=service))}) { visible=false }
            } } }
            waitDom("!!document.querySelector('.environment-page')&&!document.querySelector('[data-node=refresh]').disabled")
            assertEquals("true",html.js("Array.from(document.querySelectorAll('details')).every(e=>!e.open)"))
            assertEquals("false",html.js("!!document.querySelector('[data-node=newShell]')"))
            html.screenshot("linux-plugin-ready-collapsed")
            tap("#environment-software > summary")
            assertEquals(0,mutations.get())
            tap("[data-node=software_PYTHON]");decline()
            waitDom("document.getElementById('environment-software').open")
            assertEquals(0,mutations.get())
            tap("#environment-software > summary")
            tap("#environment-log > summary")
            waitDom("document.querySelector('[data-node=log]').scrollHeight>document.querySelector('[data-node=log]').clientHeight+100")
            html.js("document.querySelector('[data-node=log]').scrollTop=80")
            refresh()
            waitDom("document.getElementById('environment-log').open&&Math.abs(document.querySelector('[data-node=log]').scrollTop-80)<2")
            service.state.value=service.state.value.copy(stage="DOWNLOADING",bytes=512,total=1024)
            refresh();waitDom("document.querySelector('progress')?.value===50")
            assertEquals("true",html.js("document.querySelector('[data-node=software_PYTHON]').disabled"))
            service.state.value=service.state.value.copy(total=null)
            refresh();waitDom("!!document.querySelector('progress')&&!document.querySelector('progress').hasAttribute('value')")
            html.screenshot("linux-plugin-unknown-download-progress")
            service.state.value=service.state.value.copy(stage="INITIALIZING",total=1024)
            refresh();waitDom("document.querySelector('[data-node=status]').textContent.includes('安装基础软件')&&!document.querySelector('progress').hasAttribute('value')&&!document.querySelector('[data-node=progress]')")
            tap("#environment-log > summary")
            service.state.value=service.state.value.copy(stage="FAILED",failure="fixture download failed")
            refresh();waitDom("document.getElementById('environment-log').open&&document.querySelector('[role=alert]').textContent==='fixture download failed'")
            tap("#environment-log > summary");refresh()
            waitDom("!document.getElementById('environment-log').open&&!document.querySelector('[data-node=refresh]').disabled")
            assertEquals(0,mutations.get())
            val light=html.js("getComputedStyle(document.body).backgroundColor")
            rule.runOnUiThread { dark=true }
            waitDom("getComputedStyle(document.body).backgroundColor!==$light")
            rule.mainClock.advanceTimeBy(1_000);rule.waitForIdle();html.setTextZoom(150)
            tap("#environment-log > summary")
            waitDom("(()=>{const r=document.querySelector('[data-node=log]').getBoundingClientRect();return r.width>0&&r.left>=0&&r.right<=innerWidth})()")
            html.screenshot("linux-plugin-dark-large-text-failure-log")
            assertEquals(0,mutations.get())
            assertNull(runBlocking { app.workspaces.bound(chat) })
        } finally { html.setTextZoom(100);rule.runOnUiThread { visible=false } }
    }

}
