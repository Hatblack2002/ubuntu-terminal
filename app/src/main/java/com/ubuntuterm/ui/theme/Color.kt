package com.ubuntuterm.ui.theme

import androidx.compose.ui.graphics.Color

// Ubuntu-derived palette (Aubergine + Orange) but with our own spin.
val Aubergine = Color(0xFF77216F)
val AubergineDeep = Color(0xFF2C001E)
val UbuntuOrange = Color(0xFFE95420)
val UbuntuOrangeDark = Color(0xFFC7401A)

val TerminalBg = Color(0xFF0A0E14)
val TerminalFg = Color(0xFFB3B3B3)
val TerminalCursor = Color(0xFFE95420)
val TerminalSelection = Color(0x66E95420)

// Standard ANSI 16 colors (used as fallback for non-indexed SGR codes).
val AnsiBlack   = Color(0xFF1F1F1F)
val AnsiRed     = Color(0xFFE95420)
val AnsiGreen   = Color(0xFF4E9A06)
val AnsiYellow  = Color(0xFFC4A000)
val AnsiBlue    = Color(0xFF3465A4)
val AnsiMagenta = Color(0xFF75507B)
val AnsiCyan    = Color(0xFF06989A)
val AnsiWhite   = Color(0xFFD3D7CF)
val AnsiBrightBlack   = Color(0xFF555753)
val AnsiBrightRed     = Color(0xFFFF6E3A)
val AnsiBrightGreen   = Color(0xFF8AE234)
val AnsiBrightYellow  = Color(0xFFFFE13A)
val AnsiBrightBlue    = Color(0xFF729FCF)
val AnsiBrightMagenta = Color(0xFFAD7FA8)
val AnsiBrightCyan    = Color(0xFF34E2E2)
val AnsiBrightWhite   = Color(0xFFFFFFFF)

val ANSI_COLORS = listOf(
    AnsiBlack, AnsiRed, AnsiGreen, AnsiYellow,
    AnsiBlue, AnsiMagenta, AnsiCyan, AnsiWhite,
    AnsiBrightBlack, AnsiBrightRed, AnsiBrightGreen, AnsiBrightYellow,
    AnsiBrightBlue, AnsiBrightMagenta, AnsiBrightCyan, AnsiBrightWhite
)
