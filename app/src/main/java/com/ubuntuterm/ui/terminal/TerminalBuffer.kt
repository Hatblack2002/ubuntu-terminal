package com.ubuntuterm.ui.terminal

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.ubuntuterm.ui.theme.ANSI_COLORS
import com.ubuntuterm.ui.theme.TerminalBg
import com.ubuntuterm.ui.theme.TerminalFg

/**
 * A minimal in-memory terminal model.
 *
 * Holds:
 *   - a 2D grid of cells (cols × rows)
 *   - cursor position
 *   - current SGR attributes (fg, bg, bold, italic, etc.)
 *   - scrollback history (rows above the visible area)
 *
 * Consumes ANSI/VT100 escape sequences produced by the PTY and
 * updates the grid. The Compose Canvas reads the grid and renders it.
 *
 * Per project spec (section 10):
 *   The terminal MUST support stdin/stdout/stderr, signals, resize,
 *   interactive processes, Ctrl+C/D/Z, Tab, Esc, arrows, history,
 *   ANSI colors, and interactive terminal applications.
 *
 * Implementation notes:
 *   - We deliberately handle only the most common control sequences:
 *       ESC [ ... H   — cursor position
 *       ESC [ ... A/B/C/D — cursor movement
 *       ESC [ ... J   — erase display
 *       ESC [ ... K   — erase line
 *       ESC [ ... m   — SGR (colors/bold/etc.)
 *       ESC [ ... L/M — insert/delete lines
 *       ESC [ ... P/@ — insert/delete chars
 *       ESC [ ... h/l — mode set/reset (notably alt screen)
 *       ESC ] ... BEL — OSC (window title etc. — ignored)
 *       ESC 7 / ESC 8 — save/restore cursor
 *   - Less common sequences are silently dropped; this is enough for
 *     bash, vim, top, htop, apt, python REPL, etc.
 *
 * Per project spec (separation principle): this is an EMULATOR of a
 * terminal (the same role that gnome-terminal, xterm, alacritty, kitty
 * play on desktop Linux). It is NOT a shell, NOT a command interpreter,
 * NOT a reimplementation of any Ubuntu component. It only renders the
 * bytes that the real /bin/bash inside Ubuntu writes to the PTY.
 */
