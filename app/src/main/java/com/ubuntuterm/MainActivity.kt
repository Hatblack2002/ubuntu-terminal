package com.ubuntuterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
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

                    // CRITICAL FIX (v0.1.3):
                    //   Previously `vm.openSession()` was called directly inside
                    //   the composable body (inside the `when (state)` Ready
                    //   branch). That is a side effect during composition — a
                    //   Compose anti-pattern that can fire openSession() on
                    //   every recomposition, race with session creation, and
                    //   eventually cause `fork()` from the Main thread (which
                    //   in turn can SIGABRT the process — see UbuntuSession).
                    //
                    //   We now use a LaunchedEffect keyed on `state`. The
                    //   effect fires AT MOST ONCE per `state` transition, and
                    //   only when:
                    //     - state == Ready
                    //     - no session has been opened yet (sessions.isEmpty())
                    //   This guarantees openSession() runs exactly once per
                    //   Activity lifetime (assuming the user does not close
                    //   all sessions manually).
                    LaunchedEffect(state) {
                        if (state is TerminalViewModel.BootstrapState.Ready &&
                            vm.manager.sessions.value.isEmpty()
                        ) {
                            vm.openSession()
                        }
                    }

                    when (state) {
                        TerminalViewModel.BootstrapState.Ready -> {
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

