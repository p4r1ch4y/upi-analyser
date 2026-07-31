package com.spendlens.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.core.model.Source
import com.spendlens.core.parser.ParserInput
import com.spendlens.data.TransactionIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Accepts a payment receipt shared from another app.
 *
 * The one rail that catches what the others cannot. A payment made on this phone
 * often produces no notification the listener can see, and the bank SMS - when it
 * comes - never carries the remark typed in the UPI app. Sharing the receipt
 * costs one tap, needs no permission at all, and brings the text across intact.
 *
 * Invisible: it has no layout and finishes immediately, so sharing feels like
 * sending to a service rather than opening an app.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.trim()

        // Most UPI apps share a *screenshot*, not text. Reading one would need
        // OCR, which is a real piece of work and a licensing decision (see
        // fdroid/README.md). Until then the honest thing is to accept the share
        // so the app appears in the sheet at all, and open the entry form with
        // the receipt still on screen behind it — the user reads their own
        // screenshot and types four characters.
        if (shared.isNullOrEmpty()) {
            val hasImage = intent?.type?.startsWith("image/") == true ||
                intent?.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) != null
            if (hasImage) {
                startActivity(
                    Intent(this, com.spendlens.ui.MainActivity::class.java).apply {
                        action = ACTION_ADD_FROM_IMAGE
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
                finish()
                return
            }
            toastAndFinish(getString(R.string.share_nothing))
            return
        }

        val graph = SpendLensApp.graphOf(this)
        // Parsed as a notification: shared receipts read like app text rather
        // than bank prose, and the notification templates are the ones written
        // for that voice.
        val raw = graph.parser.parse(
            ParserInput(
                source = Source.NOTIFICATION,
                packageName = callingPackage ?: intent.getStringExtra(EXTRA_SOURCE_PACKAGE),
                body = shared,
                timestamp = System.currentTimeMillis()
            )
        )

        if (raw == null) {
            toastAndFinish(getString(R.string.share_unreadable))
            return
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching { graph.ingestor.ingest(raw) }.getOrNull()
            withContext(Dispatchers.Main) {
                toastAndFinish(
                    when (result) {
                        is TransactionIngestor.Result.Duplicate ->
                            getString(R.string.share_already_known)
                        else -> getString(R.string.share_added)
                    }
                )
            }
        }
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val EXTRA_SOURCE_PACKAGE = "source_package"

        /** A shared screenshot: open manual entry rather than pretend to read it. */
        const val ACTION_ADD_FROM_IMAGE = "com.spendlens.action.ADD_FROM_IMAGE"
    }
}
