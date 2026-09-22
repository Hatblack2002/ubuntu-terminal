package com.ubuntuterm.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntuterm.terminal.UbuntuSession
import com.ubuntuterm.ui.theme.TerminalBg
import com.ubuntuterm.ui.theme.TerminalCursor
import kotlinx.coroutines.flow.collectLatest

/**
 * The terminal view.
 *
 * Renders the contents of a TerminalBuffer to a Canvas, and forwards
 * key/touch input to the underlying UbuntuSession (which writes it
 * to the PTY master fd).
 *
 * Per project spec (section 9): the visual design is fully custom
 * — but the underlying behaviour is the real Linux/Ubuntu shell.
 */
@Composable
fun TerminalView(
    session: UbuntuSession,
    modifier: Modifier = Modifier
) {
    val buffer = remember(session.id) { TerminalBuffer() }

    // Tick: increments on every chunk of output, to trigger recomposition.
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(session.id) {
        session.output.collectLatest { chunk ->
            buffer.write(chunk)
            tick++
        }
    }

    // Resize the buffer when the canvas size changes.
    var lastCols by remember { mutableStateOf(80) }
    var lastRows by remember { mutableStateOf(24) }

    val density = LocalDensity.current
    val charWidthSp = 9.sp
    val charHeightSp = 16.sp

    Box(
        modifier = modifier
            .background(TerminalBg)
            .onKeyEvent { event ->
                handleKey(event, session)
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            // Recompute cols/rows from current canvas size
            val charWidth = with(density) { charWidthSp.toPx() }
            val charHeight = with(density) { charHeightSp.toPx() }
            val newCols = (size.width / charWidth).toInt().coerceAtLeast(8)
            val newRows = (size.height / charHeight).toInt().coerceAtLeast(4)
            if (newCols != lastCols || newRows != lastRows) {
                buffer.resize(newCols, newRows)
                session.resize(newCols, newRows)
                lastCols = newCols
                lastRows = newRows
            }

            drawTerminal(
                buffer = buffer,
                charWidth = charWidth,
                charHeight = charHeight,
                tick = tick
            )
        }
    }
}

private fun DrawScope.drawTerminal(
    buffer: TerminalBuffer,
    charWidth: Float,
    charHeight: Float,
    tick: Int  // forces recomposition
) {
    // Background
    drawRect(color = TerminalBg, size = size)

    val cells = buffer.snapshot()
    val cols = buffer.cols
    val rows = buffer.rows

    // Group runs of identical attributes for efficiency.
    var r = 0
    while (r < rows) {
        var c = 0
        while (c < cols) {
            val cell = cells[r * cols + c]
            if (cell.char == ' ' && cell.attr.bg == TerminalBg) {
                c++
                continue
            }
            // Find run length with same attribute
            var runEnd = c + 1
            while (runEnd < cols &&
                cells[r * cols + runEnd].attr == cell.attr &&
                cells[r * cols + runEnd].char != ' ') {
                runEnd++
            }

            val x = c * charWidth
            val y = r * charHeight
            val w = (runEnd - c) * charWidth

            // Background fill
            if (cell.attr.bg != TerminalBg) {
                drawRect(
                    color = cell.attr.bg,
                    topLeft = Offset(x, y),
                    size = Size(w, charHeight)
                )
            }

            // Text
            val text = buildString {
                for (i in c until runEnd) {
                    append(cells[r * cols + i].char)
                }
            }
            val attr = cell.attr
            val fg = if (attr.inverse) attr.bg else attr.fg
            val bg = if (attr.inverse) attr.fg else attr.bg

            drawTextRun(text, x, y, charWidth, charHeight, fg, attr)

            c = runEnd
        }
        r++
    }

    // Cursor
    if (buffer.cursorVisible) {
        val cx = buffer.cursorCol * charWidth
        val cy = buffer.cursorRow * charHeight
        drawRect(
            color = TerminalCursor.copy(alpha = 0.8f),
            topLeft = Offset(cx, cy),
            size = Size(charWidth, charHeight)
        )
    }
}

private fun DrawScope.drawTextRun(
    text: String,
    x: Float,
    y: Float,
    charWidth: Float,
    charHeight: Float,
    fgColor: Color,
    attr: TerminalBuffer.Attr
) {
    // Compose DrawScope lacks a direct drawText primitive that handles
    // monospace grid layout nicely. We use nativeCanvas + android.graphics.Paint
    // via a simple per-glyph approach.
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = charHeight * 0.85f
        color = android.graphics.Color.argb(
            (fgColor.alpha * 255).toInt(),
            (fgColor.red * 255).toInt(),
            (fgColor.green * 255).toInt(),
            (fgColor.blue * 255).toInt()
        )
        typeface = when {
            attr.bold -> android.graphics.Typeface.DEFAULT_BOLD
            attr.italic -> android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.ITALIC
            )
            else -> android.graphics.Typeface.DEFAULT
        }
        if (attr.underline) setUnderlineText(true)
        if (attr.strikethrough) setStrikeThruText(true)
    }

    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(
            text,
            x,
            y + charHeight * 0.75f,
            paint
        )
    }
}

private fun handleKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    session: UbuntuSession
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val ch = event.key
    val sb = StringBuilder()

    when {
        ch == Key.Enter || ch.nativeKeyCode == 66 -> sb.append(0x0D)
        ch == Key.Backspace || ch.nativeKeyCode == 67 -> sb.append(0x7F)  // DEL
        ch == Key.Tab -> sb.append('\t')
        ch == Key.Escape -> sb.append(0x1B)
        ch == Key.DirectionUp -> sb.append("\u001B[A")
        ch == Key.DirectionDown -> sb.append("\u001B[B")
        ch == Key.DirectionRight -> sb.append("\u001B[C")
        ch == Key.DirectionLeft -> sb.append("\u001B[D")
        // Home (122) and End (123) — Android keycodes, not exposed as Key.Home/Key.End
        ch.nativeKeyCode == 122 -> sb.append("\u001B[H")
        ch.nativeKeyCode == 123 -> sb.append("\u001B[F")
        ch == Key.PageUp -> sb.append("\u001B[5~")
        ch == Key.PageDown -> sb.append("\u001B[6~")
        ch == Key.Delete -> sb.append("\u001B[3~")
        else -> return false
    }

    session.write(sb.toString().toByteArray(Charsets.UTF_8))
    return true
}
