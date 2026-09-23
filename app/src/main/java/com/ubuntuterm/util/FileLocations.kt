package com.ubuntuterm.util

import android.content.Context
import java.io.File

/**
 * Centralised layout of all on-device filesystem locations used by
 * the application.
 *
 * v0.1.7 FIX: PRoot binary now lives in INTERNAL storage (context.filesDir)
 * because scoped storage (getExternalFilesDir) on Android 10+ does NOT
 * support Unix executable permissions. File.setExecutable() silently
 * fails on FUSE/sdcardfs, causing canExecute() to return false.
 *
 * Layout:
 *
 *   /data/data/com.ubuntuterm.debug/files/     ← INTERNAL (executable works)
 *     └── proot/                                ← PRoot binary + loader
 *
 *   /sdcard/Android/data/com.ubuntuterm.debug/files/  ← EXTERNAL (large files)
 *     ├── ubuntu/        ← extracted Ubuntu rootfs (persists across sessions)
 *     ├── sessions/      ← per-session state
 *     └── cache/         ← volatile: rootfs tarball, downloads
 *
 * Per project spec (section 11): the filesystem MUST persist between
 * sessions — so we never wipe `ubuntu/` after the first bootstrap.
 */
object FileLocations {

    lateinit var rootDir: File        private set   // external .../files/
    lateinit var ubuntuRootDir: File  private set   // external .../files/ubuntu/
    lateinit var prootDir: File       private set   // INTERNAL .../files/proot/
    lateinit var sessionsDir: File    private set   // external .../files/sessions/
    lateinit var cacheDir: File       private set   // external .../files/cache/

    fun init(context: Context) {
        // External storage for large files (rootfs, cache, sessions).
        // This is /sdcard/Android/data/<pkg>/files — scoped storage on
        // Android 10+, automatically available without SAF permissions.
        val external = context.getExternalFilesDir(null)
            ?: context.filesDir // fallback
        rootDir        = external
        ubuntuRootDir  = File(external, "ubuntu").apply { mkdirs() }
        sessionsDir    = File(external, "sessions").apply { mkdirs() }
        cacheDir       = File(external, "cache").apply { mkdirs() }

        // v0.1.7: PRoot binary goes in INTERNAL storage where
        // Unix permissions (including executable bit) actually work.
        // On Android 10+, external storage is FUSE/sdcardfs which
        // silently ignores setExecutable().
        //
        // context.filesDir = /data/data/<pkg>/files (or /data/user/0/<pkg>/files)
        // This is a real ext4 filesystem where chmod +x works.
        prootDir       = File(context.filesDir, "proot").apply { mkdirs() }
    }

    /** Where the PRoot static binary lives after first-run extraction. */
    val prootBinary: File get() = File(prootDir, "proot")

    /** Sentinel file written by the bootstrap after Ubuntu is ready. */
    val readyMarker: File get() = File(ubuntuRootDir, ".ubuntuterm_ready")

    /** True if and only if Ubuntu has been successfully bootstrapped. */
    fun isUbuntuReady(): Boolean = readyMarker.exists()

    /**
     * Returns the path to /home/<user> inside the rootfs.
     */
    fun homeDir(user: String = "root"): File =
        File(ubuntuRootDir, "home/$user")
}
