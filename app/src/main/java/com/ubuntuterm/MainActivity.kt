package com.ubuntuterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ubuntuterm.ui.BootstrapScreen
import com.ubuntuterm.ui.TerminalViewModel
import com.ubuntuterm.ui.TerminalWorkspace
import com.ubuntuterm.ui.theme.UbuntuTerminalTheme

/**
 * Entry point of the app.
 *
 * - If bootstrap has not completed: show the bootstrap screen.
 * - Otherwise: show the terminal workspace (tabbed terminals).
 */
class MainActivity : ComponentActivity() {

    private val vm: TerminalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UbuntuTerminalTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by vm.bootstrapState.collectAsState()
                    when (state) {
                        TerminalViewModel.BootstrapState.Ready -> {
                            // auto-open first session
                            if (vm.manager.sessions.value.isEmpty()) {
                                vm.openSession()
                            }
                            TerminalWorkspace(vm)
                        }
                        is TerminalViewModel.BootstrapState.Failed -> {
                            BootstrapScreen(state, onRetry = { vm.ensureBootstrap() })
                        }
                        else -> BootstrapScreen(
                            state,
                            onRetry = { vm.ensureBootstrap() }
                        )
                    }
                }
            }
        }
        // Kick off bootstrap on first launch
        vm.ensureBootstrap()
    }
}
