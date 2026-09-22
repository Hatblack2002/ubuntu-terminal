package com.ubuntuterm.terminal

import android.util.Log
import androidx.compose.runtime.Immutable
import com.ubuntuterm.ubuntu.PRootRunner
import com.ubuntuterm.util.FileLocations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages the lifecycle of multiple UbuntuSession instances.
 *
 * Provides:
 *   - openSession(): creates and starts a new session
 *   - closeSession(id): stops a session
 *   - getActiveSession(): the currently-visible session
 *   - List of all live sessions (StateFlow) for Compose observation
 *
 * Per project spec (section 22): "Utilizar Ubuntu real". This manager
 * is the seam that connects the UI to real Ubuntu processes — every
 * session it creates spawns an actual PRoot+Ubuntu process via JNI.
 */
class TerminalManager {

    private val _sessions = MutableStateFlow<List<UbuntuSession>>(emptyList())
    val sessions: StateFlow<List<UbuntuSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId.asStateFlow()

    val activeSession: UbuntuSession?
        get() = _sessions.value.find { it.id == _activeSessionId.value }

    fun openSession(title: String = "ubuntu"): UbuntuSession {
        val id = UUID.randomUUID().toString()
        val config = PRootRunner.defaultConfig().copy()
        val session = UbuntuSession(id, title, config)
        session.start()

        _sessions.value = _sessions.value + session
        _activeSessionId.value = id
        Log.i(TAG, "Opened session $id (total=${_sessions.value.size})")
        return session
    }

    fun setActive(id: String) {
        if (_sessions.value.any { it.id == id }) {
            _activeSessionId.value = id
        }
    }

    fun closeSession(id: String) {
        val session = _sessions.value.find { it.id == id } ?: return
        session.kill(force = true)
        session.close()
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_activeSessionId.value == id) {
            _activeSessionId.value = _sessions.value.firstOrNull()?.id
        }
        Log.i(TAG, "Closed session $id (total=${_sessions.value.size})")
    }

    fun closeAll() {
        _sessions.value.forEach { it.close() }
        _sessions.value = emptyList()
        _activeSessionId.value = null
    }

    companion object {
        private const val TAG = "TerminalManager"
    }
}

@Immutable
data class SessionInfo(
    val id: String,
    val title: String,
    val state: UbuntuSession.SessionState
)
