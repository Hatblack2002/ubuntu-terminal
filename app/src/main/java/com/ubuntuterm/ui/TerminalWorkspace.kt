package com.ubuntuterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ubuntuterm.terminal.UbuntuSession
import com.ubuntuterm.ui.terminal.TerminalView

/**
 * The main workspace: tabbed terminals.
 *
 * Each tab corresponds to one UbuntuSession, which spawns a real
 * PRoot+Ubuntu child process attached to a PTY.
 *
 * Per project spec (section 9): the visual design is fully custom —
 * the user sees our own UI, but every command runs against real
 * Ubuntu underneath.
 */
@Composable
fun TerminalWorkspace(vm: TerminalViewModel) {
    val sessions by vm.manager.sessions.collectAsState()
    val activeId by vm.manager.activeSessionId.collectAsState()
    val sessionError by vm.lastSessionError.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showDiagnostics by remember { mutableStateOf(false) }

    // Track session state changes so we can render Starting/Failed/Closed states.
    val activeSessionState = remember(sessions, activeId) {
        sessions.find { it.id == activeId }?.state?.value
    }

    if (showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false })
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Ubuntu Terminal",
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.openSession() }) {
                    Icon(Icons.Default.Add, contentDescription = "New tab")
                }
                IconButton(onClick = { showDiagnostics = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings & Diagnostics")
                }
            }
        }

        // Session error banner (from VM)
        if (sessionError != null) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = sessionError ?: "",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    IconButton(
                        onClick = { vm.clearSessionError() },
                        modifier = Modifier
                            .width(24.dp)
                            .height(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Tabs
        if (sessions.isNotEmpty()) {
            val activeIndex = sessions.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            // Keep selectedTab in sync when sessions change.
            if (selectedTab >= sessions.size) selectedTab = sessions.lastIndex
            ScrollableTabRow(
                selectedTabIndex = activeIndex.coerceAtMost(sessions.lastIndex),
                edgePadding = 0.dp
            ) {
                sessions.forEachIndexed { index, session ->
                    Tab(
                        selected = activeId == session.id,
                        onClick = {
                            vm.manager.setActive(session.id)
                            selectedTab = index
                        },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = session.title,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                IconButton(
                                    onClick = { vm.closeSession(session.id) },
                                    modifier = Modifier
                                        .width(24.dp)
                                        .height(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Close tab",
                                        modifier = Modifier.padding(0.dp)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }

        // Active terminal — show state-aware UI
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 0.dp),
            contentAlignment = Alignment.Center
        ) {
            val session = sessions.getOrNull(selectedTab)
                ?: sessions.find { it.id == activeId }
            if (session != null) {
                // IMPORTANT: subscribe to the session's state so we recompose
                // when it transitions Starting → Running → Exited/Failed.
                val state by session.state.collectAsState()
                when (state) {
                    is UbuntuSession.SessionState.Starting -> {
                        Text(
                            text = "Starting session…\n" +
                                "(spawn PRoot + Ubuntu /bin/bash via PTY)",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UbuntuSession.SessionState.Running -> {
                        TerminalView(session = session)
                    }
                    is UbuntuSession.SessionState.Failed -> {
                        val reason = (state as UbuntuSession.SessionState.Failed).reason
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Session failed",
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = reason,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "(if this is 'PRoot binary not ready' or " +
                                    "'nativeSpawn returned 0', check logcat for " +
                                    "terminal_jni/process_utils)",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is UbuntuSession.SessionState.Exited -> {
                        val code = (state as UbuntuSession.SessionState.Exited).code
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Session exited with code $code",
                                color = if (code == 0)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "(non-zero usually means /bin/bash or PRoot " +
                                    "could not start — see logcat for tag " +
                                    "'terminal_jni' / 'PTY_READER' / 'PTY_REAPER')",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    UbuntuSession.SessionState.Closed -> {
                        Text(
                            text = "Session closed.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    UbuntuSession.SessionState.Idle -> {
                        Text(
                            text = "Session idle.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "No sessions. Tap + to open one.",
                    modifier = Modifier
                        .padding(16.dp)
                        .wrapContentWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

