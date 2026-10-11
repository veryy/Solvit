package dev.solvit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.solvit.core.TerminalSession

private val ansiColors = listOf(0xff101014,0xffdf5555,0xff5fcb83,0xffd7bd63,0xff729deb,0xffc480de,0xff67cbd0,0xffdedee6,
    0xff747480,0xffff7474,0xff91edaa,0xfff4dc84,0xffa4bfff,0xffe1a1f0,0xff91eef3,0xffffffff)
private fun terminalColor(index: Int): Color = when {
    index >= 256 -> Color((0xff000000L or (index - 256).toLong()))
    index < 16 -> Color(ansiColors[index])
    index < 232 -> { val n = index - 16; fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(level(n / 36), level(n / 6 % 6), level(n % 6)) }
    else -> { val v = 8 + (index - 232) * 10; Color(v, v, v) }
}

@Composable
fun TerminalScreen(session: TerminalSession, onKey: (String) -> Unit) {
    val grid = remember(session.id, session.output) { TerminalGrid().apply { feed(session.output) } }
    val lines = grid.lines()
    val text = buildAnnotatedString {
        lines.forEachIndexed { row, cells ->
            cells.forEachIndexed { col, cell ->
                val cursor = session.active && grid.cursorVisible && row == grid.scrollback.size + grid.y && col == grid.x.coerceAtMost(79)
                withStyle(SpanStyle(color = terminalColor(if (cursor) cell.background else cell.foreground),
                    background = terminalColor(if (cursor) cell.foreground else cell.background),
                    fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal)) { append(cell.text) }
            }
            if (row < lines.lastIndex) append('\n')
        }
    }
    val vertical = rememberScrollState()
    LaunchedEffect(session.revision) { withFrameNanos { }; vertical.scrollTo(vertical.maxValue) }
    Column(Modifier.fillMaxWidth().background(Color(0xff101014)).padding(8.dp)) {
        Text("PTY · 80 × 24", color = Color(0xff9999aa), fontSize = 11.sp)
        Box(Modifier.fillMaxWidth().height(260.dp).verticalScroll(vertical).horizontalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false,
                modifier = Modifier.testTag("terminal_output"))
        }
        if (session.active) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf("Enter" to "\r", "⌫" to "\u007f", "Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Ctrl-D" to "\u0004").forEach { (label, key) ->
                    TextButton(onClick = { onKey(key) }, contentPadding = PaddingValues(3.dp), modifier = Modifier.defaultMinSize(minWidth = 28.dp)) { Text(label, color = Color.White, fontSize = 11.sp) }
                }
            }
            // This sends keys immediately, without waiting for the line composer.
            var keys by remember(session.id) { mutableStateOf("") }
            BasicTextField(keys, onValueChange = { value ->
                if (value.isNotEmpty()) onKey(value)
                keys = ""
            }, textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().heightIn(min=36.dp).testTag("terminal_keys"),
                decorationBox = { inner -> Box(Modifier.padding(6.dp)) { if (keys.isEmpty()) Text("点击直接输入按键", color = Color(0xffaaaaaa), fontSize = 12.sp); inner() } })
        }
    }
}
