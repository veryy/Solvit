package dev.solvit.app

/** Bounded ANSI screen model for the native PTY view (not an HTML transcript). */
class TerminalGrid(val columns: Int = 80, val rows: Int = 24) {
    data class Cell(val text: String = " ", val foreground: Int = 7, val background: Int = 0, val bold: Boolean = false)
    private fun blank() = MutableList(columns) { Cell() }
    private var screen = MutableList(rows) { blank() }
    val scrollback = ArrayDeque<List<Cell>>()
    var x = 0; private set
    var y = 0; private set
    var cursorVisible = true; private set
    private var foreground = 7; private var background = 0; private var bold = false
    private var reverse = false; private var state = 0; private var sequence = ""
    private var top = 0; private var bottom = rows - 1
    private var savedX = 0; private var savedY = 0
    private var primary: MutableList<MutableList<Cell>>? = null
    fun lines(): List<List<Cell>> = scrollback.toList() + screen.map { it.toList() }
    fun visibleLines() = screen.map { it.toList() }
    fun feed(text: String) {
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset); offset += Character.charCount(cp)
            when (state) {
                0 -> when (cp) {
                    27 -> state = 1
                    13 -> x = 0
                    10, 11, 12 -> newline()
                    8 -> x = (x - 1).coerceAtLeast(0)
                    9 -> x = ((x / 8 + 1) * 8).coerceAtMost(columns - 1)
                    else -> if (cp >= 32 && cp != 127) put(cp)
                }
                1 -> { state = 0; when (cp) {
                    91 -> { state = 2; sequence = "" }
                    93 -> state = 3
                    55 -> { savedX = x; savedY = y }
                    56 -> { x = savedX; y = savedY }
                    68 -> newline()
                    77 -> if (y > top) y-- else { screen.add(top, blank()); screen.removeAt(bottom + 1) }
                    99 -> { screen = MutableList(rows) { blank() }; x = 0; y = 0; foreground = 7; background = 0; bold = false; reverse = false }
                } }
                2 -> if (cp in 64..126) { csi(cp.toChar()); state = 0 } else if (sequence.length < 128) sequence += cp.toChar() else state = 0
                3 -> if (cp == 7) state = 0 else if (cp == 27) state = 4
                4 -> { state = if (cp == 92) 0 else 3 }
            }
        }
    }
    private fun newline() {
        if (y == bottom) {
            val removed = screen.removeAt(top)
            if (top == 0 && primary == null) { scrollback.addLast(removed.toList()); if (scrollback.size > 500) scrollback.removeFirst() }
            screen.add(bottom, blank())
        } else y = (y + 1).coerceAtMost(rows - 1)
    }
    private fun put(cp: Int) {
        if (Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt())) {
            val col = (x - 1).coerceIn(0, columns - 1); screen[y][col] = screen[y][col].copy(text = screen[y][col].text + String(Character.toChars(cp))); return
        }
        val wide = cp in 0x1100..0x115f || cp in 0x2e80..0xa4cf || cp in 0xac00..0xd7a3 || cp in 0xff01..0xff60 || cp in 0x1f300..0x1faff
        if (x >= columns || wide && x == columns - 1) { x = 0; newline() }
        screen[y][x] = Cell(String(Character.toChars(cp)), if (reverse) background else foreground, if (reverse) foreground else background, bold)
        if (wide) { screen[y][x + 1] = Cell("", foreground, background, bold); x++ }
        x++
    }
    private fun csi(command: Char) {
        val private = sequence.startsWith("?")
        val args = sequence.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(i: Int = 0, default: Int = 1) = args.getOrElse(i) { 0 }.let { if (it == 0) default else it }
        fun erase(row: Int, from: Int, to: Int) { for (col in from.coerceAtLeast(0)..to.coerceAtMost(columns - 1)) screen[row][col] = Cell(background = background) }
        when (command) {
            'A' -> y = (y - arg()).coerceAtLeast(top)
            'B' -> y = (y + arg()).coerceAtMost(bottom)
            'C' -> x = (x + arg()).coerceAtMost(columns - 1)
            'D' -> x = (x - arg()).coerceAtLeast(0)
            'G' -> x = (arg() - 1).coerceIn(0, columns - 1)
            'd' -> y = (arg() - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { y = (arg() - 1).coerceIn(0, rows - 1); x = (arg(1) - 1).coerceIn(0, columns - 1) }
            'J' -> when (args[0]) {
                0 -> { erase(y, x, columns - 1); for (r in y + 1 until rows) erase(r, 0, columns - 1) }
                1 -> { for (r in 0 until y) erase(r, 0, columns - 1); erase(y, 0, x) }
                2, 3 -> { screen = MutableList(rows) { blank() }; if (args[0] == 3) scrollback.clear() }
            }
            'K' -> when (args[0]) { 0 -> erase(y, x, columns - 1); 1 -> erase(y, 0, x); 2 -> erase(y, 0, columns - 1) }
            's' -> { savedX = x; savedY = y }
            'u' -> { x = savedX; y = savedY }
            'r' -> { top = (arg() - 1).coerceIn(0, rows - 2); bottom = (arg(1, rows) - 1).coerceIn(top + 1, rows - 1); x = 0; y = top }
            'P' -> repeat(arg().coerceAtMost(columns)) { screen[y].removeAt(x.coerceAtMost(columns - 1)); screen[y].add(Cell()) }
            '@' -> repeat(arg().coerceAtMost(columns)) { screen[y].add(x.coerceAtMost(columns - 1), Cell()); screen[y].removeLast() }
            'L' -> repeat(arg().coerceAtMost(rows)) { if (y in top..bottom) { screen.add(y, blank()); screen.removeAt(bottom + 1) } }
            'M' -> repeat(arg().coerceAtMost(rows)) { if (y in top..bottom) { screen.removeAt(y); screen.add(bottom, blank()) } }
            'h', 'l' -> if (private) args.forEach { when (it) {
                25 -> cursorVisible = command == 'h'
                1049 -> if (command == 'h' && primary == null) { primary = screen; savedX = x; savedY = y; screen = MutableList(rows) { blank() }; x = 0; y = 0 }
                    else if (command == 'l' && primary != null) { screen = primary!!; primary = null; x = savedX; y = savedY; top = 0; bottom = rows - 1 }
            } }
            'm' -> {
                var i = 0
                while (i < args.size) {
                    when (val value = args[i]) {
                        0 -> { foreground = 7; background = 0; bold = false; reverse = false }
                        1 -> bold = true; 22 -> bold = false; 7 -> reverse = true; 27 -> reverse = false
                        39 -> foreground = 7; 49 -> background = 0
                        in 30..37 -> foreground = value - 30; in 40..47 -> background = value - 40
                        in 90..97 -> foreground = value - 90 + 8; in 100..107 -> background = value - 100 + 8
                        38, 48 -> if (args.getOrNull(i + 1) == 5 && args.getOrNull(i + 2) != null) {
                            val color = args[i + 2].coerceIn(0, 255); if (value == 38) foreground = color else background = color; i += 2
                        } else if (args.getOrNull(i + 1) == 2 && args.getOrNull(i + 4) != null) {
                            val color = 256 + (args[i + 2].coerceIn(0,255) shl 16) + (args[i + 3].coerceIn(0,255) shl 8) + args[i + 4].coerceIn(0,255)
                            if (value == 38) foreground = color else background = color; i += 4
                        }
                    }; i++
                }
            }
        }
    }
}
