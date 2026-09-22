package com.ubuntuterm.ui

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    var selectedTab by remember { mutableIntStateOf(0) }

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
                IconButton(onClick = { /* Settings */ }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        }

        // Tabs
        if (sessions.isNotEmpty()) {
            val activeIndex = sessions.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = activeIndex,
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

        // Active terminal
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 0.dp)
        ) {
            val session = sessions.getOrNull(selectedTab)
                ?: sessions.find { it.id == activeId }
            if (session != null) {
                TerminalView(session = session)
            } else {
                Text(
                    text = "No sessions. Tap + to open one.",
                    modifier = Modifier
                        .padding(16.dp)
                        .wrapContentWidth()
                )
            }
        }
    }
}