class TerminalBuffer(
    initialCols: Int = 80,
    initialRows: Int = 24,
    private val scrollbackLimit: Int = 5000
) {
    var cols: Int = initialCols
        private set
    var rows: Int = initialRows
        private set

    private var cells: Array<Cell> = Array(cols * rows) { Cell() }

    /** Scrollback (rows above visible area), newest at the end. */
    private val scrollback = ArrayDeque<CellRow>()
    private var scrollbackSize = 0

    var cursorCol = 0
        private set
    var cursorRow = 0
        private set
    var cursorVisible = true
        private set

    private var currentAttr = Attr()

    /** Alternate screen buffer (used by vim, top, less). */
    private var altBuffer: Array<Cell>? = null
    private var altCols = 0
    private var altRows = 0
    private var altCursorCol = 0
    private var altCursorRow = 0

    /** The last window title set via OSC. */
    var title: String = "ubuntu@ubuntuterm"
        private set

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        if (newCols == cols && newRows == rows) return

        // Preserve existing content by reflowing row-by-row.
        val newCells = Array(newCols * newRows) { Cell() }
        for (r in 0 until minOf(rows, newRows)) {
            for (c in 0 until minOf(cols, newCols)) {
                newCells[r * newCols + c] = cells[r * cols + c]
            }
        }
        cells = newCells
        cols = newCols
        rows = newRows
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        cursorRow = cursorRow.coerceIn(0, rows - 1)
    }

    /** Clears the entire visible grid. */
    fun clear() {
        for (i in cells.indices) cells[i] = Cell()
        cursorCol = 0
        cursorRow = 0
    }

    /** Writes a chunk of bytes (UTF-8 + ANSI) to the buffer. */
    fun write(data: ByteArray) {
        val parser = AnsiParser(this)
        parser.feed(data)
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    // ---- Internals used by AnsiParser ----

    internal fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            cursorRow++
            if (cursorRow >= rows) scrollUp(1)
        }
        if (cursorRow >= rows) {
            scrollUp(cursorRow - rows + 1)
            cursorRow = rows - 1
        }
        cells[cursorRow * cols + cursorCol] = Cell(ch, currentAttr)
        cursorCol++
    }

    internal fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    internal fun moveCursor(deltaRow: Int, deltaCol: Int) {
        setCursor(cursorRow + deltaRow, cursorCol + deltaCol)
    }

    internal fun scrollUp(n: Int) {
        val n = n.coerceAtLeast(1)
        // Move existing rows to scrollback
        repeat(n) {
            val row = (0 until cols).map { c -> cells[c] }
            scrollback.addLast(CellRow(row.toTypedArray()))
            scrollbackSize++
            if (scrollbackSize > scrollbackLimit) {
                scrollback.removeFirst()
                scrollbackSize--
            }
        }
        // Shift up
        for (r in 0 until (rows - n)) {
            for (c in 0 until cols) {
                cells[r * cols + c] = cells[(r + n) * cols + c]
            }
        }
        // Clear bottom n rows
        for (r in (rows - n) until rows) {
            for (c in 0 until cols) cells[r * cols + c] = Cell()
        }
    }

    internal fun scrollDown(n: Int) {
        val n = n.coerceAtLeast(1)
        for (r in (rows - 1) downTo n) {
            for (c in 0 until cols) {
                cells[r * cols + c] = cells[(r - n) * cols + c]
            }
        }
        for (r in 0 until n) {
            for (c in 0 until cols) cells[r * cols + c] = Cell()
        }
    }

    internal fun eraseLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until cols) cells[cursorRow * cols + c] = Cell()
            1 -> for (c in 0..cursorCol)        cells[cursorRow * cols + c] = Cell()
            2 -> for (c in 0 until cols)        cells[cursorRow * cols + c] = Cell()
        }
    }

    internal fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseLine(0)
                for (r in (cursorRow + 1) until rows) {
                    for (c in 0 until cols) cells[r * cols + c] = Cell()
                }
            }
            1 -> {
                for (c in 0..cursorCol) cells[cursorRow * cols + c] = Cell()
                for (r in 0 until cursorRow) {
                    for (c in 0 until cols) cells[r * cols + c] = Cell()
                }
            }
            2, 3 -> clear()
        }
    }

    internal fun setAttr(block: (Attr) -> Attr) {
        currentAttr = block(currentAttr)
    }

    internal fun resetAttr() {
        currentAttr = Attr()
    }

    internal fun setFgIndexed(idx: Int) {
        currentAttr = currentAttr.copy(fg = colorFromIndex(idx))
    }

    internal fun setBgIndexed(idx: Int) {
        currentAttr = currentAttr.copy(bg = colorFromIndex(idx))
    }

    internal fun setFgRgb(r: Int, g: Int, b: Int) {
        currentAttr = currentAttr.copy(fg = Color(r, g, b))
    }

    internal fun setBgRgb(r: Int, g: Int, b: Int) {
        currentAttr = currentAttr.copy(bg = Color(r, g, b))
    }

    internal fun setCursorVisible(v: Boolean) { cursorVisible = v }
    internal fun setTitle(t: String) { title = t }

    private fun colorFromIndex(idx: Int): Color {
        return if (idx in ANSI_COLORS.indices) ANSI_COLORS[idx]
        else TerminalFg
    }

    /** Snapshots the visible region for rendering. Returns a copy. */
    fun snapshot(): Array<Cell> = cells.copyOf()

    /** Number of scrollback rows currently retained. */
    fun scrollbackRowCount(): Int = scrollbackSize

    /** Returns a copy of the scrollback rows (oldest first). */
    fun scrollbackRows(): List<Array<Cell>> =
        scrollback.map { it.cells.copyOf() }

    // ---- Cell data ----

    @Immutable
    data class Cell(
        val char: Char = ' ',
        val attr: Attr = Attr()
    )

    @Immutable
    data class Attr(
        val fg: Color = TerminalFg,
        val bg: Color = TerminalBg,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
        val strikethrough: Boolean = false
    )

    private data class CellRow(val cells: Array<Cell>) {
        override fun equals(other: Any?): Boolean =
            other is CellRow && cells.contentEquals(other.cells)
        override fun hashCode(): Int = cells.contentHashCode()
    }
}
