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
import com.ubuntuterm.MainActivity
import com.ubuntuterm.diagnostic.DiagnosticLog

/**
 * Foreground service that keeps at least one Ubuntu session alive
 * while the app is in the background.
 *
 * v0.1.6: startForeground() is now wrapped in try/catch. If the
 * notification cannot be created (e.g., POST_NOTIFICATIONS not
 * granted on Android 13+, or channel missing), we call stopSelf()
 * instead of letting the system kill the process with
 * ForegroundServiceDidNotStartInTimeException.
 */
class TerminalService : Service() {

    companion object {
        private const val TAG = "TerminalService"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, TerminalService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException on Android 12+
                Log.w(TAG, "startForegroundService threw: ${t.javaClass.simpleName}: ${t.message}")
                DiagnosticLog.service("TerminalService",
                    "startForegroundService threw: ${t.javaClass.simpleName}: ${t.message}", t)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, TerminalService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "stopService threw: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TerminalService created")
        DiagnosticLog.service("TerminalService", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "TerminalService onStartCommand")
        DiagnosticLog.service("TerminalService", "onStartCommand: calling startForeground")

        // v0.1.6: wrap startForeground in try/catch.
        // If startForeground() fails, the system gives us ~5 seconds
        // to call it before killing the process. If we can't call it,
        // we call stopSelf() to gracefully exit instead of being killed.
        try {
            val notification = buildNotification(sessionCount = 1)
            startForeground(NOTIF_ID, notification)
            DiagnosticLog.service("TerminalService", "startForeground succeeded")
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed: ${t.javaClass.simpleName}: ${t.message}", t)
            DiagnosticLog.error("TerminalService",
                "startForeground failed: ${t.javaClass.simpleName}: ${t.message}", t)
            // If we can't start foreground, stop the service gracefully
            // instead of letting the system kill the process.
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "TerminalService destroyed")
        DiagnosticLog.service("TerminalService", "onDestroy")
        super.onDestroy()
    }

    private fun buildNotification(sessionCount: Int): Notification {
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
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
}
