package dev.solvit.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Rule
import org.junit.Test

/** Exercises real lifecycle collectors and rule teardown, including cancellation after recreation. */
@OptIn(ExperimentalTestApi::class)
class ComposeLifecycleDispatchTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>(
        effectContext = StandardTestDispatcher()
    )

    @Test fun recreatedActivityCleansLifecycleCollectorsOnMainThread() {
        repeat(4) { iteration ->
            rule.waitUntil(30_000) {
                rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
            if (iteration < 3) rule.activityRule.scenario.recreate()
        }
        // Rule teardown must also succeed. On ui-test 1.7.6 it drains lifecycle
        // cancellation on the instrumentation thread and removeObserver throws.
    }
}
