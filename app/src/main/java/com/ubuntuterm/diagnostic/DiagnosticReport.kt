package com.ubuntuterm.diagnostic

import android.content.Context
import android.os.Build
import com.ubuntuterm.BuildConfig
import com.ubuntuterm.util.FileLocations
import java.io.File

/**
 * DiagnosticReport — generates a human-readable text report of the app's
 * internal state, suitable for display in the Settings → Diagnostics
 * screen or for copying to the clipboard / sharing as a file.
 *
 * The report includes:
 *   - APP VERSION (versionName, versionCode, applicationId)
 *   - DEVICE INFO (model, Android version, SDK, manufacturer)
 *   - BOOTSTRAP (rootfs exists, size, marker; PRoot binary exists, executable)
 *   - SESSION (last requested, last created, current state)
 *   - PRoot (binary path, rootfs path, arguments, cwd) — captured at spawn time
 *   - NATIVE (JNI loaded?, last spawn result, errno)
 *   - SERVICE (last start requested, result, exception)
 *   - LAST ERROR (most recent error event)
 *   - LAST EVENTS (last 50 events from DiagnosticLog)
 *
 * USAGE:
 *   val report = DiagnosticReport.generate(context)
 *   // Display in a dialog, or copy to clipboard, or write to a file.
 *
 * NOT A LOGCAT REPLACEMENT:
 *   If the app process is killed by SIGSEGV before it can write to
 *   DiagnosticLog, the report will be missing those events. For native
 *   crashes, logcat is still needed. But for every Kotlin-level event,
 *   this report captures it.
 */
object DiagnosticReport {

    /**
     * Snapshot of native spawn state — populated by NativeTerminalBridge
     * implementations when spawn() is called.
     */
    object NativeSnapshot {
        @Volatile var jniLoaded: Boolean = false
        @Volatile var lastSpawnCalled: Boolean = false
        @Volatile var lastSpawnResult: String = "(never called)"
        @Volatile var lastSpawnHandle: Long = 0L
        @Volatile var lastSpawnPid: Int = 0
        @Volatile var lastSpawnMasterFd: Int = 0
        @Volatile var lastSpawnErrno: Int = 0
        @Volatile var lastSpawnException: String? = null
    }

    /**
     * Snapshot of session state.
     */
    object SessionSnapshot {
        @Volatile var sessionRequested: Boolean = false
        @Volatile var sessionCreated: Boolean = false
        @Volatile var lastSessionId: String? = null
        @Volatile var lastSessionState: String = "(none)"
    }

    /**
     * Snapshot of PRoot info — populated by PRootRunner when buildArgv is called.
     */
    object PRootSnapshot {
        @Volatile var binaryPath: String = "(unknown)"
        @Volatile var rootfsPath: String = "(unknown)"
        @Volatile var cwd: String = "(unknown)"
        @Volatile var argv: List<String> = emptyList()
        @Volatile var envp: List<String> = emptyList()
        @Volatile var buildArgvException: String? = null
    }

    /**
     * Snapshot of TerminalService state.
     */
    object ServiceSnapshot {
        @Volatile var startRequested: Boolean = false
        @Volatile var startResult: String = "(not started)"
        @Volatile var startException: String? = null
    }

