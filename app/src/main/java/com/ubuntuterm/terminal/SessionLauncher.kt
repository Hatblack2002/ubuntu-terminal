package com.ubuntuterm.terminal

import android.util.Log
import com.ubuntuterm.ubuntu.PRootRunner
import com.ubuntuterm.util.FileLocations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * SessionLauncher — testable seam between Compose UI and the native PTY layer.
 *
 * PROBLEM this solves (Fase 6 of the validation strategy):
 *   UbuntuSession.start() is a public function that is called from
 *   TerminalManager which is called from TerminalViewModel which is
 *   called from a Compose LaunchedEffect. The actual fork() runs on
 *   Dispatchers.IO (since v0.1.3). But UbuntuSession itself is not
 *   easily testable because:
 *     - it requires a real Context to access FileLocations
 *     - it requires real PRoot files on disk
 *     - it requires NativeTerminal to be loadable
 *     - it cannot be exercised from a JVM unit test
 *
 *   SessionLauncher factors out the orchestration logic into a class
 *   that takes injectable collaborators. For tests, we can inject a
 *   fake NativeTerminalBridge that does NOT call nativeSpawn().
 *
 * IMPORTANT:
 *   SessionLauncher does NOT replace UbuntuSession. UbuntuSession
 *   remains the runtime representation of one spawned process.
 *   SessionLauncher is the entry point used by the ViewModel (and
 *   by tests) to create+start a session without depending on Compose.
 *
 * Architecture (per Fase 6 of the validation spec):
 *
 *   Compose UI  ──>  ViewModel  ──>  SessionLauncher  ──>  NativeTerminalBridge
 *                                                       ──>  UbuntuSession
 *                                                       ──>  (real JNI)
 *                                                  or   ──>  (fake for tests)
 */
class SessionLauncher(
    private val nativeBridge: NativeTerminalBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val configFactory: () -> PRootRunner.LaunchConfig = { PRootRunner.defaultConfig() },
    private val log: (String, String) -> Unit = { tag, msg -> Log.i(tag, msg) }
) {
    /**
     * Creates a new UbuntuSession, builds argv/envp, calls nativeSpawn(),
     * and starts the reader + reaper coroutines.
     *
     * Returns the session. The session's StateFlow will transition:
     *   Starting → Running (on success)
     *   Starting → Failed  (on failure, with reason)
     *
     * The caller (ViewModel) can observe session.state and surface it
     * to Compose without needing to know how the session was created.
     */
    fun launch(title: String = "ubuntu"): UbuntuSession {
        log("SESSION_LAUNCH", "launch() called, title=$title")
        val config = configFactory()
        val session = UbuntuSession(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            config = config,
            nativeBridge = nativeBridge,
            scope = scope
        )
        session.start()
        return session
    }
}

/**
 * Bridge between Kotlin and NativeTerminal.
 *
 * In production: backed by NativeTerminal (real JNI calls).
 * In tests: backed by a fake that returns canned values without
 * touching JNI.
 *
 * Per Fase 10 (Crash Safety): every method here MUST be safe to call
 * from a try/catch. Native crashes (SIGSEGV) cannot be caught — those
 * are documented in ENVIRONMENT_CAPABILITIES.md as NOT VERIFIABLE.
 */
interface NativeTerminalBridge {
    /**
     * Spawns a child process attached to a PTY.
     * Returns a handle that packs (master_fd, pid), or 0 on failure
     * (after throwing an IOException which the caller should catch).
     */
    @Throws(IOException::class)
    fun spawn(
        cwd: String?,
        argv: Array<String>,
        envp: Array<String>?,
        cols: Int,
        rows: Int
    ): Long

    /** Writes bytes to the PTY master. Returns bytes written or -1 on error. */
    fun write(handle: Long, buf: ByteArray, off: Int, len: Int): Int

    /**
     * Reads up to `len` bytes from the PTY master.
     * Returns:
     *   n > 0 → bytes read
     *   0     → no data available (non-blocking)
     *   -1    → error
     *   -2    → EOF (child closed the PTY)
     */
    fun read(handle: Long, buf: ByteArray, off: Int, len: Int): Int

    /** Resizes the PTY. Safe to call from any thread. */
    fun setSize(handle: Long, cols: Int, rows: Int)

    /** Sends a signal to the child. signo: 2=SIGINT, 9=SIGKILL, 15=SIGTERM, etc. */
    fun sendSignal(handle: Long, signo: Int)

    /**
     * Waits for the child to exit.
     * @param blocking true → block, false → poll
     * @return exit code 0..255, 128+sig, -2 if still running, -1 on error
     */
    fun waitExit(handle: Long, blocking: Boolean): Int

    /** Closes the PTY master fd. Does NOT kill the child. */
    fun close(handle: Long)
}

/**
 * Production implementation of NativeTerminalBridge — delegates to
 * NativeTerminal (real JNI calls).
 *
 * This class exists so that UbuntuSession can depend on an interface
 * (testable) rather than on a Kotlin object (hard to mock).
 */
class RealNativeTerminalBridge : NativeTerminalBridge {
    override fun spawn(cwd: String?, argv: Array<String>, envp: Array<String>?, cols: Int, rows: Int): Long {
        return NativeTerminal.nativeSpawn(cwd, argv, envp, cols, rows)
    }
    override fun write(handle: Long, buf: ByteArray, off: Int, len: Int): Int {
        return NativeTerminal.nativeWrite(handle, buf, off, len)
    }
    override fun read(handle: Long, buf: ByteArray, off: Int, len: Int): Int {
        return NativeTerminal.nativeRead(handle, buf, off, len)
    }
    override fun setSize(handle: Long, cols: Int, rows: Int) {
        NativeTerminal.nativeSetSize(handle, cols, rows)
    }
    override fun sendSignal(handle: Long, signo: Int) {
        NativeTerminal.nativeSendSignal(handle, signo)
    }
    override fun waitExit(handle: Long, blocking: Boolean): Int {
        return NativeTerminal.nativeWaitExit(handle, blocking)
    }
    override fun close(handle: Long) {
        NativeTerminal.nativeClose(handle)
    }
}
