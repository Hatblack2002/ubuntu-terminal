package com.ubuntuterm.ui.terminal

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharsetDecoder

/**
 * Minimal VT100 / xterm escape-sequence parser.
 *
 * Recognises:
 *   - Plain UTF-8 text → emit each char via buffer.putChar
 *   - 0x08 (BS)        → backspace (cursor left)
 *   - 0x09 (TAB)       → next tab stop (every 8 cols)
 *   - 0x0A (LF)        → cursor down + scroll if needed
 *   - 0x0B, 0x0C       → same as LF
 *   - 0x0D (CR)        → cursor to col 0
 *   - ESC (0x1B) ...   → starts a control sequence
 *     • ESC [ ... <final>   CSI (most common terminal controls)
 *     • ESC ] ... BEL       OSC (window title etc.)
 *     • ESC 7 / ESC 8       save/restore cursor
 *     • ESC M               reverse line feed
 *     • ESC D               line feed
 *     • ESC E               next line
 *     • ESC c               full reset
 *
 * Per project spec (separation principle): this is an EMULATOR of a
 * terminal — it parses byte sequences into terminal state (cursor
 * position, colors, scrollback). It does NOT interpret commands;
 * interpretation is done by /bin/bash inside Ubuntu real.
 */
class AnsiParser(private val buf: TerminalBuffer) {

    private val byteBuf = ByteArray(8192)
    private var byteBufLen = 0

    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private enum class State {
        Ground, Esc, Csi, Osc, Utf8Pending
    }

    private var state = State.Ground
    private val csiBuf = StringBuilder()
    private val oscBuf = StringBuilder()
    private var savedCursorCol = 0
    private var savedCursorRow = 0