    /**
     * Generate the full text report.
     */
    fun generate(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== UBUNTU TERMINAL DIAGNOSTICS ===\n")
        sb.append("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date())}\n\n")

        // === APP VERSION ===
        sb.append("APP VERSION\n")
        sb.append("  versionName:        ${BuildConfig.VERSION_NAME}\n")
        sb.append("  versionCode:        ${BuildConfig.VERSION_CODE}\n")
        sb.append("  applicationId:      ${BuildConfig.APPLICATION_ID}\n")
        sb.append("  buildType:          ${BuildConfig.BUILD_TYPE}\n")
        sb.append("  debuggable:         ${BuildConfig.DEBUG}\n\n")

        // === DEVICE INFO ===
        sb.append("DEVICE INFO\n")
        sb.append("  manufacturer:       ${Build.MANUFACTURER}\n")
        sb.append("  model:              ${Build.MODEL}\n")
        sb.append("  device:             ${Build.DEVICE}\n")
        sb.append("  product:            ${Build.PRODUCT}\n")
        sb.append("  brand:              ${Build.BRAND}\n")
        sb.append("  Android release:    ${Build.VERSION.RELEASE}\n")
        sb.append("  SDK level:          ${Build.VERSION.SDK_INT}\n")
        sb.append("  kernel ptrace_scope: ${getProp("ro.kernel.yama.ptrace_scope")}\n\n")

        // === FILE LOCATIONS ===
        sb.append("FILE LOCATIONS\n")
        try {
            sb.append("  rootDir:            ${FileLocations.rootDir.absolutePath}\n")
            sb.append("  ubuntuRootDir:      ${FileLocations.ubuntuRootDir.absolutePath}\n")
            sb.append("  prootDir:            ${FileLocations.prootDir.absolutePath}\n")
            sb.append("  prootBinary:         ${FileLocations.prootBinary.absolutePath}\n")
            sb.append("  readyMarker:        ${FileLocations.readyMarker.absolutePath}\n")
            sb.append("  cacheDir:           ${FileLocations.cacheDir.absolutePath}\n")
        } catch (t: Throwable) {
            sb.append("  (could not read FileLocations: ${t.message})\n")
        }
        sb.append("\n")

        // === BOOTSTRAP ===
        sb.append("BOOTSTRAP\n")
        try {
            val rootfs = FileLocations.ubuntuRootDir
            val exists = rootfs.exists()
            sb.append("  rootfs exists:      ${if (exists) "YES" else "NO"}\n")
            if (exists) {
                val size = dirSize(rootfs)
                sb.append("  rootfs size:        ${humanBytes(size)} ($size bytes)\n")
                sb.append("  rootfs file count:  ${countFiles(rootfs)}\n")
                // Verify /bin is a symlink (or regular file if buggy extractor)
                val binDir = File(rootfs, "bin")
                val binBash = File(rootfs, "bin/bash")
                sb.append("  /bin type:          ${fileType(binDir)}\n")
                if (binDir.exists()) {
                    sb.append("  /bin isSymlink:     ${binDir.isSymlink()}\n")
                    if (binDir.isSymlink()) {
                        sb.append("  /bin -> readlink:   ${readlinkSafe(binDir)}\n")
                    }
                }
                sb.append("  /bin/bash exists:   ${binBash.exists()}\n")
                if (binBash.exists()) {
                    sb.append("  /bin/bash type:     ${fileType(binBash)}\n")
                    sb.append("  /bin/bash exec:     ${binBash.canExecute()}\n")
                }
                // Count symlinks in the rootfs (best-effort)
                val symlinkCount = countSymlinks(rootfs, maxDepth = 2)
                sb.append("  symlinks (top 2):   $symlinkCount\n")
            }
            val marker = FileLocations.readyMarker
            sb.append("  readyMarker exists: ${if (marker.exists()) "YES" else "NO"}\n")
            if (marker.exists()) {
                sb.append("  readyMarker content:\n")
                marker.readText().lines().forEach { line ->
                    if (line.isNotBlank()) sb.append("    $line\n")
                }
            }
        } catch (t: Throwable) {
            sb.append("  (error reading bootstrap state: ${t.message})\n")
        }
        sb.append("\n")

        // === PRoot ===
        sb.append("PRoot\n")
        try {
            val proot = FileLocations.prootBinary
            sb.append("  binary path:        ${proot.absolutePath}\n")
            sb.append("  binary exists:      ${if (proot.exists()) "YES" else "NO"}\n")
            if (proot.exists()) {
                sb.append("  binary size:        ${humanBytes(proot.length())}\n")
                sb.append("  binary executable:  ${proot.canExecute()}\n")
                sb.append("  binary isFile:      ${proot.isFile}\n")
            }
            // Also check the loader
            val loader = File(FileLocations.prootDir, "libexec/proot/loader")
            sb.append("  loader path:        ${loader.absolutePath}\n")
            sb.append("  loader exists:      ${if (loader.exists()) "YES" else "NO"}\n")
            if (loader.exists()) {
                sb.append("  loader executable:  ${loader.canExecute()}\n")
            }
            // PRoot snapshot
            sb.append("  last rootfs path:   ${PRootSnapshot.rootfsPath}\n")
            sb.append("  last cwd:           ${PRootSnapshot.cwd}\n")
            sb.append("  last argv:          ${PRootSnapshot.argv.joinToString(" ")}\n")
            sb.append("  last envp size:     ${PRootSnapshot.envp.size}\n")
            if (PRootSnapshot.buildArgvException != null) {
                sb.append("  buildArgv exception: ${PRootSnapshot.buildArgvException}\n")
            }
        } catch (t: Throwable) {
            sb.append("  (error reading PRoot state: ${t.message})\n")
        }
        sb.append("\n")

        // === SESSION ===
        sb.append("SESSION\n")
        sb.append("  session requested:  ${if (SessionSnapshot.sessionRequested) "YES" else "NO"}\n")
        sb.append("  session created:    ${if (SessionSnapshot.sessionCreated) "YES" else "NO"}\n")
        sb.append("  last session id:    ${SessionSnapshot.lastSessionId ?: "(none)"}\n")
        sb.append("  last session state: ${SessionSnapshot.lastSessionState}\n\n")

        // === NATIVE ===
        sb.append("NATIVE\n")
        sb.append("  JNI loaded:         ${if (NativeSnapshot.jniLoaded) "YES" else "NO (or not yet)"}\n")
        sb.append("  spawn called:      ${if (NativeSnapshot.lastSpawnCalled) "YES" else "NO"}\n")
        sb.append("  spawn result:      ${NativeSnapshot.lastSpawnResult}\n")
        sb.append("  last handle:       0x${NativeSnapshot.lastSpawnHandle.toString(16)}\n")
        sb.append("  last pid:          ${NativeSnapshot.lastSpawnPid}\n")
        sb.append("  last master_fd:    ${NativeSnapshot.lastSpawnMasterFd}\n")
        sb.append("  last errno:        ${NativeSnapshot.lastSpawnErrno}\n")
        if (NativeSnapshot.lastSpawnException != null) {
            sb.append("  spawn exception:   ${NativeSnapshot.lastSpawnException}\n")
        }
        sb.append("\n")

        // === SERVICE ===
        sb.append("SERVICE\n")
        sb.append("  start requested:   ${if (ServiceSnapshot.startRequested) "YES" else "NO"}\n")
        sb.append("  start result:      ${ServiceSnapshot.startResult}\n")
        if (ServiceSnapshot.startException != null) {
            sb.append("  start exception:   ${ServiceSnapshot.startException}\n")
        }
        sb.append("\n")

        // === LAST ERROR ===
        sb.append("LAST ERROR\n")
        val lastErr = DiagnosticLog.lastError.value
        if (lastErr != null) {
            sb.append("  timestamp:         ${lastErr.formattedTimestamp}\n")
            sb.append("  tag:              ${lastErr.tag}\n")
            sb.append("  message:          ${lastErr.message}\n")
            if (lastErr.throwable != null) {
                sb.append("  exception:        ${lastErr.throwable.javaClass.name}\n")
                sb.append("  exception msg:    ${lastErr.throwable.message}\n")
                sb.append("  stack trace (top 5):\n")
                lastErr.throwable.stackTrace.take(5).forEach {
                    sb.append("    at $it\n")
                }
            }
        } else {
            sb.append("  (no error recorded)\n")
        }
        sb.append("\n")

        // === LAST EVENTS ===
        sb.append("LAST EVENTS (most recent 50)\n")
        val events = DiagnosticLog.events.value.takeLast(50)
        if (events.isEmpty()) {
            sb.append("  (no events recorded)\n")
        } else {
            events.forEach { e ->
                sb.append("  [${e.formattedTimestamp}] [${e.category.label}] ${e.tag}: ${e.message}\n")
            }
        }
        sb.append("\n")

        sb.append("=== END OF DIAGNOSTICS ===\n")
        return sb.toString()
    }

