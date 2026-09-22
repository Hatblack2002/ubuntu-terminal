package com.ubuntuterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ubuntuterm.ui.theme.UbuntuOrange

@Composable
fun BootstrapScreen(
    state: TerminalViewModel.BootstrapState,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (state) {
                TerminalViewModel.BootstrapState.Idle -> {
                    Text("Welcome", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Tap to install Ubuntu 24.04 on this device.",
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = onRetry) { Text("Install Ubuntu") }
                }
                TerminalViewModel.BootstrapState.Checking -> {
                    CircularProgressIndicator(color = UbuntuOrange)
                    Text("Verifying Ubuntu installation…")
                }
                is TerminalViewModel.BootstrapState.Downloading -> {
                    val pct = if (state.total > 0) {
                        (state.downloaded.toDouble() / state.total * 100).toInt()
                    } else 0
                    CircularProgressIndicator(
                        progress = { pct / 100f },
                        color = UbuntuOrange
                    )
                    Text("Downloading Ubuntu rootfs… $pct%")
                    Text(
                        "${state.downloaded / 1_000_000} MB / " +
                            "${state.total / 1_000_000} MB",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TerminalViewModel.BootstrapState.Extracting -> {
                    CircularProgressIndicator(color = UbuntuOrange)
                    Text("Extracting rootfs…")
                }
                TerminalViewModel.BootstrapState.Configuring -> {
                    CircularProgressIndicator(color = UbuntuOrange)
                    Text("Configuring Ubuntu environment…")
                }
                TerminalViewModel.BootstrapState.Ready -> {
                    Text("Ubuntu is ready")
                }
                is TerminalViewModel.BootstrapState.Failed -> {
                    Text(
                        "Bootstrap failed",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        state.message,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
