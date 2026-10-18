package dev.solvit.app

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual main Activity, live configuration service and OS keyboard; no replacement content. */
class ChatFirstUseDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).use { it.readBytes().decodeToString() }

    private fun ready() {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("chat_input").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        shell("mkdir -p /data/local/tmp/solvit-ui")
        shell("screencap -p /data/local/tmp/solvit-ui/$name.png")
    }

    private fun newChat() {
        rule.onNodeWithContentDescription("对话历史").performClick()
        rule.onNodeWithTag("new_chat").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("chat_drawer").fetchSemanticsNodes().isEmpty() ||
            !rule.onNodeWithTag("chat_drawer").isDisplayed() }
        ready()
    }

    private fun fixture() = ModelProfilesState(listOf(ModelProfile("first-use", "首次使用", ModelConfig(
        baseUrl = "https://example.com/v1", model = "first-use-fixture"
    ))), mapOf(ModelTask.CHAT to "first-use"), configured = false)

    @Test fun unsavedModelDoesNotAppearConnectedAndSavedModelSurvivesRecreation() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val original = app.modelProfiles.state.value
        try {
            runBlocking { app.modelProfiles.replace(fixture()) }
            newChat()
            rule.runOnUiThread {
                val activity = rule.activity
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            rule.waitUntil(10_000) { rule.runOnUiThread {
                rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
            } }
            screenshot("chat-first-use-unsaved")
            rule.onNodeWithText("first-use-fixture").assertDoesNotExist()
            rule.onNodeWithText("配置你的模型").assertIsDisplayed()
            runBlocking { app.modelProfiles.save(fixture().profiles.single()) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("配置你的模型").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithText("first-use-fixture").assertIsDisplayed()
            rule.activityRule.scenario.recreate()
            ready()
            rule.onNodeWithText("first-use-fixture").assertIsDisplayed()
            rule.onNodeWithText("配置你的模型").assertDoesNotExist()
            screenshot("chat-first-use-saved-recreated")
        } finally { runBlocking { app.modelProfiles.replace(original) } }
    }

    @Test fun compactLargeTextKeyboardKeepsFirstSetupReachableAndSaveReturnsToChat() {
        ready()
        val app = rule.activity.application as SolvitApplication
        val original = app.modelProfiles.state.value
        val oldScale = shell("settings get system font_scale").trim()
        require(oldScale == "null" || oldScale.toFloatOrNull() != null)
        val oldSize = shell("wm size").lineSequence().firstOrNull { it.startsWith("Override size:") }
            ?.substringAfter(':')?.trim()
        require(oldSize == null || oldSize.matches(Regex("[0-9]+x[0-9]+")))
        try {
            runBlocking { app.modelProfiles.replace(fixture()) }
            shell("settings put system font_scale 1.5")
            shell("wm size 1080x1600")
            rule.waitUntil(15_000) { rule.runOnUiThread {
                rule.activity.window.decorView.height == 1600 && rule.activity.resources.configuration.fontScale == 1.5f
            } }
            ready(); newChat()
            rule.onNodeWithTag("chat_input").performClick()
            rule.waitUntil(10_000) { rule.runOnUiThread {
                rule.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            } }
            screenshot("chat-first-use-compact-large-text-keyboard")
            val setup = rule.onNodeWithText("配置你的模型").assertIsDisplayed()
            val setupBounds = setup.fetchSemanticsNode().boundsInRoot
            val composer = rule.onNodeWithTag("chat_composer").fetchSemanticsNode().boundsInRoot
            val title = rule.onNodeWithTag("chat_role_title").fetchSemanticsNode().boundsInRoot
            assertTrue("Setup must remain above the composer", setupBounds.bottom <= composer.top)
            assertTrue("Setup must remain below the header", setupBounds.top >= title.bottom)
            setup.performClick()
            rule.onNodeWithTag("model_settings_list").assertIsDisplayed()
            rule.onNodeWithContentDescription("关闭设置").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("model_settings_list").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithTag("model_profiles_list").fetchSemanticsNodes().isEmpty() }
            assertFalse("Cancel must not save the default connection", app.modelProfiles.state.value.configured)
            rule.onNodeWithText("first-use-fixture").assertDoesNotExist()
            rule.onNodeWithText("配置你的模型").assertIsDisplayed().performClick()
            rule.onNodeWithTag("model_settings_list").assertIsDisplayed()
            rule.onNodeWithTag("save_model_profile").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("model_settings_list").fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithTag("model_profiles_list").fetchSemanticsNodes().isEmpty() }
            assertTrue(app.modelProfiles.state.value.configured)
            rule.onNodeWithText("配置你的模型").assertDoesNotExist()
            rule.onNodeWithText("first-use-fixture").assertIsDisplayed()
            screenshot("chat-first-use-compact-saved")
        } finally {
            runBlocking { app.modelProfiles.replace(original) }
            shell(if (oldSize == null) "wm size reset" else "wm size $oldSize")
            shell(if (oldScale == "null") "settings delete system font_scale" else "settings put system font_scale $oldScale")
        }
    }
}
