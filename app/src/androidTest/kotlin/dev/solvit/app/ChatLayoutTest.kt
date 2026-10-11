package dev.solvit.app

import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*


/** Runs against the actual app and OS keyboard, not a mock inset value. */
class ChatLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        rule.waitUntil(30_000) { rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    private fun screenshot(name: String) {
        require(name.matches(Regex("[A-Za-z-]+")))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    @Test fun startupFocusesInputAndFloatingComposerStaysAboveRealKeyboard() {
        ready()
        rule.onNode(hasSetTextAction()).assertIsFocused()
        rule.waitUntil(10_000) { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
        val bounds = rule.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
        val decor = rule.activity.window.decorView
        val ime = decor.rootWindowInsets.getInsets(WindowInsets.Type.ime()).bottom
        assertTrue("Composer must float with margins", bounds.left > 0 && bounds.right < decor.width)
        assertTrue("Composer overlaps keyboard: $bounds, IME=$ime", bounds.bottom <= decor.height - ime + 1)
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
        rule.onNode(hasText("管理角色与 Skills", substring = true)).assertDoesNotExist()
        screenshot("chat-keyboard-floating")
    }
    @Test fun wideDrawerOpensSettingsAndLastSettingsRowCanBeSeen() {
        ready()
        rule.onNodeWithContentDescription("对话历史").performClick()
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("chat_drawer").fetchSemanticsNode().boundsInRoot
        assertEquals(rule.activity.window.decorView.width * 0.75f, bounds.width, 3f)
        rule.onNodeWithText("设置", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText("关于 Solvit"))
        rule.onNodeWithTag("settings_list").performScrollToNode(hasText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。"))
        rule.onNodeWithText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。").performScrollTo()
        screenshot("settings-bottom-insets")
        rule.onNodeWithText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。").assertIsDisplayed()
    }
    @Test fun pluginPageCanReachBottomAndReturnToChat() {
        ready()
        rule.onNodeWithContentDescription("插件市场").performClick()
        rule.onNode(hasText("已安装 (", substring = true)).performClick()
        val last = (rule.activity.application as SolvitApplication).marketplace.installed.value.last().name
        rule.onNodeWithTag("plugins_list").performScrollToNode(hasText(last))
        rule.onNodeWithText(last).assertIsDisplayed()
        rule.onAllNodesWithText("卸载").onLast().assertIsDisplayed()
        screenshot("plugins-bottom-insets")
        rule.onNodeWithContentDescription("关闭插件页面").performClick()
        rule.onNodeWithTag("chat_composer").assertIsDisplayed()
    }
    @Test fun workspaceTerminalRunsRealAndroidShellInTheBoundWorkspace() {
        ready()
        rule.waitUntil(15_000) { rule.onAllNodes(hasText("工作区：", substring = true) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("工作区：", substring = true)).performClick()
        rule.onNodeWithTag("shell_command").performClick().performTextInput("printf hello-solvit")
        rule.onNodeWithText("运行").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("hello-solvit").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("hello-solvit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("＋ Shell").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Shell 2").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Shell 2").assertIsDisplayed()
        screenshot("workspace-shell-real-process")
        rule.onNodeWithText("工作目录").performClick()
        rule.onNodeWithText("新建文本").assertIsDisplayed()
        rule.onNodeWithText("插件草稿", substring = true).assertIsDisplayed()
        screenshot("workspace-directory")
    }
    @Test fun javascriptPluginCanCreateShellAndRunActualAndroidCommand() {
        ready()
        val app = rule.activity.application as SolvitApplication
        runBlocking(Dispatchers.IO) {
            val old = app.marketplace.installed.value.single { it.id == "dev.solvit.workspace" }.enabled
            app.systemExecution.setMode("dev.solvit.workspace", ExecutionMode.APP)
            try {
                if (!old) app.marketplace.enable("dev.solvit.workspace", setOf("workspace", "shell"))
                val workspace = app.workspaces.forChat("javascript-emulator-test")
                val authorization = object : ToolAuthorization {
                    override suspend fun authorize(descriptor: ToolDescriptor): Boolean {
                        assertEquals(ExecutionMode.APP, descriptor.executionMode)
                        return true
                    }
                }
                withContext(ConversationContext("javascript-emulator-test", workspace.id)) {
                    val shell = Json.parseToJsonElement(app.marketplace.runTool("dev_solvit_workspace_create_shell", """{"name":"JS Shell"}""", authorization)).jsonObject.getValue("id").jsonPrimitive.content
                    val job = Json.parseToJsonElement(app.marketplace.runTool("dev_solvit_workspace_shell_start", buildJsonObject {
                        put("shellId", shell); put("command", "printf js-native-shell")
                    }.toString(), authorization)).jsonObject.getValue("id").jsonPrimitive.content
                    val result = withTimeout(10_000) { app.workspaces.jobs.first { list -> list.any { it.id == job && it.stage == ShellStage.COMPLETED } } }.single { it.id == job }
                    assertEquals("js-native-shell", result.output.joinToString("") { it.text })
                    assertEquals("dev.solvit.workspace", result.owner)
                    assertEquals(workspace.id, result.workspaceId)
                    assertEquals(shell, result.shellSessionId)
                }
            } finally { if (!old) app.marketplace.disable("dev.solvit.workspace") }
        }
    }
}
