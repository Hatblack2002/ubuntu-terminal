package com.ubuntuterm.util

import android.content.Context
import java.io.File

/**
 * Centralised layout of all on-device filesystem locations used by
 * the application.
 *
 * Everything lives under the app's private external storage so it
 * survives app upgrades but can be cleared by uninstalling.
 *
 *   /sdcard/Android/data/com.ubuntuterm/files/
 *     ├── ubuntu/        ← extracted Ubuntu rootfs (persists across sessions)
 *     ├── proot/         ← PRoot static binary
 *     ├── sessions/      ← per-session state (history, scrollback dumps)
 *     └── cache/         ← volatile: rootfs tarball, downloads
 *
 * Per project spec (section 11): the filesystem MUST persist between
 * sessions — so we never wipe `ubuntu/` after the first bootstrap.
 */
object FileLocations {

    lateinit var rootDir: File        private set   // .../files/
    lateinit var ubuntuRootDir: File  private set   // .../files/ubuntu/
    lateinit var prootDir: File       private set   // .../files/proot/
    lateinit var sessionsDir: File    private set   // .../files/sessions/
    lateinit var cacheDir: File       private set   // .../files/cache/

    fun init(context: Context) {
        // getExternalFilesDir returns /sdcard/Android/data/<pkg>/files
        // which on Android 9+ is automatically scoped and not subject to
        // SAF restrictions.
        val base = context.getExternalFilesDir(null)
            ?: context.filesDir // fallback to internal storage
        rootDir        = base
        ubuntuRootDir  = File(base, "ubuntu").apply { mkdirs() }
        prootDir       = File(base, "proot").apply { mkdirs() }
        sessionsDir    = File(base, "sessions").apply { mkdirs() }
        cacheDir       = File(base, "cache").apply { mkdirs() }
    }

    /** Where the PRoot static binary lives after first-run extraction. */
    val prootBinary: File get() = File(prootDir, "proot")

    /** Sentinel file written by the bootstrap after Ubuntu is ready. */
    val readyMarker: File get() = File(ubuntuRootDir, ".ubuntuterm_ready")

    /** True if and only if Ubuntu has been successfully bootstrapped. */
    fun isUbuntuReady(): Boolean = readyMarker.exists()

    /**
     * Returns the path to /home/<user> inside the rootfs.
     * On Ubuntu the default non-root user created by the bootstrap is
     * "ubuntu", so this resolves to .../files/ubuntu/home/ubuntu.
     */
    fun homeDir(user: String = "ubuntu"): File =
        File(ubuntuRootDir, "home/$user")
}
