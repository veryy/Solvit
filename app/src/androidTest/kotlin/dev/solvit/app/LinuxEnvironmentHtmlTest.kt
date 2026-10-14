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
            html.screenshot("linux-plugin-before-install")
            html.js("document.querySelector('[data-node=install]').click()")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("取消").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isEmpty() }
            waitDom("!document.querySelector('[data-node=install]').disabled")
            assertEquals("IDLE", app.linuxEnvironment.state.value.stage)
            assertFalse(app.linuxEnvironment.state.value.installed)
            assertNull(runBlocking { app.workspaces.bound(chat) })
        } finally { rule.runOnUiThread { visible = false } }
    }
}
