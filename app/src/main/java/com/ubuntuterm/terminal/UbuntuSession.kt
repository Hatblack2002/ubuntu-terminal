package com.ubuntuterm.terminal

import android.util.Log
import androidx.compose.runtime.Immutable
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
    private val config: PRootRunner.LaunchConfig
) {

    companion object {
        private const val TAG = "UbuntuSession"
        private const val READ_BUF = 8 * 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var handle: Long = 0L
    private var readerJob: Job? = null
    private val closed = AtomicBoolean(false)

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

    fun start() {
        if (handle != 0L) {
            Log.w(TAG, "Session $id already started")
            return
        }
        if (closed.get()) {
            Log.w(TAG, "Session $id already closed")
            return
        }
        _state.value = SessionState.Starting
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
                Log.i("SESSION_START", "  cwd=$cwd")

                val argv: Array<String>
                val envp: Array<String>
                withContext(Dispatchers.IO) {
                    Log.i("SESSION_START", "  building argv/envp on Dispatchers.IO")
                    // buildArgv() does `require(proot.exists() && proot.canExecute())`
                    // which can throw IllegalArgumentException. We keep it
                    // inside the try/catch so it does not crash the Activity.
                    argv = PRootRunner.buildArgv(config).toTypedArray()
                    envp = PRootRunner.buildEnvp(config).toTypedArray()
                    Log.i("PRoot_ARGS_READY",
                        "  argv[0]=${argv.firstOrNull()} argv.size=${argv.size} envp.size=${envp.size}")
                }

                Log.i("NATIVE_SPAWN_START",
                    "  calling NativeTerminal.nativeSpawn() on Dispatchers.IO")
                val h = withContext(Dispatchers.IO) {
                    NativeTerminal.nativeSpawn(
                        cwd,
                        argv,
                        envp,
                        config.initialCols,
                        config.initialRows
                    )
                }
                Log.i("NATIVE_SPAWN_RETURN", "  nativeSpawn returned handle=$h")

                if (h == 0L) {
                    // nativeSpawn returns 0 when it threw an IOException from
                    // JNI. We treat that as a failed spawn.
                    throw IOException("nativeSpawn returned 0 (JNI threw IOException)")
                }

                handle = h
                Log.i(TAG, "Spawned pid for session $id (handle=$handle)")
                _state.value = SessionState.Running

                Log.i("PTY_READER_START", "  starting reader for session $id")
                startReader()
                Log.i("PTY_REAPER_START", "  starting reaper for session $id")
                startReaper()
                Log.i("SESSION_RUNNING",
                    "  session $id is Running; reader+reaper launched")
            } catch (t: Throwable) {
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
                        NativeTerminal.nativeRead(handle, buf, 0, buf.size)
                    }
                } catch (t: Throwable) {
                    Log.e("PTY_READER",
                        "  nativeRead threw: ${t.javaClass.simpleName}: ${t.message}")
                    break
                }
                when {
                    n > 0 -> {
                        val copy = buf.copyOfRange(0, n)
                        _output.emit(copy)
                    }
                    n == -2 -> {
                        Log.i("PTY_READER", "  session $id: EOF from PTY (n=-2)")
                        break
                    }
                    n == 0 -> {
                        // No data — short sleep to avoid spin
                        kotlinx.coroutines.delay(10)
                    }
                    else -> {
                        // Error
                        Log.e("PTY_READER", "  session $id: read returned $n (errno)")
                        kotlinx.coroutines.delay(50)
                    }
                }
            }
            Log.i("PTY_READER", "  reader coroutine exited for session $id")
        }
    }

    private fun startReaper() {
        scope.launch {
            Log.i("PTY_REAPER", "  reaper coroutine started for session $id")
            while (isActive && !closed.get()) {
                val code = try {
                    NativeTerminal.nativeWaitExit(handle, false)
                } catch (t: Throwable) {
                    Log.e("PTY_REAPER",
                        "  nativeWaitExit threw: ${t.javaClass.simpleName}: ${t.message}")
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
            NativeTerminal.nativeWrite(handle, data, 0, data.size)
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
            NativeTerminal.nativeSendSignal(handle, if (force) 9 else 15)
        } catch (t: Throwable) {
            Log.e(TAG, "kill failed", t)
        }
    }

    /** Resizes the PTY window. */
    fun resize(cols: Int, rows: Int) {
        if (handle == 0L) return
        try {
            NativeTerminal.nativeSetSize(handle, cols, rows)
        } catch (t: Throwable) {
            Log.w(TAG, "resize failed: ${t.message}")
        }
    }

    /** Closes the session and releases native resources. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.i(TAG, "Closing session $id")
        try {
            if (handle != 0L) {
                NativeTerminal.nativeClose(handle)
                handle = 0L
            }
        } catch (t: Throwable) {
            Log.w(TAG, "close error: ${t.message}")
        }
        readerJob?.cancel()
        scope.cancel()
        _state.value = _state.value.let {
            if (it is SessionState.Exited) it else SessionState.Closed
        }
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
