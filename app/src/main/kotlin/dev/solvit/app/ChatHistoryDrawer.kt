package dev.solvit.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.solvit.core.ChatSession

/** Only filters the already loaded titles; storage ordering and session identity stay intact. */
@Composable
internal fun ChatHistoryDrawerContent(
    sessions: List<ChatSession>, selected: String?, open: Boolean,
    canCreate: Boolean, busy: Boolean,
    onNew: () -> Unit, onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit, onSettings: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun finishSearch() { keyboard?.hide(); focus.clearFocus() }
    // Reopening history starts with all conversations, including a newly created chat.
    LaunchedEffect(open) { if (!open) query = "" }
    val visible = remember(sessions, query) {
        val term = query.trim()
        if (term.isEmpty()) sessions else sessions.filter { it.title.contains(term, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        UiText("Solvit", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(24.dp))
        FilledTonalButton(onClick = { finishSearch(); onNew() }, enabled = canCreate,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("new_chat")) {
            UiIcon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); UiText("新对话")
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { UiText("按标题搜索") }, leadingIcon = { UiIcon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.testTag("history_clear_search")) {
                    UiIcon(Icons.Default.Close, "清空搜索")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { finishSearch() }),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().testTag("history_search"),
        )
        LazyColumn(Modifier.weight(1f).testTag("history_list")) {
            if (visible.isEmpty()) item {
                UiText(if (query.isBlank()) "还没有对话" else "没有匹配的对话",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag("history_empty"))
            }
            items(visible, key = { it.id }) { session ->
                NavigationDrawerItem(
                    label = { Text(session.title + if (session.originSessionId != null && !session.title.endsWith(" · 分支")) uiString(" · 分支") else "",
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = selected == session.id,
                    onClick = { if (!busy) { finishSearch(); onSelect(session) } },
                    badge = { IconButton(onClick = { finishSearch(); onDelete(session) }, enabled = !busy) {
                        UiIcon(Icons.Default.DeleteOutline, "删除 ${session.title}")
                    } },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("history_session_${session.id}"),
                )
            }
        }
        HorizontalDivider()
        TextButton(onClick = { finishSearch(); onSettings() }, modifier = Modifier.padding(12.dp).fillMaxWidth().testTag("history_settings")) {
            UiIcon(Icons.Default.Settings, null); Spacer(Modifier.width(8.dp)); UiText("设置")
        }
    }
}
