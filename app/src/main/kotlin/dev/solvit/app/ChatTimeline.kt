package dev.solvit.app

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import kotlinx.coroutines.flow.distinctUntilChanged

/** Follow a tail item, including replies taller than the viewport, after layout. */
@Composable
fun ChatTimeline(rows: List<ChatRow>, sessionId: String?, modifier: Modifier = Modifier,
    onBranch: ((ChatMessage, BranchMode) -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
    loadExecutionEvents: (suspend (ExecutionRun) -> List<RecordedExecutionEvent>)? = null,
    onOpenFileChanges: ((ExecutionRun) -> Unit)? = null, assistantName: String = "Solvit", onRetry: ((ChatMessage) -> Unit)? = null, retryableReplyId: String? = null, attachmentsContent: (@Composable (ChatMessage) -> Unit)? = null) {
    val list = rememberLazyListState()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(sessionId) { mutableStateOf(true) }
    LaunchedEffect(dragging) {
        if (dragging) follow = false
        else if (!list.canScrollForward) follow = true
    }
    LaunchedEffect(sessionId, rows.lastOrNull { it.user != null }?.id) { follow = true }
    val tail = rows.size + if (footer == null) 0 else 1
    LaunchedEffect(rows, sessionId, follow, tail) {
        if (follow && rows.isNotEmpty()) { withFrameNanos { }; list.scrollToItem(tail) }
    }
    val replyId = rows.lastOrNull { it.user == null }?.id
    LaunchedEffect(sessionId, follow, tail, replyId) {
        var previousViewport: Int? = null
        var previousHeight: Int? = null
        if (follow) snapshotFlow {
            val layout = list.layoutInfo
            layout.viewportEndOffset to layout.visibleItemsInfo.firstOrNull { it.key == replyId }?.size
        }.distinctUntilChanged().collect { (viewport, height) ->
            // Scrolling changes offsets/visible indexes, not reply height. Do not
            // fight a user's scroll or an accessibility scroll-to-message action.
            val changed = previousViewport != viewport || (height != null && previousHeight != height)
            previousViewport = viewport
            if (height != null) previousHeight = height
            if (changed && rows.isNotEmpty()) { withFrameNanos { }; list.scrollToItem(tail) }
        }
    }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth().testTag("chat_messages"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(rows, key = { it.id }) { row -> ChatBubble(row, onBranch, loadExecutionEvents, onOpenFileChanges,assistantName,attachmentsContent,
            onRetry?.takeIf { row.messages.lastOrNull()?.id == retryableReplyId }) }
        if (footer != null) item(key = "create-workspace") { footer() }
        item(key = "stream-tail") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("chat_tail")) }
    }
}
