package com.ubuntuterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ubuntuterm.diagnostic.DiagnosticLog
import com.ubuntuterm.diagnostic.DiagnosticReport
import kotlinx.coroutines.launch

/**
 * Diagnostics screen — accessible from the Settings (gear) button.
 *
 * Shows the full in-app diagnostic report:
 *   - App version + device info
 *   - Bootstrap state (rootfs + PRoot)
 *   - Session state
 *   - Native spawn state (handle, pid, master_fd)
 *   - Service state
 *   - Last error
 *   - Last 50 events from DiagnosticLog
 *
 * The user can:
 *   - Copy the report to the clipboard
 *   - Share it via Android's share sheet (email, message, etc.)
 *   - Refresh the report (re-snapshot)
 *   - Clear the log
 *
 * This is the "no adb logcat needed" diagnostic surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var reportText by remember { mutableStateOf<String?>(null) }
    val lastError by DiagnosticLog.lastError.collectAsState()
    val eventCount by DiagnosticLog.events.collectAsState()

    // Generate the report on first entry.
    LaunchedEffect(Unit) {
        reportText = DiagnosticReport.generate(context)
    }

    // Re-generate when events change (so the latest events show up).
    // But debounce — only regenerate when event count changes by > 1
    var lastSeenEventCount by remember { mutableStateOf(0) }
    LaunchedEffect(eventCount.size) {
        if (eventCount.size != lastSeenEventCount) {
            lastSeenEventCount = eventCount.size
            reportText = DiagnosticReport.generate(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        reportText = DiagnosticReport.generate(context)
                        scope.launch {
                            snackbarHostState.showSnackbar("Report refreshed")
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val text = reportText ?: ""
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diagnostics", text))
                        scope.launch {
                            snackbarHostState.showSnackbar("Report copied to clipboard")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Copy")
                }
                OutlinedButton(
                    onClick = {
                        val text = reportText ?: ""
                        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Ubuntu Terminal Diagnostics")
                        }
                        context.startActivity(android.content.Intent.createChooser(sendIntent, "Share diagnostics"))
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Share")
                }
            }
            OutlinedButton(
                onClick = {
                    DiagnosticLog.clear()
                    reportText = DiagnosticReport.generate(context)
                    scope.launch {
                        snackbarHostState.showSnackbar("Log cleared")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Clear log")
            }

            // The report itself
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                val txt = reportText ?: "(generating…)"
                Text(
                    text = txt,
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
