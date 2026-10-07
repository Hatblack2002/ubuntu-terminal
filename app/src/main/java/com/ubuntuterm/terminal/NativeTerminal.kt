package com.ubuntuterm.terminal

/**
 * JNI bridge to libterminal.so.
 *
 * Per project spec (section 15):
 *   libterminal.so is the ONLY native component. It is NOT Ubuntu —
 *   it is the thin glue required to attach the Android UI to a real
 *   Ubuntu process running inside PRoot.
 *
 * Per project spec (section 7):
 *   Communication uses ONLY:
 *     - fork() / execve()
 *     - PTY (openpty + ioctl TIOCSWINSZ)
 *     - read() / write() on the PTY master fd
 *     - kill() / waitpid()
 *   NO HTTP, NO REST, NO websockets.
 */
object NativeTerminal {

    init {
        System.loadLibrary("terminal")
    }

    /**
     * Spawns a child process attached to a PTY.
     *
     * @param cwd       Working directory (or null to inherit)
     * @param argv      argv[0] = binary path; must be non-empty
     * @param envp      envp = list of "KEY=VALUE" strings, or null for defaults
     * @param cols      Initial PTY width  (must be > 0)
     * @param rows      Initial PTY height (must be > 0)
     * @return          An opaque handle that packs (master_fd, pid).
     *                  Use it for read/write/wait/close/signal.
     *                  Returns 0 on failure (and throws IOException).
     */
    @JvmStatic
    external fun nativeSpawn(
        cwd: String?,
        argv: Array<String>,
        envp: Array<String>?,
        cols: Int,
        rows: Int
    ): Long

    /**
     * Writes bytes to the PTY master.
     * Returns number of bytes written (may be 0 if pipe is full),
     * or -1 on error.
     */
    @JvmStatic
    external fun nativeWrite(handle: Long, buf: ByteArray, off: Int, len: Int): Int

    /**
     * Reads up to `len` bytes from the PTY master.
     * Returns:
     *   n > 0  → n bytes were read into buf[off..off+n)
     *   0      → no data available (non-blocking)
     *   -1     → error
     *   -2     → EOF (child closed the PTY)
     */
    @JvmStatic
    external fun nativeRead(handle: Long, buf: ByteArray, off: Int, len: Int): Int

    /** Resizes the PTY window. Safe to call from any thread. */
    @JvmStatic
    external fun nativeSetSize(handle: Long, cols: Int, rows: Int)

    /**
     * Sends a signal to the child process.
     * Common values:
     *   2  = SIGINT  (Ctrl+C)
     *   9  = SIGKILL
     *   15 = SIGTERM
     *   20 = SIGTSTP (Ctrl+Z)
     */
    @JvmStatic
    external fun nativeSendSignal(handle: Long, signo: Int)

    /**
     * Waits for the child to exit.
     * @param blocking  true  → block until exit
     *                  false → poll
     * @return exit code 0..255, 128+sig, or -2 if still running
     */
    @JvmStatic
    external fun nativeWaitExit(handle: Long, blocking: Boolean): Int

    /** Closes the PTY master fd. Does NOT kill the child. */
    @JvmStatic
    external fun nativeClose(handle: Long)
}
