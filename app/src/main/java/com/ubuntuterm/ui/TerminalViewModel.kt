package com.ubuntuterm.ui

import android.app.Application
import android.util.Log
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

    /**
     * Last session-creation error, if any. Surfaced to the UI so the user
     * sees a real error message instead of the app silently dying.
     */
    private val _lastSessionError = MutableStateFlow<String?>(null)
    val lastSessionError: StateFlow<String?> = _lastSessionError.asStateFlow()

    fun clearSessionError() {
        _lastSessionError.value = null
    }

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

    /**
     * Opens a new Ubuntu session.
     *
     * CRITICAL FIX (v0.1.4):
     *   Previously `TerminalService.start(getApplication())` was called
     *   directly from here. That does `context.startForegroundService()`
     *   which on Android 12+ can throw
     *   `ForegroundServiceStartNotAllowedException` if the app is in a
     *   background-restricted state. That exception was NOT caught and
     *   crashed the Activity when the user tapped "+".
     *
     *   Now we:
     *     1. Log every stage (PLUS_CLICK → SESSION_CREATE_START → ...).
     *     2. Wrap TerminalService.start() in try/catch. If it fails, we
     *        continue anyway — the session can still run without the
     *        foreground service, it just won't survive backgrounding.
     *     3. Wrap _manager.openSession() in try/catch too. If PRoot is
     *        not executable or nativeSpawn throws, we capture the error
     *        and surface it via `lastSessionError` instead of crashing.
     *     4. Return the session (or null on failure) so the caller can
     *        react.
     */
    fun openSession(title: String = "ubuntu"): UbuntuSession? {
        android.util.Log.i("PLUS_CLICK", "openSession() invoked, title=$title")
        _lastSessionError.value = null

        // Stage: SESSION_CREATE_START
        android.util.Log.i("SESSION_CREATE_START", "calling _manager.openSession()")
        val session = try {
            _manager.openSession(title)
        } catch (t: Throwable) {
            android.util.Log.e("SESSION_CREATE_FAILED",
                "_manager.openSession threw: ${t.javaClass.simpleName}: ${t.message}", t)
            _lastSessionError.value =
                "Session create failed: ${t.javaClass.simpleName}: ${t.message}"
            return null
        }
        android.util.Log.i("SESSION_REGISTERED",
            "session created id=${session.id} state=${session.state.value}")

        // Stage: foreground service — best-effort, NOT fatal
        android.util.Log.i("FG_SERVICE_START", "calling TerminalService.start()")
        try {
            TerminalService.start(getApplication())
            android.util.Log.i("FG_SERVICE_STARTED", "TerminalService started OK")
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException, SecurityException,
            // IllegalStateException, etc. We log and continue — the session
            // can run without the foreground service, it just won't survive
            // backgrounding.
            android.util.Log.w("FG_SERVICE_FAILED",
                "TerminalService.start threw: ${t.javaClass.simpleName}: ${t.message}")
            // Do NOT set lastSessionError here — the session itself is OK.
        }

        return session
    }

    fun closeSession(id: String) {
        _manager.closeSession(id)
        if (_manager.sessions.value.isEmpty()) {
            try {
                TerminalService.stop(getApplication())
            } catch (t: Throwable) {
                android.util.Log.w("FG_SERVICE_STOP_FAILED",
                    "TerminalService.stop threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun onCleared() {
        _manager.closeAll()
        try {
            TerminalService.stop(getApplication())
        } catch (t: Throwable) {
            android.util.Log.w("FG_SERVICE_STOP_FAILED",
                "onCleared: TerminalService.stop threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        super.onCleared()
    }
}

