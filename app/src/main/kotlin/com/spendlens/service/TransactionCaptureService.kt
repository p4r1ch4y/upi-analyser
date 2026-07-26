package com.spendlens.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.core.model.MoneyFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service to keep the app alive for background transaction capture.
 * Shows a low-priority persistent notification with today's spending total.
 */
class TransactionCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground() within a few seconds of the start
        // request, so post immediately with whatever total the caller supplied
        // and refresh from the ledger afterwards.
        val seedTotal = intent?.takeIf { it.hasExtra(EXTRA_TODAY_TOTAL) }
            ?.getLongExtra(EXTRA_TODAY_TOTAL, 0L)

        startInForeground(createServiceNotification(seedTotal ?: 0L))

        if (seedTotal != null) {
            updateNotification(seedTotal)
        } else {
            scope.launch {
                runCatching { SpendLensApp.graphOf(applicationContext).todayTotalMinor() }
                    .onSuccess { updateNotification(it) }
            }
        }

        return START_STICKY
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createServiceNotification(todayTotalMinor: Long): Notification {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, SpendLensApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(
                getString(
                    R.string.service_notification_text,
                    MoneyFormat.money(todayTotalMinor)
                )
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(todayTotalMinor: Long) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, createServiceNotification(todayTotalMinor))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TODAY_TOTAL = "com.spendlens.extra.TODAY_TOTAL"

        fun start(context: Context) {
            context.startForegroundServiceCompat(Intent(context, TransactionCaptureService::class.java))
        }

        /** Push a freshly computed day total into the persistent notification. */
        fun updateTodayTotal(context: Context, todayTotalMinor: Long) {
            val intent = Intent(context, TransactionCaptureService::class.java)
                .putExtra(EXTRA_TODAY_TOTAL, todayTotalMinor)
            context.startForegroundServiceCompat(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransactionCaptureService::class.java))
        }

        // minSdk is 26, so startForegroundService always exists.
        private fun Context.startForegroundServiceCompat(intent: Intent) =
            startForegroundService(intent)
    }
}
