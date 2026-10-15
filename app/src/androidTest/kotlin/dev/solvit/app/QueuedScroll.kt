package dev.solvit.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Lazy-list scrolls must run on the queued effect dispatcher before reading their result. */
internal fun SemanticsNodeInteraction.performQueuedScrollToNode(
    rule: ComposeTestRule,
    matcher: SemanticsMatcher
): SemanticsNodeInteraction {
    val containerId = fetchSemanticsNode().id
    val inContainer = hasAnyAncestor(SemanticsMatcher("scroll container $containerId") {
        it.id == containerId
    })
    // These callers exercise vertical LazyColumns, including offscreen items.
    performScrollToIndex(0)
    rule.mainClock.advanceTimeByFrame()
    rule.waitUntil(10_000) {
        if (rule.onAllNodes(matcher and inContainer).onFirst().isDisplayed()) true else {
            val height = fetchSemanticsNode().boundsInRoot.height
            check(height > 0f) { "Scroll viewport must be visible" }
            performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, height / 2f) }
            rule.mainClock.advanceTimeByFrame()
            false
        }
    }
    return this
}
