package com.spendlens.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.spendlens.core.model.Source
import com.spendlens.core.parser.BuiltInTemplates
import com.spendlens.core.parser.ParserInput
import com.spendlens.core.parser.TemplateParser

/**
 * Notification listener for UPI app notifications.
 * This is the primary ingestion rail - faster than SMS and doesn't need SMS permission.
 */
class UpiNotificationListener : NotificationListenerService() {

    private val parser = TemplateParser().apply {
        addTemplates(BuiltInTemplates.all())
    }

    private val targetPackages = setOf(
        "com.google.android.apps.nbu.paisa.user",  // Google Pay
        "com.phonepe.app",                          // PhonePe
        "net.one97.paytm",                          // Paytm
        "in.org.npci.upiapp",                       // BHIM
        "in.amazon.mShop.android.shopping",         // Amazon Pay
        "com.dreamplug.androidapp"                  // CRED
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Filter out non-UPI apps
        if (sbn.packageName !in targetPackages) return

        // Ignore group summaries (causes duplicates)
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return

        // Ignore ongoing events (progress notifications)
        if (sbn.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return

        // Ignore notifications older than 5 minutes (prevents replay on rebind)
        val ageMs = System.currentTimeMillis() - sbn.postTime
        if (ageMs > 5 * 60 * 1000) return

        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val subText = extras.getCharSequence("android.subText")?.toString()

        val bodyText = bigText ?: text ?: ""
        if (bodyText.isBlank()) return

        Log.d(TAG, "Captured notification from ${sbn.packageName}: $title | $bodyText")

        val input = ParserInput(
            source = Source.NOTIFICATION,
            packageName = sbn.packageName,
            title = title,
            body = bodyText,
            extras = mapOf(
                "subText" to (subText ?: ""),
                "tickerText" to (notification.tickerText?.toString() ?: "")
            ),
            timestamp = sbn.postTime
        )

        val rawTxn = parser.parse(input)
        if (rawTxn != null) {
            Log.d(TAG, "Parsed transaction: ${rawTxn.amountMinor} ${rawTxn.currency} to ${rawTxn.counterpartyNameRaw}")
            // TODO: Save to database and show nudge notification
            // For now, just log
        } else {
            Log.w(TAG, "Failed to parse notification from ${sbn.packageName}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // We don't care about removals
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected - requesting rebind")
        requestRebind(componentName)
    }

    companion object {
        private const val TAG = "UpiNotificationListener"
    }
}
