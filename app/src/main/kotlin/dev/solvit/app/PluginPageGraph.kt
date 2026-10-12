package dev.solvit.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.math.sqrt

/** Bounded graph data; positions only arrange the supplied nodes and never invent relations. */
@Composable
internal fun PluginPageGraph(node: JsonObject, enabled: Boolean, modifier: Modifier, onSelect: (String) -> Unit) {
    val nodes = node.getValue("nodes").jsonArray.map { it.jsonObject }
    val edges = node.getValue("edges").jsonArray.map { it.jsonObject }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    val cellWidth = with(density) { 170.dp.toPx() }
    val cellHeight = with(density) { 90.dp.toPx() }
    val width = with(density) { 145.dp.toPx() }
    val height = with(density) { 54.dp.toPx() }
    val columns = ceil(sqrt(nodes.size.toDouble())).toInt().coerceAtLeast(1)
    val positions = remember(nodes, cellWidth, cellHeight) { nodes.mapIndexed { i, n ->
        n.getValue("id").jsonPrimitive.content to Offset((i % columns) * cellWidth + cellWidth / 2, (i / columns) * cellHeight + cellHeight / 2)
    }.toMap() }
    var zoom by remember(node.getValue("id")) { mutableFloatStateOf(1f) }
    var pan by remember(node.getValue("id")) { mutableStateOf(Offset.Zero) }
    val colors = MaterialTheme.colorScheme
    val fill = colors.secondaryContainer
    val line = colors.outline
    val labelPaint = remember(colors.onSecondaryContainer, density) { android.text.TextPaint().apply {
        isAntiAlias = true; color = colors.onSecondaryContainer.toArgb(); textSize = with(density) { 13.sp.toPx() }
    } }
    val edgePaint = remember(colors.onSurfaceVariant, density) { android.text.TextPaint().apply {
        isAntiAlias = true; color = colors.onSurfaceVariant.toArgb(); textSize = with(density) { 11.sp.toPx() }
    } }
    Column(modifier.fillMaxWidth()) {
        UiText("关系图：拖动移动，双指缩放", style = MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(320.dp).clipToBounds().testTag("plugin_graph_canvas_${node.getValue("id").jsonPrimitive.content}")
            .pointerInput(node, enabled) { detectTransformGestures { centroid, drag, scale, _ ->
                val next = (zoom * scale).coerceIn(.4f, 3f)
                val ratio = next / zoom
                pan = centroid - (centroid - pan) * ratio + drag
                zoom = next
            } }
            .pointerInput(node, enabled) { detectTapGestures { tap ->
                if (enabled) {
                    val point = (tap - pan) / zoom
                    positions.entries.lastOrNull { (_, p) -> kotlin.math.abs(point.x - p.x) <= width / 2 && kotlin.math.abs(point.y - p.y) <= height / 2 }
                        ?.let { currentOnSelect(it.key) }
                }
            } }) {
            withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, pivot = Offset.Zero) }) {
                edges.forEach { edge ->
                    val from = positions.getValue(edge.getValue("from").jsonPrimitive.content)
                    val to = positions.getValue(edge.getValue("to").jsonPrimitive.content)
                    drawLine(line, from, to, strokeWidth = 2.dp.toPx())
                    val midpoint = (from + to) / 2f
                    val label = android.text.TextUtils.ellipsize(edge.getValue("label").jsonPrimitive.content, edgePaint, width, android.text.TextUtils.TruncateAt.END).toString()
                    drawContext.canvas.nativeCanvas.drawText(label, midpoint.x - edgePaint.measureText(label) / 2, midpoint.y - 6.dp.toPx(), edgePaint)
                }
                nodes.forEach { n ->
                    val center = positions.getValue(n.getValue("id").jsonPrimitive.content)
                    drawRoundRect(fill, center - Offset(width / 2, height / 2), Size(width, height), CornerRadius(8.dp.toPx()))
                    val label = android.text.TextUtils.ellipsize(n.getValue("label").jsonPrimitive.content, labelPaint, width - 16.dp.toPx(), android.text.TextUtils.TruncateAt.END).toString()
                    drawContext.canvas.nativeCanvas.drawText(label, center.x - labelPaint.measureText(label) / 2, center.y - (labelPaint.ascent() + labelPaint.descent()) / 2, labelPaint)
                }
            }
        }
        // Also expose every node as a native accessible control, including nodes off canvas.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            nodes.forEach { n -> TextButton(onClick = { onSelect(n.getValue("id").jsonPrimitive.content) }, enabled = enabled) {
                Text(n.getValue("label").jsonPrimitive.content, maxLines = 1)
            } }
        }
    }
}

