package dev.solvit.app

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.ChatAttachment
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** The production viewer chrome; integrated picker/bytes/HTTP paths remain in attachment/PDF tests. */
class AttachmentPreviewLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun screenshot(name: String) {
        rule.waitForIdle()
        runBlocking {
            val frame = CompletableDeferred<Unit>()
            rule.runOnUiThread {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                check(root.isHardwareAccelerated)
                root.viewTreeObserver.registerFrameCommitCallback { frame.complete(Unit) }
                root.invalidate()
            }
            withTimeout(10_000) { frame.await() }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/$name.png")).use { it.readBytes() }
    }

    @Test fun longNameAndFileInformationKeepViewerNavigationUsableInBothThemes() {
        val app = rule.activity.application as SolvitApplication
        rule.waitUntil(15_000) { app.ready.value != null }
        check(app.ready.value!!.isSuccess)
        val name = "项目评审与实现说明-".repeat(12) + "final-reviewed.pdf"
        val attachment = ChatAttachment(UUID.randomUUID().toString(), name, "application/pdf", 128, "0".repeat(64))
        var visible by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var dismissals = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                        if (visible) AttachmentPreviewPage(attachment, { visible = false; dismissals++ }) { modifier ->
                            Box(modifier) { Text("预览正文保持打开", Modifier.testTag("attachment_fixture_content")) }
                        }
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            val theme = if (isDark) "dark" else "light"
            rule.runOnUiThread { visible = true; dark = isDark }
            val title = rule.onNodeWithTag("attachment_preview_title").assertIsDisplayed()
            val close = rule.onNodeWithTag("attachment_preview_close").assertIsDisplayed()
            val information = rule.onNodeWithTag("attachment_preview_info").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue("A long name must ellipsize without expanding the toolbar", layouts.single().isLineEllipsized(0))
            val toolbar = rule.onNodeWithTag("attachment_preview_toolbar").fetchSemanticsNode().boundsInRoot
            val titleBounds = title.fetchSemanticsNode().boundsInRoot
            val closeBounds = close.fetchSemanticsNode().boundsInRoot
            val informationBounds = information.fetchSemanticsNode().boundsInRoot
            assertTrue(closeBounds.left >= toolbar.left && closeBounds.right <= toolbar.right)
            assertTrue(informationBounds.left >= toolbar.left && informationBounds.right <= toolbar.right)
            assertTrue(titleBounds.left >= closeBounds.right && titleBounds.right <= informationBounds.left)
            assertTrue(rule.onNodeWithTag("attachment_preview_body").fetchSemanticsNode().boundsInRoot.top >= toolbar.bottom)
            screenshot("attachment-preview-$theme-large-text")
            information.performClick()
            val fullName = rule.onNodeWithTag("attachment_info_name").performScrollTo().assertIsDisplayed().assertTextEquals(name)
            val detailLayouts = mutableListOf<TextLayoutResult>()
            fullName.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(detailLayouts) }
            assertTrue(detailLayouts.single().lineCount > 1)
            assertFalse(detailLayouts.single().hasVisualOverflow)
            screenshot("attachment-info-$theme-large-text")
            rule.onNodeWithTag("attachment_info_type").performScrollTo().assertIsDisplayed().assertTextEquals("application/pdf")
            rule.onNodeWithTag("attachment_info_size").performScrollTo().assertIsDisplayed().assertTextContains("128", substring = true)
            rule.onNodeWithTag("attachment_info_close").assertIsDisplayed()
            // Actual system Back dismisses file information first, preserving the underlying viewer.
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
            rule.waitUntil(8_000) { rule.onAllNodesWithTag("attachment_info_name").fetchSemanticsNodes().isEmpty() }
            rule.onNodeWithTag("attachment_fixture_content").assertIsDisplayed()
            rule.onNodeWithTag("attachment_preview_close").performClick()
            rule.waitUntil(8_000) { rule.onAllNodesWithTag("attachment_preview_title").fetchSemanticsNodes().isEmpty() }
            assertEquals(if (isDark) 2 else 1, dismissals)
        }
    }
}
