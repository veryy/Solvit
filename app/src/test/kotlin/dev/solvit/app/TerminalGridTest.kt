package dev.solvit.app

import kotlin.test.*

class TerminalGridTest {
    private fun TerminalGrid.row(index: Int = 0) = visibleLines()[index].joinToString("") { it.text }.trimEnd()
    @Test fun carriageReturnAndEraseReplaceProgressInsteadOfAppendingLogs() {
        val grid = TerminalGrid(20, 4)
        grid.feed("Progress 10%\r\u001b[2KProgress 90%")
        assertEquals("Progress 90%", grid.row())
        assertEquals(12,grid.x)
    }
    @Test fun splitAnsiSequenceChangesColorAndMovesCursor() {
        val grid = TerminalGrid(20, 4)
        grid.feed("\u001b[3"); grid.feed("1mred\u001b[0m\u001b[2;4Hhere")
        assertEquals(1,grid.visibleLines()[0][0].foreground)
        assertEquals("   here",grid.row(1)); assertEquals(1,grid.y)
    }
    @Test fun alternateScreenRestoresPrimaryAndOscIsNotPrinted() {
        val grid = TerminalGrid(20, 4)
        grid.feed("shell\u001b]0;title\u0007\u001b[?1049h\u001b[Heditor\u001b[?1049l")
        assertEquals("shell",grid.row()); assertEquals(5,grid.x)
    }
    @Test fun screenScrollAndHistoryAreBoundedAndChineseUsesTwoCells() {
        val grid = TerminalGrid(8, 3)
        grid.feed("你好")
        assertEquals(4,grid.x)
        repeat(600) { grid.feed("\r\nline") }
        assertEquals(500,grid.scrollback.size); assertEquals(503,grid.lines().size)
    }
}
