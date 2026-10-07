package com.ubuntuterm.terminal

import android.util.Log
import androidx.compose.runtime.Immutable
import com.ubuntuterm.diagnostic.DiagnosticLog
import com.ubuntuterm.diagnostic.DiagnosticReport
import com.ubuntuterm.ubuntu.PRootRunner
import com.ubuntuterm.util.FileLocations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents one running Ubuntu session.
 *
 * Internally backed by:
 *   - libterminal.so (JNI for PTY)
 *   - PRoot (running the actual Ubuntu rootfs)
 *
 * Per project spec (section 6 & 10):
 *   - User input is forwarded verbatim to the PTY master fd.
 *   - PRoot translates syscalls and the real /bin/bash inside Ubuntu
 *     interprets them. We do NOT parse or simulate commands.
 *   - Output is read from the PTY master and forwarded to the UI as
 *     a raw byte stream.
 *
 * Per project spec (section 19): NO SIMULATION.
 */
class UbuntuSession(
    val id: String,
    val title: String,
    private val config: PRootRunner.LaunchConfig,
    // Injectable for tests; defaults to RealNativeTerminalBridge in production.
    // Per Fase 6: this lets us test UbuntuSession without touching real JNI.
    private val nativeBridge: NativeTerminalBridge = RealNativeTerminalBridge(),
    // Injectable scope for tests; defaults to Dispatchers.IO in production.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private const val TAG = "UbuntuSession"
        private const val READ_BUF = 8 * 1024
    }

    private var handle: Long = 0L
    private var readerJob: Job? = null
    private val closed = AtomicBoolean(false)

    // v0.1.15: Ring buffer of last 4KB of PTY output for diagnostics.
    // This lets the diagnostic report show what bash wrote before dying.
    private val ptyTail = java.util.ArrayDeque<ByteArray>()
    private val ptyTailMax = 4096
    private var ptyTotalBytes = 0L
    private val ptyLock = Any()

    /** Raw bytes emitted by the PTY (terminal escape sequences included). */
    private val _output = MutableSharedFlow<ByteArray>(
        replay = 8,
        extraBufferCapacity = 64
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _exitCode = MutableStateFlow<Int?>(null)
    val exitCode: StateFlow<Int?> = _exitCode.asStateFlow()

    /** v0.1.15: Returns the last 4KB of PTY output (for diagnostic report). */
    fun ptyOutputSnapshot(): String = synchronized(ptyLock) {
        if (ptyTail.isEmpty()) "(no PTY output received)" else
            ptyTail.joinToString("") { String(it, Charsets.UTF_8) }
    }

    /** v0.1.15: Total bytes read from PTY. */
    fun ptyBytesRead(): Long = synchronized(ptyLock) { ptyTotalBytes }

    fun start() {
        if (handle != 0L) {
            Log.w(TAG, "Session $id already started")
            DiagnosticLog.session("SESSION_START", "session $id already started, skipping")
            return
        }
        if (closed.get()) {
            Log.w(TAG, "Session $id already closed")
            DiagnosticLog.session("SESSION_START", "session $id already closed, skipping")
            return
        }
        _state.value = SessionState.Starting
        DiagnosticReport.SessionSnapshot.lastSessionState = "Starting"
        DiagnosticLog.session("SESSION_START", "start() invoked for session $id")
        Log.i("SESSION_START", "start() invoked for session $id")

        // CRITICAL FIX (v0.1.3):
        //   `nativeSpawn()` calls `fork()` from the JNI layer. Calling `fork()`
        //   from Android's Main thread (where this function used to run when
        //   invoked from Compose) can cause ART to deadlock the GC or to
        //   SIGABRT the whole process — which matches the symptom of the app
        //   "closing unexpectedly right after the bootstrap finishes".
        //
        //   We now run ALL of:
        //     - PRootRunner.buildArgv()      (may throw IllegalArgumentException)
        //     - PRootRunner.buildEnvp()
        //     - NativeTerminal.nativeSpawn()  (does the actual fork)
        //   on Dispatchers.IO, AND we wrap them inside the SAME try/catch.
        //
        //   The JNI/PTY architecture is NOT changed. fork() is still fork().
        //   Only the dispatcher that calls it is different.
        scope.launch {
            try {
                val cwd = FileLocations.rootDir.absolutePath
                DiagnosticReport.PRootSnapshot.cwd = cwd
                DiagnosticLog.session("SESSION_START", "  cwd=$cwd")
                Log.i("SESSION_START", "  cwd=$cwd")

                val argv: Array<String>
                val envp: Array<String>
                withContext(Dispatchers.IO) {
                    DiagnosticLog.session("SESSION_START", "  building argv/envp on Dispatchers.IO")
                    Log.i("SESSION_START", "  building argv/envp on Dispatchers.IO")
                    // buildArgv() does `require(proot.exists() && proot.canExecute())`
                    // which can throw IllegalArgumentException. We keep it
                    // inside the try/catch so it does not crash the Activity.
                    argv = PRootRunner.buildArgv(config).toTypedArray()
                    envp = PRootRunner.buildEnvp(config).toTypedArray()
                    // Populate PRoot snapshot for diagnostics
                    DiagnosticReport.PRootSnapshot.argv = argv.toList()
                    DiagnosticReport.PRootSnapshot.envp = envp.toList()
                    DiagnosticReport.PRootSnapshot.binaryPath = argv.firstOrNull() ?: "(unknown)"
                    DiagnosticReport.PRootSnapshot.rootfsPath = config.rootfs.absolutePath
                    DiagnosticLog.proot("PRoot_ARGS_READY",
                        "argv[0]=${argv.firstOrNull()} argv.size=${argv.size} envp.size=${envp.size}")
                    Log.i("PRoot_ARGS_READY",
                        "  argv[0]=${argv.firstOrNull()} argv.size=${argv.size} envp.size=${envp.size}")
                }

                DiagnosticLog.native("NATIVE_SPAWN_START",
                    "calling nativeBridge.spawn() on Dispatchers.IO; cols=${config.initialCols} rows=${config.initialRows}")
                Log.i("NATIVE_SPAWN_START",
                    "  calling nativeBridge.spawn() on Dispatchers.IO")
                DiagnosticReport.NativeSnapshot.lastSpawnCalled = true
                val h = withContext(Dispatchers.IO) {
                    nativeBridge.spawn(
                        cwd,
                        argv,
                        envp,
                        config.initialCols,
                        config.initialRows
                    )
                }
                DiagnosticReport.NativeSnapshot.lastSpawnResult = "handle=0x${h.toString(16)}"
                DiagnosticReport.NativeSnapshot.lastSpawnHandle = h
                // Handle packs (master_fd << 32) | (pid & 0xFFFFFFFF)
                val masterFd = (h ushr 32).toInt()
                val pid = (h and 0xFFFFFFFFL).toInt()
                DiagnosticReport.NativeSnapshot.lastSpawnMasterFd = masterFd
                DiagnosticReport.NativeSnapshot.lastSpawnPid = pid
                DiagnosticLog.native("NATIVE_SPAWN_RETURN",
                    "nativeBridge.spawn returned handle=0x${h.toString(16)} pid=$pid master_fd=$masterFd")
                Log.i("NATIVE_SPAWN_RETURN", "  nativeBridge.spawn returned handle=$h")

                if (h == 0L) {
                    // nativeSpawn returns 0 when it threw an IOException from
                    // JNI. We treat that as a failed spawn.
                    DiagnosticReport.NativeSnapshot.lastSpawnException = "handle=0 (JNI threw)"
                    throw IOException("nativeSpawn returned 0 (JNI threw IOException)")
                }

                handle = h
                DiagnosticLog.session("SESSION_RUNNING",
                    "session $id is Running; handle=0x${h.toString(16)} pid=$pid master_fd=$masterFd")
                Log.i(TAG, "Spawned pid for session $id (handle=$handle)")
                _state.value = SessionState.Running
                DiagnosticReport.SessionSnapshot.lastSessionState = "Running"

                DiagnosticLog.session("PTY_READER_START", "starting reader for session $id")
                Log.i("PTY_READER_START", "  starting reader for session $id")
                startReader()
                DiagnosticLog.session("PTY_REAPER_START", "starting reaper for session $id")
                Log.i("PTY_REAPER_START", "  starting reaper for session $id")
                startReaper()
                DiagnosticLog.session("SESSION_RUNNING",
                    "session $id is Running; reader+reaper launched")
                Log.i("SESSION_RUNNING",
                    "  session $id is Running; reader+reaper launched")
            } catch (t: Throwable) {
                DiagnosticReport.SessionSnapshot.lastSessionState = "Failed: ${t.message}"
                DiagnosticReport.NativeSnapshot.lastSpawnException =
                    "${t.javaClass.simpleName}: ${t.message}"
                DiagnosticLog.error("SESSION_FAILED",
                    "session $id failed: ${t.javaClass.simpleName}: ${t.message}", t)
                Log.e(TAG, "Failed to spawn session", t)
                Log.e("SESSION_FAILED",
                    "  session $id failed: ${t.javaClass.simpleName}: ${t.message}")
                _state.value = SessionState.Failed(t.message ?: "spawn failed")
            }
        }
    }

    private fun startReader() {
        readerJob = scope.launch {
            val buf = ByteArray(READ_BUF)
            Log.i("PTY_READER", "  reader coroutine started for session $id")
            while (isActive && !closed.get()) {
                val n = try {
                    withContext(Dispatchers.IO) {
                        nativeBridge.read(handle, buf, 0, buf.size)
                    }
                } catch (t: Throwable) {
                    Log.e("PTY_READER",
                        "  nativeBridge.read threw: ${t.javaClass.simpleName}: ${t.message}")
                    break
                }
                when {
                    n > 0 -> {
                        val copy = buf.copyOfRange(0, n)
                        _output.emit(copy)
                        // v0.1.15: accumulate in ring buffer for diagnostics
                        synchronized(ptyLock) {
                            ptyTail.addLast(copy)
                            ptyTotalBytes += n
                            var total = ptyTail.sumOf { it.size }
                            while (total > ptyTailMax && ptyTail.isNotEmpty()) {
                                total -= ptyTail.removeFirst().size
                            }
                        }
                        // Log first 200 bytes for logcat visibility
                        val preview = String(copy, Charsets.UTF_8)
                            .replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
                            .replace(Regex("\u001B\\][^\u0007]*\u0007"), "")
                            .replace("\r", "").replace("\n", "\\n")
                            .take(200)
                        Log.d("PTY_READER", "read $n bytes: $preview")
                    }
                    n == -2 -> {
                        Log.i("PTY_READER", "  session $id: EOF from PTY (n=-2)")
                        break
                    }
                    n == 0 -> {
                        kotlinx.coroutines.delay(10)
                    }
                    else -> {
                        Log.e("PTY_READER", "  session $id: read returned $n (errno)")
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
            Log.i("PTY_READER", "  reader coroutine exited for session $id, totalBytes=$ptyTotalBytes")
        }
    }

    private fun startReaper() {
        scope.launch {
            Log.i("PTY_REAPER", "  reaper coroutine started for session $id")
            while (isActive && !closed.get()) {
                val code = try {
                    nativeBridge.waitExit(handle, false)
                } catch (t: Throwable) {
                    Log.e("PTY_REAPER",
                        "  nativeBridge.waitExit threw: ${t.javaClass.simpleName}: ${t.message}")
                    -1
                }
                if (code != -2) {
                    Log.i(TAG, "Session $id exited with code $code")
                    Log.i("PTY_REAPER", "  session $id reaped, exitCode=$code")
                    _exitCode.value = code
                    _state.value = SessionState.Exited(code)
                    close()
                    break
                }
                kotlinx.coroutines.delay(250)
            }
            Log.i("PTY_REAPER", "  reaper coroutine exited for session $id")
        }
    }

    /** Writes raw bytes (typically UTF-8 encoded user input) to the PTY. */
    fun write(data: ByteArray) {
        if (closed.get() || handle == 0L) return
        try {
            nativeBridge.write(handle, data, 0, data.size)
        } catch (t: Throwable) {
            Log.e(TAG, "write failed", t)
        }
    }

    /** Convenience for writing a UTF-8 string. */
    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    /** Sends a literal Enter key. */
    fun sendEnter() = write(byteArrayOf(0x0D))

    /** Sends Ctrl+C (SIGINT via terminal line discipline). */
    fun sendCtrlC() = write(byteArrayOf(0x03))

    /** Sends Ctrl+D (EOF). */
    fun sendCtrlD() = write(byteArrayOf(0x04))

    /** Sends Ctrl+Z (SIGTSTP). */
    fun sendCtrlZ() = write(byteArrayOf(0x1A))

    /** Sends SIGINT (or SIGKILL if force). */
    fun kill(force: Boolean = false) {
        if (handle == 0L) return
        try {
            nativeBridge.sendSignal(handle, if (force) 9 else 15)
        } catch (t: Throwable) {
            Log.e(TAG, "kill failed", t)
        }
    }

    /** Resizes the PTY window. */
    fun resize(cols: Int, rows: Int) {
        if (handle == 0L) return
        try {
            nativeBridge.setSize(handle, cols, rows)
        } catch (t: Throwable) {
            Log.w(TAG, "resize failed: ${t.message}")
        }
    }

    /** Closes the session and releases native resources. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "Closing session $id")
        DiagnosticLog.session("SESSION_CLOSE", "closing session $id, handle=0x${handle.toString(16)}")

        // v0.1.13: Kill the child process FIRST, before closing the PTY.
        // If we close the PTY master fd first, the child gets SIGHUP but
        // may not die immediately, leaving zombie processes and leaked fds.
        if (handle != 0L) {
            try {
                nativeBridge.sendSignal(handle, 9)  // SIGKILL
                DiagnosticLog.session("SESSION_CLOSE", "SIGKILL sent to child")
            } catch (t: Throwable) {
                DiagnosticLog.error("SESSION_CLOSE", "SIGKILL failed: ${t.message}", t)
            }
            // Brief sleep to let the child die and the reaper reap it
            try { Thread.sleep(100) } catch (e: InterruptedException) {}
            try {
                nativeBridge.close(handle)
                DiagnosticLog.session("SESSION_CLOSE", "nativeBridge.close done")
            } catch (t: Throwable) {
                DiagnosticLog.error("SESSION_CLOSE", "close error: ${t.message}", t)
            }
            handle = 0L
        }
        readerJob?.cancel()
        scope.cancel()
        _state.value = _state.value.let {
            if (it is SessionState.Exited) it else SessionState.Closed
        }
        DiagnosticLog.session("SESSION_CLOSE", "session $id fully closed")
    }

    @Immutable
    sealed class SessionState {
        object Idle : SessionState()
        object Starting : SessionState()
        object Running : SessionState()
        data class Exited(val code: Int) : SessionState()
        object Closed : SessionState()
        data class Failed(val reason: String) : SessionState()
    }
}
