package dev.solvit.app

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.solvit.core.ChatSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatHistoryDrawerTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun largeTextSearchKeepsCorrectSessionActionsAndCanRecoverFromNoResults() {
        val sessions = listOf(
            ChatSession("same-a", "Android 项目排错：检查长标题下的会话操作不会指向另一条记录", 3),
            ChatSession("same-b", "Android 项目排错：检查长标题下的会话操作不会指向另一条记录", 2),
            ChatSession("other", "旅行清单", 1),
        )
        var chosen: String? = null
        var deleted: String? = null
        var busy by mutableStateOf(false)
        rule.runOnUiThread {
            rule.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MaterialTheme {
                        Box(Modifier.width(300.dp).fillMaxHeight()) {
                            ChatHistoryDrawerContent(sessions, "same-b", true, !busy, busy,
                                {}, { chosen = it.id }, { deleted = it.id }, {})
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("history_search").performTextInput("  aNdRoId  ")
        rule.onNodeWithTag("history_search").performImeAction()
        rule.onNodeWithTag("history_session_other").assertDoesNotExist()
        rule.onNodeWithTag("history_session_same-b").assertIsSelected()
        rule.onNodeWithTag("history_session_same-a").performClick()
        assertEquals("same-a", chosen)
        rule.onNode(hasContentDescription("删除 " + sessions[1].title) and hasAnyAncestor(hasTestTag("history_session_same-b")), useUnmergedTree = true).performClick()
        assertEquals("same-b", deleted)
        rule.runOnIdle { busy = true; chosen = null; deleted = null }
        rule.onNodeWithTag("history_session_same-a").performClick()
        assertNull(chosen)
        rule.onNodeWithTag("new_chat").assertIsNotEnabled()
        rule.onNodeWithTag("history_search").performTextReplacement("absent-title")
        rule.onNodeWithTag("history_empty").assertIsDisplayed()
        rule.onNodeWithTag("history_session_same-a").assertDoesNotExist()
        rule.onNodeWithTag("history_clear_search").assertIsDisplayed().performClick()
        rule.onNodeWithTag("history_session_other").assertExists()
        rule.onNodeWithTag("history_session_same-b").assertIsSelected()
        rule.onNodeWithTag("history_clear_search").assertDoesNotExist()
        rule.onNodeWithTag("history_search").performTextInput("Android")
        rule.onNodeWithTag("history_search").performImeAction()
        rule.onNodeWithTag("history_session_same-b").assertIsDisplayed()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("mkdir -p /data/local/tmp/solvit-ui")).use { it.readBytes() }
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p /data/local/tmp/solvit-ui/history-search-large-text.png")).use { it.readBytes() }
    }
}
