package dev.solvit.app

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.solvit.market.EncryptedPluginConfiguration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class McpPageUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun htmlManagesTwoEncryptedConnectionsAcrossReopenAndRequiresNativeConfirmation() {
        val app = rule.activity.application as SolvitApplication
        val id = "dev.solvit.mcp"
        val configuration = EncryptedPluginConfiguration(app)
        runBlocking {
            withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() }
            app.marketplace.disable(id)
            app.marketplace.installLocal(app.assets.open("builtin-plugins/$id.solvit.json").use { it.readBytes() })
            app.marketplace.configure(id, emptyMap()); app.marketplace.enable(id, setOf("network", "workspace", "shell"))
        }
        val html = HtmlPageProbe(rule.activity)
        fun waitDom(expression: String) { rule.waitUntil(10_000) { runCatching { html.js(expression) == "true" }.getOrDefault(false) } }
        fun profiles() = Json.parseToJsonElement(configuration.read(id).getValue("connections")).jsonArray
        fun click(node: String) {
            waitDom("!!document.querySelector('[data-node=$node]')&&!document.querySelector('[data-node=$node]').disabled")
            html.js("document.querySelector('[data-node=$node]').click()")
        }
        fun add(name: String, token: String) {
            click("new"); waitDom("!!document.getElementById('field-name')")
            html.js("(()=>{for(const [id,value]of Object.entries({name:'$name',url:'https://$name.example.invalid/mcp',token:'$token'})){const input=document.getElementById('field-'+id);input.value=value;input.dispatchEvent(new Event('input'))}document.querySelector('[data-node=save]').click()})()")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            waitDom("!!document.querySelector('[data-node=server_0]') && !document.querySelector('[data-node=new]').disabled")
        }
        var visible by mutableStateOf(true)
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("MCP 服务器", { app.marketplace.openPage(id, "main", "zh") }) { visible = false }
            } } }
            waitDom("window.pluginPageReady===true")
            assertTrue(html.secureWindow())
            add("docs", "ui-private-docs"); add("code", "ui-private-code")
            assertEquals(2, profiles().size)
            assertFalse(app.getSharedPreferences("extension_configuration", 0).getString(id, "")!!.contains("ui-private-"))
            assertFalse(html.js("document.body.textContent").contains("ui-private-"))
            assertTrue(app.getSharedPreferences("extension_data.$id", 0).all.isEmpty())
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_web_page").fetchSemanticsNodes().isEmpty() }
            rule.runOnUiThread { visible = true }
            waitDom("window.pluginPageReady===true && document.querySelectorAll('.card').length===2")
            waitDom("innerHeight>300 && document.getElementById('content').getBoundingClientRect().height>innerHeight*.5 && document.getElementById('actions').getBoundingClientRect().top>innerHeight*.7 && Array.from(document.querySelectorAll('.card')).every(n=>n.getBoundingClientRect().bottom<document.getElementById('actions').getBoundingClientRect().top)")
            click("edit_0"); waitDom("!!document.getElementById('field-token')")
            assertEquals("\"\"", html.js("document.getElementById('field-token').value"))
            assertEquals("\"password\"", html.js("document.getElementById('field-token').type"))
            click("save")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugin_page_confirm").performClick()
            waitDom("!!document.querySelector('[data-node=server_1]')")
            assertEquals("ui-private-docs", profiles()[0].jsonObject.getValue("token").jsonPrimitive.content)
            click("toggle_1"); waitDom("document.querySelector('[data-node=state_1]')?.textContent==='已停用'")
            assertTrue(profiles()[1].jsonObject.getValue("disabled").jsonPrimitive.boolean)
            click("delete_1")
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_confirm").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("取消").performClick(); assertEquals(2, profiles().size)
            click("delete_1"); rule.onNodeWithTag("plugin_page_confirm").performClick()
            waitDom("document.querySelectorAll('.card').length===1")
            assertEquals("docs", profiles().single().jsonObject.getValue("name").jsonPrimitive.content)
        } finally {
            rule.runOnUiThread { visible = false }
            runBlocking { app.marketplace.disable(id); app.marketplace.configure(id, emptyMap()) }
        }
    }
}
