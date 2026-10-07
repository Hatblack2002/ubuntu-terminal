package com.ubuntuterm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ubuntuterm.util.FileLocations

/**
 * Application entry point.
 *
 * Initialises notification channels and file system layout.
 * No Ubuntu work happens here — that is deferred until the user opens
 * a terminal session.
 */
class UbuntuTerminalApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Make sure our data directories exist before any session starts.
        FileLocations.init(this)

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_SESSIONS,
                getString(R.string.notif_channel_session),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Ubuntu session alive in the background."
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_SESSIONS = "ubuntu_sessions"

        @Volatile lateinit var instance: UbuntuTerminalApp
            private set
    }
}
