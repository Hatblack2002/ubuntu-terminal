package com.ubuntuterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ubuntuterm.terminal.UbuntuSession
import kotlinx.coroutines.flow.collectLatest

/**
 * SimpleTerminalOutput — muestra los bytes crudos del PTY como texto.
 *
 * v0.1.11: Reemplaza al Canvas (TerminalView) que crasheaba.
 * No parsea ANSI, no dibuja cursor — solo muestra el texto que
 * bash escribe al PTY. Suficiente para verificar que el shell funciona.
 *
 * Cuando confirmemos que bash arranca correctamente, podemos
 * restaurar el Canvas con el parser ANSI.
 */
@Composable
fun SimpleTerminalOutput(session: UbuntuSession) {
    var text by remember(session.id) { mutableStateOf("") }

    LaunchedEffect(session.id) {
        session.output.collectLatest { chunk ->
            val s = String(chunk, Charsets.UTF_8)
            // Limpiar secuencias ANSI básicas para que el texto sea legible
            val clean = s
                .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")  // CSI sequences
                .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")  // OSC sequences
                .replace(Regex("\u001B[()][0-9a-zA-Z]"), "")      // charset
                .replace("\r", "")                                  // carriage returns
            text += clean
            // Limitar a últimos 5000 chars para no agotar memoria
            if (text.length > 5000) {
                text = text.takeLast(5000)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
            .padding(8.dp)
    ) {
        Text(
            text = text.ifEmpty { "(waiting for bash output...)" },
            color = Color(0xFFB3B3B3),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        )
    }
}
