package com.spendlens.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.core.model.MoneyFormat

/**
 * The real-time nudge: what you just spent, and what that makes the day.
 *
 * This is the product's whole feedback loop, so it fires within a second of the
 * payment landing and carries the running total rather than just the payment.
 */
class NudgeNotifier(private val context: Context) {

    fun notifyPayment(displayName: String, amountMinor: Long, dayTotalMinor: Long) {
        if (!canPostNotifications()) return

        val amount = MoneyFormat.money(amountMinor)
        val dayTotal = MoneyFormat.money(dayTotalMinor)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, SpendLensApp.CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$amount → $displayName")
            .setContentText(context.getString(R.string.nudge_day_total, dayTotal))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Same id every time: the nudge replaces itself rather than stacking up
        // one entry per payment.
        notificationManager()?.notify(NUDGE_ID, notification)
    }

    private fun notificationManager(): NotificationManager? =
        ContextCompat.getSystemService(context, NotificationManager::class.java)

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val NUDGE_ID = 2001
    }
}
