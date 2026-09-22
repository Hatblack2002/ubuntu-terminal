package com.ubuntuterm.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ubuntuterm.terminal.TerminalService
import com.ubuntuterm.terminal.TerminalManager
import com.ubuntuterm.terminal.UbuntuSession
import com.ubuntuterm.bootstrap.BootstrapManager
import com.ubuntuterm.bootstrap.PRootManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Top-level view model for the app.
 *
 * Drives:
 *   - Bootstrap (download Ubuntu rootfs + PRoot binary)
 *   - Session lifecycle
 *   - Foreground service start/stop
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {

    private val _manager = TerminalManager()
    val manager get() = _manager

    private val bootstrap = BootstrapManager(app)
    private val prootManager = PRootManager(app)

    sealed class BootstrapState {
        object Idle : BootstrapState()
        object Checking : BootstrapState()
        data class Downloading(val downloaded: Long, val total: Long) : BootstrapState()
        object Extracting : BootstrapState()
        object Configuring : BootstrapState()
        object Ready : BootstrapState()
        data class Failed(val message: String) : BootstrapState()
    }

    private val _bootstrapState = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val bootstrapState: StateFlow<BootstrapState> = _bootstrapState.asStateFlow()

    private val _prootState = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val prootState: StateFlow<BootstrapState> = _prootState.asStateFlow()

    fun ensureBootstrap() {
        if (_bootstrapState.value is BootstrapState.Ready ||
            _bootstrapState.value is BootstrapState.Checking ||
            _bootstrapState.value is BootstrapState.Downloading ||
            _bootstrapState.value is BootstrapState.Extracting ||
            _bootstrapState.value is BootstrapState.Configuring) {
            return
        }
        _bootstrapState.value = BootstrapState.Checking
        viewModelScope.launch {
            // First extract PRoot from APK assets (no network required)
            _prootState.value = BootstrapState.Checking
            when (val r = prootManager.ensureReady()) {
                is PRootManager.Result.AlreadyReady -> _prootState.value = BootstrapState.Ready
                is PRootManager.Result.Extracted -> _prootState.value = BootstrapState.Ready
                is PRootManager.Result.Failed -> {
                    _bootstrapState.value = BootstrapState.Failed(
                        "PRoot: ${r.reason}"
                    )
                    return@launch
                }
            }

            // Then Ubuntu rootfs
            _bootstrapState.value = BootstrapState.Downloading(0, 0)
            when (val r = bootstrap.ensureReady { done, total ->
                _bootstrapState.value = BootstrapState.Downloading(done, total)
            }) {
                BootstrapManager.Result.AlreadyReady ->
                    _bootstrapState.value = BootstrapState.Ready
                is BootstrapManager.Result.Downloaded -> {
                    _bootstrapState.value = BootstrapState.Extracting
                    _bootstrapState.value = BootstrapState.Configuring
                    _bootstrapState.value = BootstrapState.Ready
                }
                is BootstrapManager.Result.Failed -> {
                    _bootstrapState.value = BootstrapState.Failed(r.reason)
                }
            }
        }
    }

    fun openSession(title: String = "ubuntu"): UbuntuSession {
        val session = _manager.openSession(title)
        // Keep the session alive in the background
        TerminalService.start(getApplication())
        return session
    }

    fun closeSession(id: String) {
        _manager.closeSession(id)
        if (_manager.sessions.value.isEmpty()) {
            TerminalService.stop(getApplication())
        }
    }

    override fun onCleared() {
        _manager.closeAll()
        TerminalService.stop(getApplication())
        super.onCleared()
    }
}
