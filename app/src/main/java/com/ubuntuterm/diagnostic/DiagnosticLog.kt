package com.ubuntuterm.diagnostic

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * DiagnosticLog — in-memory ring buffer of significant events.
 *
 * PURPOSE (per the validation strategy):
 *   Every significant event in the app's lifecycle — bootstrap phases,
 *   session creation, native spawn results, foreground-service start
 *   outcomes, errors, exceptions — is recorded here. The contents of
 *   this log can then be formatted by [DiagnosticReport] and presented
 *   to the user via the in-app Settings → Diagnostics screen, WITHOUT
 *   requiring `adb logcat`.
 *
 * CAPACITY:
 *   We keep the last 500 events. Older events are dropped. Each event
 *   is timestamped with millisecond precision and tagged with a category
 *   (BOOTSTRAP, SESSION, NATIVE, SERVICE, ERROR, ...).
 *
 * THREAD SAFETY:
 *   All public functions are thread-safe. Use from any dispatcher.
 *
 * NOT A SUBSTITUTE FOR LOGCAT:
 *   This is an app-level summary, not a full system log. For low-level
 *   native crashes (SIGSEGV/SIGABRT) the system logcat is still needed
 *   because the process dies before this log can flush. But for every
 *   Kotlin-level event, this log captures it.
 */
object DiagnosticLog {

    private const val TAG = "DiagnosticLog"
    private const val CAPACITY = 500

    enum class Category(val label: String) {
        BOOTSTRAP("BOOTSTRAP"),
        PROOT("PRoot"),
        SESSION("SESSION"),
        NATIVE("NATIVE"),
        SERVICE("SERVICE"),
        UI("UI"),
        ERROR("ERROR"),
        APP("APP")
    }

    data class Event(
        val timestamp: Long,
        val category: Category,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        val formattedTimestamp: String
            get() = SimpleDateFormat("HH:mm:ss.SSS").format(Date(timestamp))
    }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val counter = AtomicLong(0)

    /** Last error event (most recent), or null if no error has been recorded. */
    private val _lastError = MutableStateFlow<Event?>(null)
    val lastError: StateFlow<Event?> = _lastError.asStateFlow()

    fun log(category: Category, tag: String, message: String, throwable: Throwable? = null) {
        val event = Event(
            timestamp = System.currentTimeMillis(),
            category = category,
            tag = tag,
            message = message,
            throwable = throwable
        )
        // Append to the list, trimming to CAPACITY
        val current = _events.value
        val newList = if (current.size >= CAPACITY) {
            current.drop(current.size - CAPACITY + 1) + event
        } else {
            current + event
        }
        _events.value = newList

        // Mirror to logcat so adb is still useful when available
        val logMsg = "[${event.formattedTimestamp}] [${category.label}] $tag: $message"
        if (throwable != null) {
            Log.e(TAG, logMsg, throwable)
        } else when (category) {
            Category.ERROR -> Log.e(TAG, logMsg)
            else -> Log.i(TAG, logMsg)
        }

        // Track last error
        if (category == Category.ERROR) {
            _lastError.value = event
        }
    }

    /** Convenience helpers. */
    fun bootstrap(tag: String, msg: String) = log(Category.BOOTSTRAP, tag, msg)
    fun bootstrap(tag: String, msg: String, t: Throwable) = log(Category.BOOTSTRAP, tag, msg, t)
    fun proot(tag: String, msg: String) = log(Category.PROOT, tag, msg)
    fun proot(tag: String, msg: String, t: Throwable) = log(Category.PROOT, tag, msg, t)
    fun session(tag: String, msg: String) = log(Category.SESSION, tag, msg)
    fun session(tag: String, msg: String, t: Throwable) = log(Category.SESSION, tag, msg, t)
    fun native(tag: String, msg: String) = log(Category.NATIVE, tag, msg)
    fun native(tag: String, msg: String, t: Throwable) = log(Category.NATIVE, tag, msg, t)
    fun service(tag: String, msg: String) = log(Category.SERVICE, tag, msg)
    fun service(tag: String, msg: String, t: Throwable) = log(Category.SERVICE, tag, msg, t)
    fun ui(tag: String, msg: String) = log(Category.UI, tag, msg)
    fun ui(tag: String, msg: String, t: Throwable) = log(Category.UI, tag, msg, t)
    fun app(tag: String, msg: String) = log(Category.APP, tag, msg)
    fun app(tag: String, msg: String, t: Throwable) = log(Category.APP, tag, msg, t)
    fun error(tag: String, msg: String, t: Throwable? = null) = log(Category.ERROR, tag, msg, t)

    /** Clears the log. Useful when the user wants a fresh diagnostic snapshot. */
    fun clear() {
        _events.value = emptyList()
        _lastError.value = null
    }
}
