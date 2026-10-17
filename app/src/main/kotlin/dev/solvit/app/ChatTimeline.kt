package dev.solvit.app

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.solvit.core.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Follow a tail item, including replies taller than the viewport, after layout. */
@Composable
fun ChatTimeline(rows: List<ChatRow>, sessionId: String?, modifier: Modifier = Modifier,
    onBranch: ((ChatMessage, BranchMode) -> Unit)? = null, footer: (@Composable () -> Unit)? = null,
    loadExecutionEvents: (suspend (ExecutionRun) -> List<RecordedExecutionEvent>)? = null,
    onOpenFileChanges: ((ExecutionRun) -> Unit)? = null, assistantName: String = "Solvit", onRetry: ((ChatMessage) -> Unit)? = null, retryableReplyId: String? = null, attachmentsContent: (@Composable (ChatMessage) -> Unit)? = null) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragging by list.interactionSource.collectIsDraggedAsState()
    var follow by remember(sessionId) { mutableStateOf(true) }
    LaunchedEffect(dragging) {
        if (dragging) follow = false
        else if (!list.canScrollForward) follow = true
    }
    // A fling can reach the bottom after the drag ends. Resume only once the
    // list has actually settled there, independently of content growth.
    LaunchedEffect(sessionId, list) {
        snapshotFlow { !list.isScrollInProgress && !list.canScrollForward }
            .distinctUntilChanged().collect { atBottom -> if (atBottom) follow = true }
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
    Box(modifier.fillMaxWidth()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("chat_messages"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(rows, key = { it.id }) { row -> ChatBubble(row, onBranch, loadExecutionEvents, onOpenFileChanges,assistantName,attachmentsContent,
                onRetry?.takeIf { row.messages.lastOrNull()?.id == retryableReplyId }) }
            if (footer != null) item(key = "create-workspace") { footer() }
            item(key = "stream-tail") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("chat_tail")) }
        }
        if (!follow && list.canScrollForward) {
            FilledTonalIconButton(
                onClick = { scope.launch { list.animateScrollToItem(tail); follow = true } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp)
                    .testTag("chat_return_latest")
            ) { UiIcon(Icons.Default.KeyboardArrowDown, "回到最新消息") }
        }
    }
}
