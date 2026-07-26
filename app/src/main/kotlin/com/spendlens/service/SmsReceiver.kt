package com.spendlens.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.spendlens.SpendLensApp
import com.spendlens.core.model.Source
import com.spendlens.core.parser.ParserInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The second ingestion rail: bank SMS.
 *
 * Notifications are faster and need no dangerous permission, but they only exist
 * while a payment app is installed and chatty. Bank SMS is the rail that survives
 * a phone reset, covers cards and NEFT as well as UPI, and - through
 * [com.spendlens.data.SmsInboxImporter] - reaches backwards through history.
 *
 * Only registered in the `full` flavour, which is the one that declares the SMS
 * permissions. In the `standard` flavour this class is dead code.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull() ?: return

        // Multipart messages arrive as several parts of one logical SMS; join them
        // before parsing or a UPI reference number can be split across the seam.
        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        if (body.isBlank()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress ?: return
        val sentAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val graph = SpendLensApp.graphOf(context)
        val raw = graph.parser.parse(
            ParserInput(
                source = Source.SMS,
                sender = sender,
                body = body,
                timestamp = sentAt
            )
        ) ?: return

        // A BroadcastReceiver is dead the moment onReceive returns, so the work
        // goes to the application-scoped coroutine scope rather than a local one.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                graph.ingestor.ingest(raw)
                val dayTotal = graph.todayTotalMinor()
                graph.nudgeNotifier.notifyPayment(raw.counterpartyNameRaw ?: sender, raw.amountMinor, dayTotal)
                TransactionCaptureService.updateTodayTotal(context, dayTotal)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to ingest SMS", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
