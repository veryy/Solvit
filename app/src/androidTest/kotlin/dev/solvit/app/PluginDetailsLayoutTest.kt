package dev.solvit.app

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.ExtensionPage
import dev.solvit.core.ExtensionSetting
import dev.solvit.core.InstalledExtension
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Rendering and navigation checks; actual installation/page persistence stays in PluginPageUiTest. */
class PluginDetailsLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    @Test fun disabledDetailsKeepPrimaryActionVisibleWithLargeTextAndLongPermissions() {
        var toggleCount = 0
        var openCount = 0
        var uninstallCount = 0
        val extension = InstalledExtension("test.layout", "工作区与 Shell", "1.0.0",
            setOf("storage", "network", "workspace", "git", "shell", "plugins", "model", "agent.read", "agent"),
            false, emptyList(), settings = listOf(ExtensionSetting("path", "Path", "text")),
            pages = listOf(ExtensionPage("files", "文件与工作目录"), ExtensionPage("terminal", "交互终端")))
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MaterialTheme {
                        InstalledPluginDetailsDialog(extension, false, {}, { toggleCount++ }, {}, { openCount++ }, { uninstallCount++ })
                    }
                }
            }
        }
        rule.onNodeWithTag("plugin_toggle").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("open_plugin_page_test.layout_files").assertIsNotEnabled()
        rule.onNodeWithText("启用插件后可打开它的页面。").assertIsDisplayed()
        rule.onNodeWithTag("plugin_details_content").performScrollToNode(hasTestTag("plugin_permissions_toggle"))
        rule.onNodeWithTag("plugin_permissions_toggle").performClick()
        rule.onNodeWithTag("plugin_permissions_details").assertExists()
        // A long expanded permission section must scroll while the primary action stays reachable.
        rule.onNodeWithTag("plugin_details_content").performScrollToNode(hasTestTag("plugin_uninstall"))
        rule.onNodeWithTag("plugin_uninstall").assertIsDisplayed()
        rule.onNodeWithTag("plugin_toggle").assertIsDisplayed().performClick()
        assertEquals(1, toggleCount); assertEquals(0, openCount); assertEquals(0, uninstallCount)
        screenshot("plugin-details-large-text")
    }
    @Test fun enabledDetailsOpenTheChosenPluginPageAndKeepUninstallSeparate() {
        var opened: ExtensionPage? = null
        var uninstallCount = 0
        val page = ExtensionPage("main", "插件工作台")
        val extension = InstalledExtension("test.enabled-layout", "插件示例", "1.0.0", emptySet(), true, emptyList(), pages = listOf(page))
        rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
            InstalledPluginDetailsDialog(extension, false, {}, {}, {}, { opened = it }, { uninstallCount++ })
        } } }
        rule.onNodeWithTag("plugin_permissions_details").assertDoesNotExist()
        rule.onNodeWithTag("open_plugin_page_test.enabled-layout_main").assertIsDisplayed().performClick()
        assertEquals(page, opened); assertEquals(0, uninstallCount)
        screenshot("plugin-details-enabled")
    }
}
