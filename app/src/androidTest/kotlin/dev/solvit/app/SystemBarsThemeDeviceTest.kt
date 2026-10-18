package dev.solvit.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses the actual Activity and SolvitTheme, never a test-set MaterialTheme. */
class SystemBarsThemeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shell(command: String): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation()
            .uiAutomation.executeShellCommand(command)).use { it.readBytes() }

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
        // App frame completion alone does not synchronize the separate System UI process.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 2_000)
        shell("mkdir -p /data/local/tmp/solvit-ui")
        shell("screencap -p /data/local/tmp/solvit-ui/$name.png")
        rule.runOnUiThread {
            val window = rule.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals("$name: status icons must follow the actual app surface", light, controller.isAppearanceLightStatusBars)
            assertEquals("$name: navigation icons must follow the actual app surface", light, controller.isAppearanceLightNavigationBars)
            assertFalse("$name: navigation scrim must not add a contrasting strip", window.isNavigationBarContrastEnforced)
        }
        val (statusHeight, navigationHeight, geometry) = rule.runOnUiThread {
            val window = rule.activity.window
            val decor = window.decorView
            val content = rule.activity.findViewById<View>(android.R.id.content)
            fun box(view: View?): String {
                if (view == null) return "absent"
                val position = IntArray(2); view.getLocationOnScreen(position)
                return "${position.toList()}/${view.width}x${view.height}/padding=${view.paddingTop},${view.paddingBottom}"
            }
            val insets = decor.rootWindowInsets
            Triple(insets.getInsets(WindowInsets.Type.statusBars()).top,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                "decor=${box(decor)} content=${box(content)} child=${box((content as? ViewGroup)?.getChildAt(0))} uiFlags=${decor.systemUiVisibility} windowFlags=${window.attributes.flags} fitTypes=${window.attributes.fitInsetsTypes}")
        }
        val bytes = shell("cat /data/local/tmp/solvit-ui/$name.png")
        val image = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertTrue("Expected visible system bars: $geometry", statusHeight > 0 && navigationHeight > 0)
            // Sample a clear left margin, outside icons and chat text, in the actual OS screenshot.
            val x = image.width / 100
            val luminance = listOf(image.height / 2, statusHeight / 2, image.height - navigationHeight / 2)
                .map { ColorUtils.calculateLuminance(image.getPixel(x, it)) }
            assertTrue("$name: app/status/nav backgrounds must follow the selected surface; luminance=$luminance; $geometry",
                luminance.all { if (light) it > .7 else it < .2 })
            // On the visible-bar emulator preset, verify actual navigation controls,
            // not just the WindowInsetsController's requested light/dark flags.
            val background = luminance.last()
            var readableNavigationSamples = 0
            for (row in image.height - navigationHeight until image.height step 2) {
                for (column in 0 until image.width step 2) {
                    val foreground = ColorUtils.calculateLuminance(image.getPixel(column, row))
                    val contrast = (maxOf(background, foreground) + .05) / (minOf(background, foreground) + .05)
                    if (contrast >= 3) readableNavigationSamples++
                }
            }
            assertTrue("$name: actual navigation controls must contrast with their background; samples=$readableNavigationSamples; $geometry",
                readableNavigationSamples >= 16)
        } finally { image.recycle() }
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
