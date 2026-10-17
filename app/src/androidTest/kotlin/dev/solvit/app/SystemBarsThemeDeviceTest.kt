package dev.solvit.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the actual Activity and SolvitTheme, never a test-set MaterialTheme. */
class SystemBarsThemeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun mainPage() {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("chat_composer").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings_list").fetchSemanticsNodes().isEmpty() }
        rule.runOnUiThread {
            val activity = rule.activity
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        rule.waitUntil(10_000) {
            rule.runOnUiThread { rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true }
        }
        rule.waitForIdle()
    }

    private fun systemTheme(dark: Boolean) {
        shell("cmd uimode night ${if (dark) "yes" else "no"}")
        rule.waitUntil(15_000) {
            rule.runOnUiThread {
                (rule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark
            }
        }
        mainPage()
    }

    private fun chooseTheme(mode: String) {
        rule.onNodeWithContentDescription("设置").performClick()
        rule.onNodeWithTag("settings_list").performQueuedScrollToNode(rule, hasText("外观"))
        rule.onNodeWithText("外观").performClick()
        rule.onNodeWithTag("theme_$mode").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodes(hasTestTag("theme_$mode") and isSelected()).fetchSemanticsNodes().size == 1
        }
        rule.onNodeWithContentDescription("返回设置").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("theme_settings").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithContentDescription("返回聊天").performClick()
        mainPage()
    }

    private fun verifyBars(name: String, light: Boolean) {
        // Capture only after a committed frame from the real Activity window.
        runBlocking {
            val frame = CompletableDeferred<Unit>()
            rule.runOnUiThread {
                val root = rule.activity.window.decorView
                check(root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { frame.complete(Unit) }
                root.invalidate()
            }
            withTimeout(10_000) { frame.await() }
        }
        shell("mkdir -p /data/local/tmp/solvit-ui")
        shell("screencap -p /data/local/tmp/solvit-ui/$name.png")
        rule.runOnUiThread {
            val window = rule.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals("$name: status icons must follow the actual app surface", light, controller.isAppearanceLightStatusBars)
            assertEquals("$name: navigation icons must follow the actual app surface", light, controller.isAppearanceLightNavigationBars)
            assertFalse("$name: navigation scrim must not add a contrasting strip", window.isNavigationBarContrastEnforced)
        }
    }

    @Test fun manualThemeAndSystemThemeKeepMainWindowBarsReadableAfterReturnAndRecreation() {
        mainPage()
        val app = rule.activity.application as SolvitApplication
        val originalTheme = app.appearance.theme.value
        val originalDynamic = app.appearance.dynamicColors.value
        val originalNight = (app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).nightMode
        try {
            rule.runOnUiThread { app.appearance.setTheme("light"); app.appearance.setDynamicColors(false) }
            systemTheme(false)
            // Start with a real light-system Activity so the opposite app mode is observable.
            rule.activityRule.scenario.recreate()
            mainPage()
            chooseTheme("dark")
            verifyBars("main-theme-dark-system-light", light = false)
            rule.activityRule.scenario.recreate()
            mainPage()
            assertEquals("dark", AppearanceStore(app).theme.value)
            verifyBars("main-theme-dark-recreated", light = false)

            systemTheme(true)
            chooseTheme("light")
            verifyBars("main-theme-light-system-dark", light = true)
            chooseTheme("system")
            verifyBars("main-theme-follow-system-dark", light = false)
            systemTheme(false)
            verifyBars("main-theme-follow-system-light", light = true)
        } finally {
            rule.runOnUiThread { app.appearance.setTheme(originalTheme); app.appearance.setDynamicColors(originalDynamic) }
            shell("cmd uimode night ${when (originalNight) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" }}")
        }
    }
}
