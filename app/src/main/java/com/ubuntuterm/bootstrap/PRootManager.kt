package com.ubuntuterm.bootstrap

import android.content.Context
import android.util.Log
import com.ubuntuterm.diagnostic.DiagnosticLog
import com.ubuntuterm.util.FileLocations
import com.ubuntuterm.util.ensureExecutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Provides the PRoot binary to the application.
 *
 * === Per separation principle (NO Termux) ===
 *
 * Termux is NOT used as:
 *   - an app
 *   - a library
 *   - a download source
 *   - a fallback
 *
 * PRoot is shipped **bundled inside the APK** as a compressed asset at
 * `app/src/main/assets/proot/`. At first launch the asset is copied to
 * the app's private external storage and made executable. There is
 * **NO runtime download** of PRoot.
 *
 * === Source of the bundled binary ===
 *
 * The PRoot binary in `assets/proot/proot-arm64` is the official
 * upstream PRoot (https://github.com/proot-me/proot, GPL-2.0) compiled
 * for Android arm64 using the build scripts published by
 * `green-green-avk/build-proot-android` (MIT,
 * https://github.com/green-green-avk/build-proot-android).
 *
 * The build scripts compile PRoot with the Android NDK and statically
 * link libtalloc, producing a binary that depends only on bionic
 * `libc.so` and `libdl.so` — both present on every Android device.
 *
 * Licenses of the bundled binary are included alongside the binary in
 * `assets/proot/LICENSE-proot` (GPL-2.0) and
 * `assets/proot/LICENSE-build-proot-android` (MIT).
 *
 * === Why this is the correct approach ===
 *
 * 1. No external network dependency at runtime.
 * 2. No dependency on Termux (neither the app nor its package repo).
 * 3. The binary is reproducibly built from public upstream sources.
 * 4. Licenses are respected and shipped with the binary.
 * 5. If the user wants to rebuild the binary from source, the build
 *    scripts at green-green-avk/build-proot-android are public.
 */
class PRootManager(private val context: Context) {

    sealed class Result {
        object AlreadyReady : Result()
        data class Extracted(val version: String) : Result()
        data class Failed(val reason: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Extracts the PRoot binary from APK assets into the app's private
     * external storage, making it executable.
     *
     * Idempotent: if the binary already exists and is executable,
     * returns AlreadyReady without re-extracting.
     */
    suspend fun ensureReady(): Result = withContext(Dispatchers.IO) {
        val bin = FileLocations.prootBinary
        DiagnosticLog.proot("PRootManager",
            "ensureReady: prootBinary path=${bin.absolutePath} exists=${bin.exists()} canExecute=${if (bin.exists()) bin.canExecute() else "(n/a)"} size=${if (bin.exists()) bin.length() else 0}")
        if (bin.exists() && bin.canExecute() && bin.length() > 0) {
            DiagnosticLog.proot("PRootManager", "PRoot already extracted and executable, short-circuit")
            return@withContext Result.AlreadyReady
        }

        try {
            FileLocations.prootDir.mkdirs()
            bin.parentFile?.mkdirs()
            DiagnosticLog.proot("PRootManager", "Extracting PRoot binary from APK assets to ${bin.absolutePath}")

            // Extract proot binary itself.
            copyAsset("proot/proot-arm64", bin)
            if (!ensureExecutable(bin)) {
                throw IOException("Failed to chmod +x ${bin.absolutePath}")
            }

            // Extract the loader that PRoot uses to bootstrap the child
            // process. PRoot expects the loader at a fixed relative path:
            //   <proot_binary_dir>/../libexec/proot/loader
            val libexecDir = File(FileLocations.prootDir, "libexec/proot").apply { mkdirs() }
            val loader = File(libexecDir, "loader")
            copyAsset("proot/loader-arm64", loader)
            if (!ensureExecutable(loader)) {
                throw IOException("Failed to chmod +x loader")
            }

            // The 32-bit loader is only needed if we ever run 32-bit
            // binaries inside Ubuntu. For an arm64-only setup, copy it
            // anyway for completeness.
            val loader32 = File(libexecDir, "loader32")
            copyAsset("proot/loader32-arm", loader32)
            ensureExecutable(loader32)

            Log.i(TAG, "PRoot extracted to ${bin.absolutePath} (${bin.length()} bytes)")
            Log.i(TAG, "Loader extracted to ${loader.absolutePath} (${loader.length()} bytes)")
            DiagnosticLog.proot("PRootManager",
                "PRoot extracted: path=${bin.absolutePath} size=${bin.length()} canExecute=${bin.canExecute()}")
            DiagnosticLog.proot("PRootManager",
                "Loader extracted: path=${loader.absolutePath} size=${loader.length()} canExecute=${loader.canExecute()}")

            Result.Extracted("v5.1.0-android-aarch64")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to extract PRoot from assets", t)
            DiagnosticLog.error("PRootManager",
                "Failed to extract PRoot: ${t.javaClass.simpleName}: ${t.message}", t)
            // Clean up partial state
            if (bin.exists()) bin.delete()
            Result.Failed(
                "Could not extract bundled PRoot binary from APK assets: ${t.message}",
                t
            )
        }
    }

    /**
     * Copies a single asset to a destination file.
     */
    private fun copyAsset(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { out ->
                input.copyTo(out)
            }
        }
    }

    companion object {
        private const val TAG = "PRootManager"
    }
}
