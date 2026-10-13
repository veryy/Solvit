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
    loadExecutionEvents: (suspend (ExecutionRun) -> List<RecordedExecutionEvent>)? = null) {
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
    LaunchedEffect(sessionId, follow, tail) {
        if (follow) snapshotFlow {
            val layout = list.layoutInfo
            // Rich text can grow after its input row was already observed. Follow
            // measured content changes as well as viewport/keyboard changes.
            layout.viewportEndOffset to layout.visibleItemsInfo.lastOrNull()?.let {
                Triple(it.index, it.offset, it.size)
            }
        }.distinctUntilChanged().collect {
            if (rows.isNotEmpty()) { withFrameNanos { }; list.scrollToItem(tail) }
        }
    }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth().testTag("chat_messages"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(rows, key = { it.id }) { ChatBubble(it, onBranch, loadExecutionEvents) }
        if (footer != null) item(key = "create-workspace") { footer() }
        item(key = "stream-tail") { Spacer(Modifier.fillMaxWidth().height(1.dp).testTag("chat_tail")) }
    }
}