    fun feed(data: ByteArray) {
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                State.Ground -> handleGround(b)
                State.Esc -> handleEsc(b)
                State.Csi -> handleCsi(b)
                State.Osc -> handleOsc(b)
                State.Utf8Pending -> handleGround(b)  // fall back to ground for malformed sequences
            }
        }
    }

    private fun handleGround(b: Int) {
        when {
            b == 0x1B -> state = State.Esc
            b == 0x07 -> { /* BEL — ignore */ }
            b == 0x08 -> buf.moveCursor(0, -1)
            b == 0x09 -> {
                val next = ((buf.cursorCol / 8) + 1) * 8
                buf.setCursor(buf.cursorRow, next)
            }
            b == 0x0A || b == 0x0B || b == 0x0C -> {
                // LF
                buf.setCursor(buf.cursorRow + 1, buf.cursorCol)
            }
            b == 0x0D -> buf.setCursor(buf.cursorRow, 0)
            b < 0x20 || b == 0x7F -> { /* other C0 controls — ignore */ }
            else -> {
                // UTF-8 multibyte handling: pass single byte; if leading byte
                // of multibyte sequence, accumulate. We rely on the decoder.
                val bb = b.toByte()
                byteBuf[byteBufLen++] = bb
                val expected = expectedUtf8Len(bb)
                if (byteBufLen >= expected) {
                    val bytes = byteBuf.copyOfRange(0, byteBufLen)
                    val ch = decodeChar(bytes)
                    buf.putChar(ch)
                    byteBufLen = 0
                }
            }
        }
    }

    private fun decodeChar(bytes: ByteArray): Char {
        val result = decoder.decode(ByteBuffer.wrap(bytes))
        return if (result.length > 0) result.get(0) else '?'
    }

    private fun expectedUtf8Len(first: Byte): Int {
        val b = first.toInt() and 0xFF
        return when {
            b and 0x80 == 0x00 -> 1
            b and 0xE0 == 0xC0 -> 2
            b and 0xF0 == 0xE0 -> 3
            b and 0xF8 == 0xF0 -> 4
            else -> 1
        }
    }

    private fun handleEsc(b: Int) {
        when (b.toChar()) {
            '[' -> { state = State.Csi; csiBuf.setLength(0) }
            ']' -> { state = State.Osc; oscBuf.setLength(0) }
            '7' -> { savedCursorCol = buf.cursorCol; savedCursorRow = buf.cursorRow; state = State.Ground }
            '8' -> { buf.setCursor(savedCursorRow, savedCursorCol); state = State.Ground }
            'M' -> { buf.setCursor(buf.cursorRow - 1, buf.cursorCol); state = State.Ground }
            'D' -> { buf.setCursor(buf.cursorRow + 1, buf.cursorCol); state = State.Ground }
            'E' -> { buf.setCursor(buf.cursorRow + 1, 0); state = State.Ground }
            'c' -> { buf.clear(); state = State.Ground }
            '(', ')', '*', '+' -> { /* charset designator — ignore next */ state = State.Ground }
            '=' -> { /* keypad app mode — ignore */ state = State.Ground }
            '>' -> { /* keypad numeric mode — ignore */ state = State.Ground }
            else -> { state = State.Ground }
        }
    }

    private fun handleCsi(b: Int) {
        val ch = b.toChar()
        if (ch in '0'..'9' || ch == ';' || ch == '?' || ch == '>' || ch == '=' || ch == ':') {
            csiBuf.append(ch)
            return
        }
        // Final byte
        val params = csiBuf.toString().split(';').mapNotNull { it.toIntOrNull() }
        when (ch) {
            'H', 'f' -> {
                val row = params.getOrNull(0) ?: 1
                val col = params.getOrNull(1) ?: 1
                buf.setCursor(row - 1, col - 1)
            }
            'A' -> buf.moveCursor(-(params.getOrNull(0) ?: 1), 0)
            'B' -> buf.moveCursor((params.getOrNull(0) ?: 1), 0)
            'C' -> buf.moveCursor(0, (params.getOrNull(0) ?: 1))
            'D' -> buf.moveCursor(0, -(params.getOrNull(0) ?: 1))
            'E' -> buf.setCursor(buf.cursorRow + (params.getOrNull(0) ?: 1), 0)
            'F' -> buf.setCursor(buf.cursorRow - (params.getOrNull(0) ?: 1), 0)
            'G' -> buf.setCursor(buf.cursorRow, (params.getOrNull(0) ?: 1) - 1)
            'd' -> buf.setCursor((params.getOrNull(0) ?: 1) - 1, buf.cursorCol)
            'J' -> buf.eraseDisplay(params.getOrNull(0) ?: 0)
            'K' -> buf.eraseLine(params.getOrNull(0) ?: 0)
            'L' -> buf.scrollDown(params.getOrNull(0) ?: 1)
            'M' -> buf.scrollUp(params.getOrNull(0) ?: 1)
            '@' -> { /* insert chars — not implemented */ }
            'P' -> { /* delete chars — not implemented */ }
            'm' -> applySgr(params)
            'h' -> { /* set mode — e.g. alt screen, ignored */ }
            'l' -> { /* reset mode — ignored */ }
            'r' -> { /* set scrolling region — ignored */ }
            's' -> { savedCursorCol = buf.cursorCol; savedCursorRow = buf.cursorRow }
            'u' -> { buf.setCursor(savedCursorRow, savedCursorCol) }
            '?', '>' -> { /* private modes — ignore */ }
            else -> { /* unknown — ignore */ }
        }
        state = State.Ground
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) {
            buf.resetAttr()
            return
        }
        var i = 0
        while (i < params.size) {
            val code = params[i]
            when (code) {
                0 -> buf.resetAttr()
                1 -> buf.setAttr { it.copy(bold = true) }
                3 -> buf.setAttr { it.copy(italic = true) }
                4 -> buf.setAttr { it.copy(underline = true) }
                7 -> buf.setAttr { it.copy(inverse = true) }
                9 -> buf.setAttr { it.copy(strikethrough = true) }
                22 -> buf.setAttr { it.copy(bold = false) }
                23 -> buf.setAttr { it.copy(italic = false) }
                24 -> buf.setAttr { it.copy(underline = false) }
                27 -> buf.setAttr { it.copy(inverse = false) }
                29 -> buf.setAttr { it.copy(strikethrough = false) }
                in 30..37 -> buf.setFgIndexed(code - 30)
                38 -> {
                    // Extended color: 38;5;<n> or 38;2;r;g;b
                    when (params.getOrNull(i + 1)) {
                        5 -> { buf.setFgIndexed(params.getOrNull(i + 2) ?: 0); i += 2 }
                        2 -> {
                            buf.setFgRgb(
                                params.getOrNull(i + 2) ?: 0,
                                params.getOrNull(i + 3) ?: 0,
                                params.getOrNull(i + 4) ?: 0
                            )
                            i += 4
                        }
                    }
                }
                39 -> buf.setAttr { it.copy(fg = com.ubuntuterm.ui.theme.TerminalFg) }
                in 40..47 -> buf.setBgIndexed(code - 40)
                48 -> {
                    when (params.getOrNull(i + 1)) {
                        5 -> { buf.setBgIndexed(params.getOrNull(i + 2) ?: 0); i += 2 }
                        2 -> {
                            buf.setBgRgb(
                                params.getOrNull(i + 2) ?: 0,
                                params.getOrNull(i + 3) ?: 0,
                                params.getOrNull(i + 4) ?: 0
                            )
                            i += 4
                        }
                    }
                }
                49 -> buf.setAttr { it.copy(bg = com.ubuntuterm.ui.theme.TerminalBg) }
                in 90..97 -> buf.setFgIndexed(code - 90 + 8)
                in 100..107 -> buf.setBgIndexed(code - 100 + 8)
            }
            i++
        }
    }

    private fun handleOsc(b: Int) {
        when (b) {
            0x07 -> { /* BEL — end of OSC */
                val s = oscBuf.toString()
                val semi = s.indexOf(';')
                if (semi >= 0) {
                    val code = s.substring(0, semi).toIntOrNull() ?: -1
                    val arg = s.substring(semi + 1)
                    when (code) {
                        0, 2 -> buf.setTitle(arg)
                        // 1 (icon name), 4 (palette), 8 (link), etc. — ignored
                    }
                }
                state = State.Ground
            }
            0x1B -> { /* ESC \ might follow — fall through */ }
            else -> oscBuf.appendCodePoint(b)
        }
    }
}
