package com.spendlens.service

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.spendlens.SpendLensApp
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import com.spendlens.core.parser.ParserInput
import com.spendlens.data.TransactionIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Notification listener for UPI app notifications.
 * This is the primary ingestion rail - faster than SMS and doesn't need SMS permission.
 *
 * On connect it also sweeps whatever is already sitting in the tray, so payments
 * made before the app was installed (or while the listener was unbound) are not
 * lost. Note that this reaches the *live* tray only: Android's system Notification
 * History log is gated behind a signature-level permission and is not readable by
 * third-party apps, so anything the user has already swiped away is gone. The SMS
 * inbox importer is what recovers genuinely old history.
 */
class UpiNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph by lazy { SpendLensApp.graphOf(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val raw = toRawTxn(sbn) ?: return
        scope.launch { ingestAndNudge(raw) }
    }

    /**
     * Reads every notification currently in the tray.
     *
     * There is no age cut-off. The dedupe hash folds in each notification's post
     * time, so a rebind replays the same hashes and collapses to nothing, while a
     * genuinely unseen three-hour-old payment still lands.
     */
    private fun sweepTray(reason: String) {
        val posted = runCatching { activeNotifications }
            .onFailure { Log.e(TAG, "Could not read the notification tray", it) }
            .getOrNull() ?: return

        val raws = posted.mapNotNull { toRawTxn(it) }
        if (raws.isEmpty()) {
            Log.d(TAG, "Tray sweep ($reason): nothing to ingest out of ${posted.size} notifications")
            return
        }

        scope.launch {
            val summary = graph.ingestor.ingestAll(raws)
            Log.d(TAG, "Tray sweep ($reason): $summary")
            if (summary.inserted > 0 || summary.merged > 0) {
                refreshTodayTotal()
            }
        }
    }

    /** Parses one notification, or returns null if it is not a payment. */
    private fun toRawTxn(sbn: StatusBarNotification): RawTxn? {
        if (sbn.packageName !in targetPackages) return null

        // Group summaries duplicate their children, and ongoing notifications are
        // progress indicators rather than completed payments.
        val flags = sbn.notification.flags
        if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return null

        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val bigText = extras.getCharSequence("android.bigText")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()

        // bigText carries the full sentence; text is often truncated with an
        // ellipsis, which would cut the VPA out of a BHIM-style message.
        val body = bigText ?: text ?: return null
        if (body.isBlank()) return null

        return graph.parser.parse(
            ParserInput(
                source = Source.NOTIFICATION,
                packageName = sbn.packageName,
                title = title,
                body = body,
                extras = mapOf(
                    "subText" to (extras.getCharSequence("android.subText")?.toString() ?: ""),
                    "tickerText" to (sbn.notification.tickerText?.toString() ?: "")
                ),
                timestamp = sbn.postTime
            )
        ).also {
            if (it == null) Log.w(TAG, "No template matched a notification from ${sbn.packageName}")
        }
    }

    private suspend fun ingestAndNudge(raw: RawTxn) {
        runCatching { graph.ingestor.ingest(raw) }
            .onFailure { Log.e(TAG, "Failed to ingest transaction", it) }
            .onSuccess { result ->
                when (result) {
                    is TransactionIngestor.Result.Duplicate -> Unit
                    is TransactionIngestor.Result.Merged ->
                        nudge(result.displayName, result.amountMinor, result.id)
                    is TransactionIngestor.Result.Inserted ->
                        nudge(result.txn.label(), result.txn.amount.amountMinor, result.txn.id.value)
                }
            }
    }

    private suspend fun nudge(displayName: String, amountMinor: Long, txnId: String) {
        val dayTotal = graph.todayTotalMinor()
        graph.nudgeNotifier.notifyPayment(
            displayName = displayName,
            amountMinor = amountMinor,
            dayTotalMinor = dayTotal,
            // Carried so the notification can annotate the row it is about,
            // which is the whole point of asking while the context is fresh.
            txnId = txnId,
            quickTags = runCatching { graph.annotations.mostUsedTags() }.getOrDefault(emptyList())
        )
        TransactionCaptureService.updateTodayTotal(this, dayTotal)
    }

    private suspend fun refreshTodayTotal() {
        TransactionCaptureService.updateTodayTotal(this, graph.todayTotalMinor())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // We don't care about removals
    }

    override fun onListenerConnected() {
        Log.d(TAG, "Notification listener connected")
        connected = this
        sweepTray(reason = "listener connected")
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected - requesting rebind")
        connected = null
        requestRebind(ComponentName(this, UpiNotificationListener::class.java))
    }

    override fun onDestroy() {
        connected = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "UpiNotificationListener"

        /**
         * The bound instance, so the UI can ask for a rescan. Android gives no
         * other handle on a NotificationListenerService, and only the bound
         * instance may call [getActiveNotifications].
         */
        @Volatile
        private var connected: UpiNotificationListener? = null

        val isConnected: Boolean get() = connected != null

        /** @return false when the listener is not bound, so the UI can say why. */
        fun rescanTray(): Boolean {
            val listener = connected ?: return false
            listener.sweepTray(reason = "user requested")
            return true
        }

        /**
         * Nudges Android into rebinding a listener that has been killed. Cheap and
         * safe to call whenever the UI notices it is not connected.
         */
        fun requestRebind(context: Context) {
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(context, UpiNotificationListener::class.java)
                )
            }
        }

        /**
         * Apps whose notifications are worth reading. Kept in sync with the
         * `<queries>` block in the manifest.
         */
        val targetPackages: Set<String> = setOf(
            "com.google.android.apps.nbu.paisa.user",  // Google Pay
            "com.phonepe.app",                          // PhonePe
            "net.one97.paytm",                          // Paytm
            "in.org.npci.upiapp",                       // BHIM
            "in.amazon.mShop.android.shopping",         // Amazon Pay
            "com.dreamplug.androidapp",                 // CRED
            "money.super.payments",                     // super.money
            "in.slice.android",                         // slice
            "com.jupiter.consumer",                     // Jupiter
            "com.epifi.paisa",                          // Fi
            "com.naviapp",                              // Navi
            "com.whatsapp",                             // WhatsApp Pay
            "com.mobikwik_new",                         // MobiKwik
            "com.freecharge.android",                   // Freecharge
            "com.csam.icici.bank.imobile",              // iMobile Pay
            "com.snapwork.hdfc",                        // HDFC PayZapp
            "com.sbi.upi",                              // BHIM SBI Pay
            "com.axis.mobile",                          // Axis Mobile
            "com.msf.kbank.mobile"                      // Kotak
        )
    }
}
