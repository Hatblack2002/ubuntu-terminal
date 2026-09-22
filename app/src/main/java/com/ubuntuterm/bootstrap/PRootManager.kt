package com.ubuntuterm.bootstrap

import android.util.Log
import com.ubuntuterm.util.FileLocations
import com.ubuntuterm.util.ensureExecutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads and caches the PRoot static binary.
 *
 * Per project spec (section 3 & 14):
 *   - PRoot is a TRANSLATOR — it intercepts syscalls from the Ubuntu
 *     binaries and rewrites paths so that "absolute" paths like /usr/bin
 *     are translated to point inside our sandbox. It does NOT replace
 *     Ubuntu.
 *   - PRoot is the standard way to run a real Linux rootfs without root
 *     on Android. It is also what Termux uses internally.
 *
 * We use a statically-linked arm64 PRoot binary so it has no runtime
 * dependencies on the host system.
 */
class PRootManager {

    sealed class Result {
        object AlreadyReady : Result()
        data class Downloaded(val version: String) : Result()
        data class Failed(val reason: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Source URLs for the PRoot static binary.
     *
     * Primary: official proot/proot GitHub releases.
     * Fallback: Termux packages repository (also a static binary).
     */
    private val sources = listOf(
        "https://github.com/proot/proot/releases/download/v5.1.0/proot-v5.1.0-arm64-static" to "v5.1.0",
        // Fallback: Termux's proot package (also statically compiled)
        "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot-static_5.1.0-71_aarch64.deb" to "termux-5.1.0-71"
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun ensureReady(): Result = withContext(Dispatchers.IO) {
        val bin = FileLocations.prootBinary
        if (bin.exists() && bin.canExecute()) {
            return@withContext Result.AlreadyReady
        }
        FileLocations.prootDir.mkdirs()
        bin.parentFile?.mkdirs()

        var lastErr: Throwable? = null
        for ((url, version) in sources) {
            try {
                downloadTo(url, bin, version)
                return@withContext Result.Downloaded(version)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to fetch PRoot from $url: ${t.message}")
                lastErr = t
                if (bin.exists()) bin.delete()
            }
        }
        return@withContext Result.Failed(
            "All PRoot sources failed: ${lastErr?.message}",
            lastErr
        )
    }

    private fun downloadTo(url: String, target: File, version: String) {
        Log.i(TAG, "Downloading PRoot $version from $url")
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IOException("empty body")
            body.byteStream().use { input ->
                FileOutputStream(target).use { out ->
                    input.copyTo(out)
                }
            }
        }
        if (!target.setExecutable(true, true)) {
            throw IOException("Failed to chmod +x ${target.absolutePath}")
        }
        if (!ensureExecutable(target)) {
            throw IOException("PRoot binary is not executable after chmod")
        }
        Log.i(TAG, "PRoot installed at ${target.absolutePath} (${target.length()} bytes)")
    }

    companion object {
        private const val TAG = "PRootManager"
    }
}
