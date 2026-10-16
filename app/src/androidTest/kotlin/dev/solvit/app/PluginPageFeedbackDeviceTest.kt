package dev.solvit.app

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Host feedback and ownership tests; actual JS/HTML persistence remains in PluginWebPageUiTest. */
class PluginPageFeedbackDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }
    private fun ready() {
        val app = rule.activity.application as SolvitApplication
        runBlocking { withTimeout(30_000) { app.ready.filterNotNull().first().getOrThrow() } }
    }
    private fun snapshot(number: Int) = ExtensionPageSnapshot(1,
        """{"title":"Fixture page","nodes":[{"id":"status","kind":"text","text":"Fixture session $number"}],"footer":[{"id":"write","kind":"button","text":"Write fixture","action":"write"}]}""")

    @Test fun failedOpenCanReopenInPlaceAndCloseTheRecoveredSession() {
        ready()
        var visible by mutableStateOf(true)
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val recovered = object : ExtensionPageSession {
            override val closed = MutableStateFlow(false)
            override suspend fun open() = snapshot(2)
            override suspend fun dispatch(revision: Long, nodeId: String, value: String?, fields: Map<String, String>, confirmed: Boolean) = error("unused")
            override suspend fun close() { closes.incrementAndGet(); closed.value = true }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("Fixture page", {
                    if (opens.incrementAndGet() == 1) error("PRIVATE_PAGE_FAILURE_SENTINEL")
                    recovered
                }) { visible = false }
            } } }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_error").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("PRIVATE_PAGE_FAILURE_SENTINEL", substring = true).assertDoesNotExist()
            screenshot("plugin-page-open-error")
            rule.onNodeWithTag("plugin_page_reopen").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Fixture session 2").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, opens.get())
            rule.onNodeWithTag("plugin_page_error").assertDoesNotExist()
            screenshot("plugin-page-recovered")
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { closes.get() == 1 }
        } catch (failure: Throwable) {
            runCatching { screenshot("plugin-page-open-failure") }; throw failure
        } finally { rule.runOnUiThread { visible = false } }
    }

    @Test fun failedDispatchReopensOnlyAfterCleanupAndNeverReplaysTheWrite() {
        ready()
        var visible by mutableStateOf(true)
        val opens = AtomicInteger()
        val writes = AtomicInteger()
        val closes = AtomicInteger()
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fun session(number: Int) = object : ExtensionPageSession {
            override val closed = MutableStateFlow(false)
            override suspend fun open() = snapshot(number)
            override suspend fun dispatch(revision: Long, nodeId: String, value: String?, fields: Map<String, String>, confirmed: Boolean): ExtensionPageSnapshot {
                writes.incrementAndGet(); error("PRIVATE_DISPATCH_FAILURE_SENTINEL")
            }
            override suspend fun close() {
                if (number == 1) { closing.complete(Unit); release.await() }
                closes.incrementAndGet(); closed.value = true
            }
        }
        try {
            rule.runOnUiThread { rule.activity.setContent { MaterialTheme {
                if (visible) PluginPageScreen("Fixture page", { session(opens.incrementAndGet()) }) { visible = false }
            } } }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Write fixture").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Write fixture").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("plugin_page_error").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, writes.get())
            rule.onNodeWithText("PRIVATE_DISPATCH_FAILURE_SENTINEL", substring = true).assertDoesNotExist()
            screenshot("plugin-page-action-error")
            rule.onNodeWithTag("plugin_page_reopen").assertIsDisplayed().performClick()
            rule.waitUntil(10_000) { closing.isCompleted }
            assertEquals(1, opens.get())
            assertEquals(1, writes.get())
            rule.onNodeWithTag("plugin_page_loading").assertIsDisplayed()
            screenshot("plugin-page-reopening")
            release.complete(Unit)
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Fixture session 2").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(2, opens.get()); assertEquals(1, closes.get()); assertEquals(1, writes.get())
            screenshot("plugin-page-reopened-after-action-failure")
            rule.onNodeWithTag("plugin_page_close").performClick()
            rule.waitUntil(10_000) { closes.get() == 2 }
        } catch (failure: Throwable) {
            runCatching { screenshot("plugin-page-dispatch-failure") }; throw failure
        } finally { release.complete(Unit); rule.runOnUiThread { visible = false } }
    }
}
