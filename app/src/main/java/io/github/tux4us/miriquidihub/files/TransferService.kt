package io.github.tux4us.miriquidihub.files

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.tux4us.miriquidihub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Vordergrunddienst, solange der [TransferManager] einen Vorgang ausführt. Er zeigt den Fortschritt
 * in einer Benachrichtigung mit Abbrechen-Schaltfläche und beendet sich selbst nach dem Vorgang.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.fm_channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fm_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferManager.cancel()
        }
        startAsForeground(buildNotification(getString(R.string.fm_transfer_running), null))
        if (watcher == null) {
            watcher = scope.launch {
                TransferManager.state.collect { st ->
                    when (st) {
                        is TransferManager.State.Running -> notify(buildNotification(st.title, st.progress))
                        else -> {
                            ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(n: android.app.Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(title: String, p: TransferProgress?): android.app.Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1, intent(this).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, io.github.tux4us.miriquidihub.FileManagerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.cancel), cancelIntent)
        if (p != null) {
            b.setContentText(p.currentName)
            if (p.currentSize > 0) {
                b.setProgress(100, (p.currentDone * 100 / p.currentSize).toInt().coerceIn(0, 100), false)
            } else {
                b.setProgress(0, 0, true)
            }
        } else {
            b.setProgress(0, 0, true)
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "file_transfers"
        private const val NOTIFICATION_ID = 2101
        private const val ACTION_CANCEL = "io.github.tux4us.miriquidihub.files.CANCEL"

        fun intent(context: Context): Intent = Intent(context, TransferService::class.java)
    }
}
