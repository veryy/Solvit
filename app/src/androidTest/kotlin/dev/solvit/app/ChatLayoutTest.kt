package dev.solvit.app

import android.graphics.Bitmap
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Runs against the actual app and OS keyboard, not a mock inset value. */
class ChatLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun ready() {
        rule.waitUntil(30_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }
    private fun screenshot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(rule.activity.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        screenshot("chat-keyboard-floating")
    }
    @Test fun halfWidthDrawerOpensSettingsAndLastSettingsRowCanBeSeen() {
        ready()
        rule.onNodeWithContentDescription("对话历史").performClick()
        rule.waitForIdle()
        val bounds = rule.onNodeWithTag("chat_drawer").fetchSemanticsNode().boundsInRoot
        assertEquals(rule.activity.window.decorView.width / 2f, bounds.width, 3f)
        rule.onNodeWithText("设置", useUnmergedTree = true).performClick()
        rule.onNodeWithText("关于 Solvit").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("覆盖更新保留数据；卸载会删除本机数据，请先导出配置备份。").performScrollTo().assertIsDisplayed()
        screenshot("settings-bottom-insets")
    }
    @Test fun pluginPageCanReachBottomAndReturnToChat() {
        ready()
        rule.onNodeWithContentDescription("插件市场").performClick()
        rule.onNodeWithText("已安装 (6)").performClick()
        rule.onNodeWithText("插件开发").performScrollTo().assertIsDisplayed()
        screenshot("plugins-bottom-insets")
        rule.onNodeWithContentDescription("关闭插件页面").performClick()
        rule.onNodeWithTag("chat_composer").assertIsDisplayed()
    }
}
