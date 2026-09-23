package com.ubuntuterm.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for TerminalBuffer — the in-memory terminal grid.
 */
class TerminalBufferTest {

    @Test
    fun buffer_should_start_empty_with_correct_dimensions() {
        val buf = TerminalBuffer(80, 24)
        assertEquals(80, buf.cols)
        assertEquals(24, buf.rows)
        assertEquals(0, buf.cursorCol)
        assertEquals(0, buf.cursorRow)
    }

    @Test
    fun writing_a_single_character_should_place_it_at_cursor() {
        val buf = TerminalBuffer(80, 24)
        buf.write("A")
        val cells = buf.snapshot()
        assertEquals('A', cells[0].char)
        assertEquals(1, buf.cursorCol)
    }

    @Test
    fun writing_multiple_characters_should_advance_cursor() {
        val buf = TerminalBuffer(80, 24)
        buf.write("Hello")
        assertEquals(5, buf.cursorCol)
        val cells = buf.snapshot()
        assertEquals('H', cells[0].char)
        assertEquals('e', cells[1].char)
        assertEquals('l', cells[2].char)
        assertEquals('l', cells[3].char)
        assertEquals('o', cells[4].char)
    }

    @Test
    fun newline_should_move_cursor_to_next_row_keeping_column() {
        val buf = TerminalBuffer(80, 24)
        buf.write("Hello\nWorld")
        // After "Hello\n": cursor at row=1, col=5
        // Then "World": cursor at row=1, col=10
        assertEquals(10, buf.cursorCol)
        assertEquals(1, buf.cursorRow)
        val cells = buf.snapshot()
        assertEquals('H', cells[0].char)   // row 0, col 0
        assertEquals('W', cells[85].char)   // row 1, col 5 (80 + 5)
    }

    @Test
    fun carriage_return_should_move_cursor_to_column_0() {
        val buf = TerminalBuffer(80, 24)
        buf.write("Hello\rWorld")
        assertEquals(5, buf.cursorCol)  // After "World" overwriting "Hello"
        assertEquals(0, buf.cursorRow)
        val cells = buf.snapshot()
        assertEquals('W', cells[0].char)  // "World" overwrote "Hello"
    }

    @Test
    fun tab_should_advance_to_next_tab_stop() {
        val buf = TerminalBuffer(80, 24)
        buf.write("\t")
        assertEquals(8, buf.cursorCol)
    }

    @Test
    fun backspace_should_move_cursor_left() {
        val buf = TerminalBuffer(80, 24)
        buf.write("Hello")
        buf.write(byteArrayOf(0x08))
        assertEquals(4, buf.cursorCol)
    }

    @Test
    fun resize_should_change_dimensions_and_preserve_content() {
        val buf = TerminalBuffer(80, 24)
        buf.write("Hello")
        buf.resize(100, 30)
        assertEquals(100, buf.cols)
        assertEquals(30, buf.rows)
        val cells = buf.snapshot()
        assertEquals('H', cells[0].char)
    }

    @Test
    fun ANSI_SGR_0_should_reset_attributes() {
        val buf = TerminalBuffer(80, 24)
        buf.write("\u001B[1m")
        buf.write("\u001B[0m")
        buf.write("A")
        val cells = buf.snapshot()
        assertEquals(false, cells[0].attr.bold)
    }

    @Test
    fun ANSI_SGR_1_should_set_bold() {
        val buf = TerminalBuffer(80, 24)
        buf.write("\u001B[1mA")
        val cells = buf.snapshot()
        assertTrue("Bold should be set", cells[0].attr.bold)
    }

    @Test
    fun ANSI_cursor_position_should_set_cursor() {
        val buf = TerminalBuffer(80, 24)
        buf.write("\u001B[5;10H")
        assertEquals(4, buf.cursorRow)
        assertEquals(9, buf.cursorCol)
    }

    @Test
    fun ANSI_erase_line_should_clear_current_line() {
        val buf = TerminalBuffer(80, 24)
        buf.write("Hello World")
        buf.write("\u001B[2K")
        val cells = buf.snapshot()
        for (c in 0 until 80) {
            assertEquals(' ', cells[c].char)
        }
    }

    @Test
    fun writing_past_end_of_line_should_wrap() {
        val buf = TerminalBuffer(5, 24)
        buf.write("ABCDEFGH")
        val cells = buf.snapshot()
        assertEquals('A', cells[0].char)
        assertEquals('E', cells[4].char)
        assertEquals('F', cells[5].char)
    }
}
