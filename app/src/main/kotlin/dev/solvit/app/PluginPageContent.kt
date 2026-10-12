package dev.solvit.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.solvit.plugins.ScriptPageView
import dev.solvit.plugins.ScriptPageHostAction
import dev.solvit.core.PostTurnTaskControl
import kotlinx.serialization.json.*

/** Captures the displayed view so queued clicks cannot act on a newer page. */
data class PluginPageInteraction(val view: ScriptPageView, val nodeId: String, val value: String? = null,
    val fields: Map<String, String> = emptyMap(), val confirmed: Boolean = false)

/** Generic renderer: content and business actions come from the plugin, not per-plugin screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PluginPageContent(view: ScriptPageView, busy: Boolean, modifier: Modifier = Modifier,
    onEvent: (PluginPageInteraction) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val fields = remember(view) {
        mutableStateMapOf<String, String>().apply {
            fun collect(node: JsonObject) {
                if (node["kind"] == JsonPrimitive("field")) put(node.getValue("id").jsonPrimitive.content, node.getValue("value").jsonPrimitive.content)
                node["children"]?.jsonArray?.forEach { collect(it.jsonObject) }
            }
            view.nodes.forEach(::collect)
        }
    }
    var confirmation by remember(view) { mutableStateOf<Pair<JsonObject, Map<String, String>>?>(null) }
    val dispatch: (JsonObject, String?) -> Unit = { node, value ->
        if (!busy) {
            val snapshot = fields.toMap()
            keyboardController?.hide()
            focusManager.clearFocus()
            if (node["confirm"] != null || node["hostAction"] != null) confirmation = node to snapshot
            else onEvent(PluginPageInteraction(view, node.getValue("id").jsonPrimitive.content, value, snapshot))
        }
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("plugin_page_content"), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(view.nodes, key = { it.getValue("id").jsonPrimitive.content }) { node ->
                PluginPageNode(node, busy, fields, dispatch)
            }
        }
        if (view.footer.isNotEmpty()) {
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().padding(12.dp).testTag("plugin_page_footer"),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                view.footer.forEach { node -> key(node.getValue("id").jsonPrimitive.content) { PluginPageNode(node, busy, fields, dispatch) } }
            }
        }
    }
    confirmation?.let { (node, values) ->
        AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(node.getValue("text").jsonPrimitive.content) },
            text = {
                val native = node["hostAction"]?.jsonObject?.let(ScriptPageHostAction::parse)
                if (native == null) Text(node.getValue("confirm").jsonPrimitive.content)
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UiText(when (native) {
                        is PostTurnTaskControl.Retry -> "重试将使用原模型配置，可能产生额外费用。"
                        is PostTurnTaskControl.Delete -> "删除此任务将取消后续处理和重试，已保存记忆和聊天仍然保留。"
                        is PostTurnTaskControl.Stop -> "停止此任务的当前处理，需要手动重试才能再次执行。"
                        PostTurnTaskControl.Cleanup -> "清理最多 100 个已完成或跳过的任务，保留失败、等待和运行中的任务。"
                    })
                    when (native) {
                        is PostTurnTaskControl.Retry -> Text(native.id)
                        is PostTurnTaskControl.Delete -> Text(native.id)
                        is PostTurnTaskControl.Stop -> Text(native.id)
                        PostTurnTaskControl.Cleanup -> Unit
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                onEvent(PluginPageInteraction(view, node.getValue("id").jsonPrimitive.content, fields = values, confirmed = true))
            }, enabled = !busy, modifier = Modifier.testTag("plugin_page_confirm")) { UiText("确认操作") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { UiText("取消") } })
    }
}

@Composable
private fun PluginPageNode(node: JsonObject, busy: Boolean, fields: MutableMap<String, String>,
    dispatch: (JsonObject, String?) -> Unit, baseModifier: Modifier = Modifier) {
    fun text(key: String) = node.getValue(key).jsonPrimitive.content
    val modifier = baseModifier.testTag("plugin_node_${text("id")}")
    val children = node["children"]?.jsonArray?.map { it.jsonObject }.orEmpty()
    when (text("kind")) {
        "text" -> Text(text("text"), modifier, style = when (node["tone"]?.jsonPrimitive?.content) {
            "title" -> MaterialTheme.typography.titleMedium
            "caption" -> MaterialTheme.typography.bodySmall
            else -> MaterialTheme.typography.bodyMedium
        })
        "column" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            children.forEach { child -> key(child.getValue("id").jsonPrimitive.content) { PluginPageNode(child, busy, fields, dispatch) } }
        }
        "row" -> if (children.any { it["kind"] == JsonPrimitive("field") }) {
            Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                children.forEach { child -> key(child.getValue("id").jsonPrimitive.content) {
                    PluginPageNode(child, busy, fields, dispatch,
                        if (child["kind"] == JsonPrimitive("field")) Modifier.weight(1f) else Modifier)
                } }
            }
        } else Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            children.forEach { child -> key(child.getValue("id").jsonPrimitive.content) { PluginPageNode(child, busy, fields, dispatch) } }
        }
        "card" -> Card(modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                children.forEach { child -> key(child.getValue("id").jsonPrimitive.content) { PluginPageNode(child, busy, fields, dispatch) } }
            }
        }
        "button" -> Button(onClick = { dispatch(node, null) }, enabled = !busy && node["enabled"]?.jsonPrimitive?.boolean != false,
            modifier = modifier) { Text(text("text"), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        "field" -> Column(modifier.fillMaxWidth()) {
            val id = text("id")
            val hasAction = node["action"] != null
            val multiline = node["multiline"]?.jsonPrimitive?.boolean == true
            OutlinedTextField(fields[id].orEmpty(), onValueChange = { if (it.length <= ScriptPageView.MAX_FIELD) fields[id] = it },
                label = { Text(text("label")) }, enabled = !busy, singleLine = !multiline,
                modifier = Modifier.fillMaxWidth().testTag("plugin_field_$id"), minLines = if (multiline) 3 else 1, maxLines = if (multiline) 8 else 1,
                keyboardOptions = KeyboardOptions(imeAction = if (hasAction && !multiline) ImeAction.Search else ImeAction.Default),
                keyboardActions = KeyboardActions(onSearch = { if (hasAction) dispatch(node, fields[id].orEmpty()) }))
            if (hasAction) TextButton(onClick = { dispatch(node, fields[id].orEmpty()) }, enabled = !busy) { UiText("应用输入") }
        }
        "choice" -> {
            var open by remember(node) { mutableStateOf(false) }
            val options = node.getValue("options").jsonArray.map { it.jsonObject }
            val selected = options.first { it["value"] == node["value"] }
            Box(modifier) {
                OutlinedButton(onClick = { open = true }, enabled = !busy) { Text(text("label") + ": " + selected.getValue("label").jsonPrimitive.content) }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    options.forEach { option -> DropdownMenuItem(text = { Text(option.getValue("label").jsonPrimitive.content) },
                        onClick = { open = false; dispatch(node, option.getValue("value").jsonPrimitive.content) }) }
                }
            }
        }
        "graph" -> PluginPageGraph(node, !busy && node["action"] != null, modifier) { dispatch(node, it) }
    }
}
