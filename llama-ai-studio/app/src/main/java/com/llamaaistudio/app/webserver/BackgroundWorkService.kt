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
 * Keeps the process in the foreground while long-running work is active — the built-in web
 * server, a Node server, a Gradle build or a package install in Linux. Without it Android
 * (14+) freezes the app as soon as you switch to a browser or another app, and the server
 * stops answering / the build stalls. Shows what's running, with a Stop-all action, and ends
 * itself when nothing is running any more. Started by [LlamaAiStudioApp] whenever
 * [LlamaAiStudioApp.backgroundWork] becomes non-empty.
 */
class BackgroundWorkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as LlamaAiStudioApp
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            app.stopAllBackgroundWork()
            stopSelfCompat()
            return START_NOT_STICKY
        }
        val work = app.backgroundWork.value
        ensureChannel()
        // startForeground must be called promptly after startForegroundService(), even if the
        // work already finished in the meantime — then stop right after.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(work), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (work.isEmpty()) {
            stopSelfCompat()
            return START_NOT_STICKY
        }
        watchJob?.cancel()
        watchJob = scope.launch {
            app.backgroundWork.collect { items ->
                if (items.isEmpty()) {
                    stopSelfCompat()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(items))
                }
            }
        }
        // If Android kills the process, the work is gone too; don't restart an empty service.
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
                NotificationChannel(CHANNEL_ID, "Background work", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a server, build or install keeps running"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(items: List<String>): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BackgroundWorkService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (items.size) {
            0 -> "Finishing…"
            1 -> items.first().substringBefore(" · ")
            else -> "${items.size} tasks running"
        }
        val text = items.joinToString("\n")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_web)
            .setContentTitle(title)
            .setContentText(items.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop all", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "web_server"
        private const val NOTIFICATION_ID = 4210
        private const val ACTION_STOP_ALL = "com.llamaaistudio.app.STOP_ALL_WORK"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundWorkService::class.java))
        }
    }
}
