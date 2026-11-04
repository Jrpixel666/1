package com.llamaaistudio.app.webserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.llamaaistudio.app.LlamaAiStudioApp
import com.llamaaistudio.app.MainActivity
import com.llamaaistudio.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while the built-in web server runs. Without it, testing
 * the site in an external browser fails: once this app is in the background, Android (14+)
 * freezes cached processes and the server stops answering. The notification shows the URL
 * and has a Stop action. The service ends itself as soon as the server stops.
 */
class WebServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val server get() = (application as LlamaAiStudioApp).webServer
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            server.stop()
            stopSelfCompat()
            return START_NOT_STICKY
        }
        val state = server.state.value
        if (!state.running) {
            stopSelfCompat()
            return START_NOT_STICKY
        }
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        watchJob?.cancel() // onStartCommand runs again on every (re)start of the server
        watchJob = scope.launch {
            server.state.collect { s ->
                if (!s.running) {
                    stopSelfCompat()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(s))
                }
            }
        }
        // If Android kills the process, the server is gone too; don't restart an empty service.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Web server", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Web Studio's built-in server is running"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(state: LocalWebServer.State): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, WebServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = state.config?.label ?: "site"
        val text = state.lanUrl?.let { "${state.localUrl}  ·  Wi-Fi: $it" } ?: state.localUrl.orEmpty()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_web)
            .setContentTitle("Serving $label")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "web_server"
        private const val NOTIFICATION_ID = 4210
        private const val ACTION_STOP = "com.llamaaistudio.app.webserver.STOP"

        /** Call after the server has started. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WebServerService::class.java))
        }
    }
}