    // ----- helpers -----

    private fun getProp(name: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", name))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.ifEmpty { "(empty)" }
        } catch (t: Throwable) {
            "(unavailable: ${t.message})"
        }
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.2f KB".format(bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return "%.2f MB".format(bytes / 1024.0 / 1024.0)
        return "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.listFiles()?.forEach { total += dirSize(it) }
        return total
    }

    private fun countFiles(dir: File): Int {
        if (!dir.exists()) return 0
        if (dir.isFile) return 1
        var count = 0
        dir.listFiles()?.forEach { count += countFiles(it) }
        return count
    }

    private fun countSymlinks(dir: File, maxDepth: Int): Int {
        if (!dir.exists() || maxDepth < 0) return 0
        var count = 0
        if (dir.isSymlink()) count++
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { count += countSymlinks(it, maxDepth - 1) }
        }
        return count
    }

    private fun fileType(file: File): String {
        if (!file.exists()) return "(missing)"
        return when {
            file.isDirectory -> "DIRECTORY"
            file.isSymlink() -> "SYMLINK"
            file.isFile -> "REGULAR_FILE"
            else -> "OTHER"
        }
    }

    private fun readlinkSafe(file: File): String {
        return try {
            android.system.Os.readlink(file.absolutePath)
        } catch (t: Throwable) {
            "(readlink failed: ${t.message})"
        }
    }

    // Kotlin extension — java.io.File doesn't have isSymlink() so we add one
    private fun File.isSymlink(): Boolean {
        return try {
            val canonParent = this.parentFile?.canonicalFile ?: this.canonicalFile.parentFile
            val canonSelf = File(canonParent, this.name)
            !canonSelf.canonicalPath.equals(this.absolutePath)
        } catch (t: Throwable) {
            false
        }
    }
}
