package dev.solvit.app

import androidx.compose.ui.geometry.Offset
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
        val viewport = fetchSemanticsNode().boundsInRoot
        check(viewport.height > 0f) { "Scroll viewport must be visible" }
        val target = rule.onAllNodes(matcher and inContainer).fetchSemanticsNodes().firstOrNull()
        // LazyColumn can precompose an item without placing it. Its coordinates
        // then describe a placeholder, not a visible item (as AndroidAssertions checks).
        val coordinates = target?.layoutInfo?.takeIf { layout ->
            generateSequence(layout) { it.parentInfo }.all { it.isPlaced }
        }?.coordinates
        val top = coordinates?.localToRoot(Offset.Zero)?.y
        val bottom = if (top != null && coordinates != null) top + coordinates.size.height else null
        val visible = top != null && bottom != null &&
            ((top >= viewport.top && bottom <= viewport.bottom) ||
                (top <= viewport.top && bottom >= viewport.bottom))
        if (visible) true else {
            val distance = when {
                top == null || bottom == null -> viewport.height / 2f
                top < viewport.top -> top - viewport.top
                else -> bottom - viewport.bottom
            }
            performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, distance) }
            rule.mainClock.advanceTimeByFrame()
            false
        }
    }
    return this
}
