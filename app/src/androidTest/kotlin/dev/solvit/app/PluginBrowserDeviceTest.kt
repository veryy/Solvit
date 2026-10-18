package dev.solvit.app

import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.MarketEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Activity and installed plugin service. Market metadata fixture does not test deployment/download. */
class PluginBrowserDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().decodeToString() }
    private fun ready() {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun open() {
        rule.onNodeWithContentDescription("插件市场").performClick()
        rule.onNodeWithTag("plugins_search").assertIsDisplayed()
    }
    private fun query() = rule.onNodeWithTag("plugins_search").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun screenshot(name: String) {
        rule.waitForIdle(); shell("mkdir -p /data/local/tmp/solvit-ui")
        shell("screencap -p /data/local/tmp/solvit-ui/$name.png")
    }
    private fun scrollTo(tag: String) {
        rule.onNodeWithTag("plugins_list").performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    @Test fun installedSearchStatusAndDetailReturnKeepTheActualPluginAtLargeSystemFont() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val clock = app.marketplace.installed.value.single { it.id == "dev.solvit.clock" }
        val oldScale = shell("settings get system font_scale").trim()
        require(oldScale == "null" || oldScale.toFloatOrNull() != null)
        try {
            if (!clock.enabled) runBlocking { app.marketplace.enable(clock.id, clock.permissions) }
            shell("settings put system font_scale 1.5")
            rule.waitUntil(15_000) { rule.runOnUiThread { rule.activity.resources.configuration.fontScale == 1.5f } }
            ready(); open()
            rule.onNodeWithTag("plugins_installed_tab").performClick()
            rule.onNodeWithTag("plugins_search").performClick().performTextReplacement("  DEV.SOLVIT.CLOCK  ")
            // Actual OS IME stays open while typing; Search dismisses it to expose all results.
            rule.waitUntil(10_000) { rule.runOnUiThread {
                WindowInspector.getGlobalWindowViews().any { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            } }
            rule.onNodeWithTag("installed_plugin_${clock.id}").assertIsDisplayed()
            screenshot("plugins-search-large-text-keyboard")
            val result = rule.onNodeWithTag("installed_plugin_${clock.id}").fetchSemanticsNode()
            val coordinates = result.layoutInfo.coordinates
            val viewport = rule.onNodeWithTag("plugins_list").fetchSemanticsNode().boundsInRoot
            val top = coordinates.localToRoot(Offset.Zero).y
            assertTrue("Search result must be fully visible above keyboard: top=$top height=${coordinates.size.height} viewport=$viewport",
                top >= viewport.top && top + coordinates.size.height <= viewport.bottom + 1f)
            rule.onNodeWithTag("plugins_search").performImeAction()
            rule.waitUntil(10_000) { rule.runOnUiThread {
                WindowInspector.getGlobalWindowViews().none { it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true }
            } && rule.onAllNodesWithTag("plugins_filter_1").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugins_filter_1").performClick()
            rule.onNodeWithTag("plugins_filter_1").assertIsSelected()
            rule.onNodeWithTag("plugins_result_count").assertTextEquals("显示 1 / ${app.marketplace.installed.value.size} 个插件")
            rule.onNodeWithTag("installed_plugin_${clock.id}").performClick()
            rule.onNodeWithTag("plugin_toggle").assertIsDisplayed().assertTextEquals("停用")
            rule.onNodeWithText("关闭", useUnmergedTree = true).performClick()
            rule.onNodeWithTag("plugins_filter_1").assertIsSelected()
            assertEquals("  DEV.SOLVIT.CLOCK  ", query())
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("plugins_search").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("plugins_filter_1").assertIsSelected()
            assertEquals("  DEV.SOLVIT.CLOCK  ", query())
            rule.onNodeWithTag("installed_plugin_${clock.id}").assertIsDisplayed()
            rule.onNodeWithTag("plugins_filter_2").performClick()
            rule.onNodeWithTag("plugins_no_results").assertIsDisplayed()
            rule.onNodeWithTag("installed_plugin_${clock.id}").assertDoesNotExist()
            screenshot("plugins-search-no-results-large-text")
            rule.onNodeWithTag("plugins_reset_search").performClick()
            assertEquals("", query())
            rule.onNodeWithTag("plugins_filter_0").assertIsSelected()
            scrollTo("installed_plugin_${clock.id}")
        } finally {
            if (!clock.enabled) runBlocking { app.marketplace.disable(clock.id) }
            shell(if (oldScale == "null") "settings delete system font_scale" else "settings put system font_scale $oldScale")
        }
    }

    @Test fun marketAndInstalledKeepIndependentQueriesAndScrollOnTabAndDetailReturn() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val vm = rule.runOnUiThread { ViewModelProvider(rule.activity)[MarketplaceViewModel::class.java] }
        rule.waitUntil(40_000) { !vm.loading.value }
        val original = vm.catalog.value
        val oldError = vm.error.value
        val plugins = app.marketplace.installed.value
        assertTrue("Exercise scrolling through real packaged plugins", plugins.size >= 9)
        val fixture = plugins.mapIndexed { index, p -> MarketEntry(p.id, p.name,
            if (index == 0) "catalog-feature-demo" else "目录交互夹具", p.version, p.permissions, emptySet(), "0".repeat(64), 1, "/fixture") }
        try {
            rule.runOnUiThread { vm.catalog.value = fixture; vm.error.value = null }
            open()
            rule.onNodeWithTag("plugins_search").performTextReplacement("FEATURE-DEMO")
            rule.onNodeWithTag("plugins_search").performImeAction()
            rule.onNodeWithTag("market_plugin_${fixture.first().id}").assertIsDisplayed()
            rule.onNodeWithTag("plugins_result_count").assertTextEquals("显示 1 / ${fixture.size} 个插件")
            rule.onNodeWithTag("plugins_installed_tab").performClick()
            assertEquals("", query())
            rule.onNodeWithTag("plugins_search").performTextReplacement("DEV.SOLVIT.CLOCK")
            rule.onNodeWithTag("plugins_search").performImeAction()
            rule.onNodeWithTag("installed_plugin_dev.solvit.clock").assertIsDisplayed()
            rule.onNodeWithTag("plugins_market_tab").performClick()
            assertEquals("FEATURE-DEMO", query())
            rule.onNodeWithContentDescription("清除搜索").performClick()
            val last = "market_plugin_${fixture.last().id}"
            scrollTo(last)
            val before = rule.onNodeWithTag(last).fetchSemanticsNode().boundsInRoot.top
            screenshot("plugins-market-scroll")
            rule.onNodeWithTag("plugins_installed_tab").performClick()
            assertEquals("DEV.SOLVIT.CLOCK", query())
            rule.onNodeWithTag("installed_plugin_dev.solvit.clock").assertIsDisplayed()
            rule.onNodeWithTag("plugins_market_tab").performClick()
            assertEquals("", query())
            rule.onNodeWithTag(last).assertIsDisplayed()
            assertEquals(before, rule.onNodeWithTag(last).fetchSemanticsNode().boundsInRoot.top, 1f)
            rule.onNodeWithTag(last).performClick()
            rule.onNodeWithText("关闭", useUnmergedTree = true).performClick()
            rule.onNodeWithTag(last).assertIsDisplayed()
            assertEquals(before, rule.onNodeWithTag(last).fetchSemanticsNode().boundsInRoot.top, 1f)
            rule.activityRule.scenario.recreate()
            rule.waitUntil(15_000) { rule.onAllNodesWithTag("plugins_search").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(last).assertIsDisplayed()
            screenshot("plugins-market-restored")
        } finally { rule.runOnUiThread { vm.catalog.value = original; vm.error.value = oldError } }
    }
}
