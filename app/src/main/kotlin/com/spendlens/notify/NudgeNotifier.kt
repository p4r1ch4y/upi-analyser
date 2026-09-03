package com.spendlens.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.core.model.MoneyFormat
import com.spendlens.service.PaymentActionReceiver

/**
 * The real-time nudge: what you just spent, what that makes the day, and the one
 * moment you still know what the payment was for.
 *
 * This is the product's whole feedback loop, so it fires within a second of the
 * payment landing and carries the running total rather than just the payment.
 *
 * It also *asks*. Most Indian bank SMS names no payee — a real 30-day ledger here
 * held 149 payments labelled only "Airtel Payments Bank" — so the thing that makes
 * a row meaningful later is a tag or a note, and the only person who can supply
 * one is standing next to the shop right now. Expecting them to open the app,
 * find the row and type into it is four actions at the exact moment they are
 * busy paying for something, and the result is measurable: 160 payments in that
 * ledger carried one note between them.
 *
 * So the asking happens where the telling already happens. A note is typed into
 * the shade; the tags used most are one tap each.
 */
class NudgeNotifier(private val context: Context) {

    fun notifyPayment(
        displayName: String,
        amountMinor: Long,
        dayTotalMinor: Long,
        /** Null when the caller could not identify the stored row, which disables the actions. */
        txnId: String? = null,
        /** The user's most-used tags, offered as one-tap buttons. */
        quickTags: List<String> = emptyList()
    ) {
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

        val builder = NotificationCompat.Builder(context, SpendLensApp.CHANNEL_NUDGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$amount → $displayName")
            .setContentText(context.getString(R.string.nudge_day_total, dayTotal))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (txnId != null) {
            builder.addAction(noteAction(txnId))
            // Two at most. A shade full of buttons is a worse prompt than one
            // obvious one, and Android collapses the rest out of sight anyway.
            quickTags.take(MAX_QUICK_TAGS).forEachIndexed { index, tag ->
                builder.addAction(tagAction(txnId, tag, index))
            }
        }

        // Same id every time: the nudge replaces itself rather than stacking up
        // one entry per payment.
        notificationManager()?.notify(NUDGE_ID, builder.build())
    }

    /**
     * Type a note without leaving what you are doing.
     *
     * `RemoteInput` is the whole point of putting this here: the answer costs a
     * pull-down and a few words, not a context switch into another app.
     */
    private fun noteAction(txnId: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(PaymentActionReceiver.KEY_NOTE)
            .setLabel(context.getString(R.string.nudge_note_hint))
            .build()

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            context.getString(R.string.nudge_add_note),
            pendingFor(txnId, tag = null, requestCode = 0)
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun tagAction(txnId: String, tag: String, index: Int): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            tag,
            pendingFor(txnId, tag = tag, requestCode = index + 1)
        ).build()

    /**
     * `FLAG_MUTABLE` because a direct-reply action must be able to have the typed
     * text written into it. The intent is explicit, aimed at a receiver inside
     * this app, so nothing outside can redirect it.
     */
    private fun pendingFor(txnId: String, tag: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, PaymentActionReceiver::class.java).apply {
            action = PaymentActionReceiver.ACTION_ANNOTATE
            putExtra(PaymentActionReceiver.EXTRA_TXN_ID, txnId)
            putExtra(PaymentActionReceiver.EXTRA_NOTIFICATION_ID, NUDGE_ID)
            tag?.let { putExtra(PaymentActionReceiver.EXTRA_TAG, it) }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // Distinct request codes, or every action would overwrite the last one's
        // extras and the tag buttons would all apply the same tag.
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
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
        const val MAX_QUICK_TAGS = 2
    }
}
