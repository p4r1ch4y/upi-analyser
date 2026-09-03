package com.spendlens.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.spendlens.SpendLensApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Tags or notes a payment straight from its notification.
 *
 * The context of a payment has a half-life of about a minute. "₹10, Airtel
 * Payments Bank" is perfectly recoverable while you are still standing next to
 * the auto you just paid for, and completely unrecoverable a week later when you
 * are looking at a list of forty identical ₹10 rows trying to work out which
 * were fares and which were chai.
 *
 * The app already knew this - the nudge fires within a second of the payment -
 * but the nudge was only a tap target. Opening the app, finding the row and
 * typing into it is four actions at the exact moment the user is busy paying for
 * something, which is why in a real 30-day ledger 160 payments carried one note
 * between them.
 *
 * So the asking happens where the telling already happens. A note can be typed
 * into the shade without leaving whatever you are doing, and the tags you use
 * most are one tap each.
 */
class PaymentActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val txnId = intent.getStringExtra(EXTRA_TXN_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val tag = intent.getStringExtra(EXTRA_TAG)
        val note = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_NOTE)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (tag == null && note == null) return

        // A BroadcastReceiver is dead the moment onReceive returns, so the write
        // goes to an application-scoped coroutine rather than a local one.
        val pending = goAsync()
        val graph = SpendLensApp.graphOf(context)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                note?.let { graph.repository.setNote(txnId, it) }
                tag?.let { name ->
                    graph.annotations.ensureTag(name)?.let { ref ->
                        graph.annotations.tag(listOf(txnId), ref.id)
                    }
                }
                // The nudge has served its purpose the moment it is answered.
                // Leaving it up invites a second tag on the same payment.
                if (notificationId >= 0) {
                    ContextCompat.getSystemService(context, NotificationManager::class.java)
                        ?.cancel(notificationId)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Could not annotate $txnId", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PaymentAction"

        const val ACTION_ANNOTATE = "com.spendlens.action.ANNOTATE_PAYMENT"
        const val EXTRA_TXN_ID = "txn_id"
        const val EXTRA_TAG = "tag_name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        /** The key the typed note arrives under, for [RemoteInput]. */
        const val KEY_NOTE = "note"
    }
}
