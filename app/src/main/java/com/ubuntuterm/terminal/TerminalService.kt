package com.ubuntuterm.terminal

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ubuntuterm.R
import com.ubuntuterm.UbuntuTerminalApp

/**
 * Foreground service that keeps at least one Ubuntu session alive
 * while the app is in the background.
 *
 * Without this, Android would aggressively kill the PRoot child
 * process when the user switches to another app, losing any
 * in-progress commands (apt install, git clone, gcc, etc.).
 *
 * Per project spec (section 11): "Los archivos creados deben persistir
 * entre sesiones." This service ensures the in-memory state survives
 * brief background excursions.
 */
class TerminalService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TerminalService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(sessionCount = 1))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "TerminalService destroyed")
        super.onDestroy()
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, Class.forName("com.ubuntuterm.MainActivity"))
        val pi = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, UbuntuTerminalApp.CHANNEL_SESSIONS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(getString(R.string.notif_session_text, sessionCount))
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "TerminalService"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalService::class.java))
        }
    }
}
