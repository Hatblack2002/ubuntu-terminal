package com.ubuntuterm.util

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val TAG = "IOUtils"

/**
 * Copies `input` to `output`, calling `onProgress` periodically with
 * the number of bytes copied so far.
 *
 * The caller is responsible for closing both streams.
 */
@Throws(IOException::class)
fun copyStream(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int = 64 * 1024,
    onProgress: ((Long) -> Unit)? = null
): Long {
    val buf = ByteArray(bufferSize)
    var total = 0L
    var lastReport = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        total += n
        if (onProgress != null && total - lastReport >= bufferSize * 16) {
            onProgress(total)
            lastReport = total
        }
    }
    output.flush()
    onProgress?.invoke(total)
    return total
}

fun ensureExecutable(file: java.io.File): Boolean {
    if (!file.exists()) return false
    // v0.1.7: Try File.setExecutable() first (works on internal storage).
    // If that fails, try android.system.Os.chmod() which calls the
    // chmod() syscall directly. This is needed because File.setExecutable()
    // silently fails on some filesystems (e.g., FUSE on older Android).
    try {
        if (file.setExecutable(true, true)) {
            return true
        }
    } catch (e: Exception) {
        Log.w(TAG, "setExecutable failed: ${e.message}")
    }
    // Fallback: use Os.chmod() directly
    try {
        // 0o700 = rwx------ (owner can read, write, execute)
        android.system.Os.chmod(file.absolutePath, 448)  // 0o700 = 448 decimal
        return file.canExecute()
    } catch (e: Exception) {
        Log.e(TAG, "Os.chmod failed: ${e.message}")
    }
    return false
}

fun ensureDir(dir: java.io.File): Boolean {
    if (dir.exists()) return dir.isDirectory
    return dir.mkdirs()
}

/** Sets the executable bit on a file and all parents up to rootDir. */
fun setExecutableRecursive(file: java.io.File) {
    var cur: java.io.File? = file
    while (cur != null && cur != FileLocations.rootDir.parentFile) {
        cur.setExecutable(true, false)
        cur = cur.parentFile
    }
}

fun logD(tag: String, msg: String) {
    Log.d(tag, msg)
}

fun logE(tag: String, msg: String, t: Throwable? = null) {
    Log.e(tag, msg, t)
}
