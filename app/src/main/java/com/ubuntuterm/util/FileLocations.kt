package com.ubuntuterm.util

import android.content.Context
import java.io.File

/**
 * Centralised layout of all on-device filesystem locations used by
 * the application.
 *
 * v0.1.9 FIX: ubuntuRootDir moved to INTERNAL storage (context.filesDir)
 * because external storage (FUSE/sdcardfs) on Android 10+ CANNOT create
 * symlinks (EPERM), hardlinks, or set Unix permissions. This broke the
 * Ubuntu rootfs extraction — /bin was a regular file instead of a symlink
 * to usr/bin, so /bin/bash was unreachable and bash could not start.
 *
 * ALL app data now lives in internal storage:
 *
 *   /data/data/com.ubuntuterm.debug/files/     ← INTERNAL (ext4/f2fs)
 *     ├── proot/          ← PRoot binary + loader (executable works)
 *     ├── ubuntu/         ← Ubuntu rootfs (symlinks/hardlinks work)
 *     └── cache/          ← tarball downloads
 *
 * External storage is NOT used — it's FUSE and breaks everything.
 */
object FileLocations {

    lateinit var rootDir: File        private set   // internal .../files/
    lateinit var ubuntuRootDir: File  private set   // internal .../files/ubuntu/
    lateinit var prootDir: File       private set   // internal .../files/proot/
    lateinit var sessionsDir: File    private set   // internal .../files/sessions/
    lateinit var cacheDir: File       private set   // internal .../files/cache/

    fun init(context: Context) {
        // v0.1.9: EVERYTHING goes in internal storage (context.filesDir).
        // This is /data/data/<pkg>/files — a real ext4/f2fs filesystem
        // where symlinks, hardlinks, chmod, and executable bits all work.
        //
        // External storage (getExternalFilesDir) is FUSE/sdcardfs on
        // Android 10+ and CANNOT create symlinks (EPERM). This broke
        // the Ubuntu rootfs extraction — /bin was a regular file instead
        // of a symlink to usr/bin, so /bin/bash was unreachable.
        rootDir        = context.filesDir
        ubuntuRootDir  = File(context.filesDir, "ubuntu").apply { mkdirs() }
        prootDir       = File(context.filesDir, "proot").apply { mkdirs() }
        sessionsDir    = File(context.filesDir, "sessions").apply { mkdirs() }
        cacheDir       = File(context.filesDir, "cache").apply { mkdirs() }
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
