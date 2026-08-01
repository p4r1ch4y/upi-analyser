package com.spendlens.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.core.model.MoneyFormat
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import com.spendlens.core.parser.ParserInput
import com.spendlens.core.parser.ReceiptFileName
import com.spendlens.data.SharedReceipt
import com.spendlens.data.SharedReceiptReader
import com.spendlens.data.TransactionIngestor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Accepts a payment receipt shared from another app.
 *
 * The one rail that catches what the others cannot. A payment made on this phone
 * often produces no notification the listener can see, and the bank SMS - when it
 * comes - never carries the remark typed in the UPI app. Sharing the receipt
 * costs one tap, needs no permission at all, and brings it across intact.
 *
 * Three outcomes, in order of how much the app can do unaided:
 *
 * 1. **Text that parses.** Filed straight into the ledger, and the activity never
 *    draws anything - sharing feels like sending to a service, not opening an app.
 * 2. **An image, with or without unparseable text.** Handed to the entry form
 *    *with the receipt attached*, so the user reads their own screenshot inside
 *    the form rather than from memory, and with the date taken from the
 *    screenshot rather than from now.
 * 3. **Nothing usable.** Says so and gets out of the way.
 *
 * SpendLens does not read images. That is a deliberate open question rather than
 * an oversight - see `fdroid/README.md` - and until it is answered the honest
 * thing is to put the receipt in front of the person who can read it.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) {
            toastAndFinish(getString(R.string.share_nothing))
            return
        }

        val receipt = SharedReceiptReader.read(this, intent)
        if (receipt.isEmpty && !receipt.imageUnreadable) {
            toastAndFinish(getString(R.string.share_nothing))
            return
        }

        val parsed = receipt.firstParse()

        // A UPI app that shares readable text *and* a picture of the same payment
        // has already told the app everything. Opening a form over it would be a
        // step backwards from the one-tap capture this rail exists to provide, so
        // the staged image is thrown away unread.
        if (parsed != null) {
            SharedReceiptReader.discard(this, receipt.imageFile)
            ingest(parsed)
            return
        }

        // An image arrived and could not be opened - a revoked grant, usually.
        // The user still shared a payment, so they get the form rather than a
        // flat "nothing to add" for something they plainly did add.
        if (!receipt.hasImage) {
            if (!receipt.imageUnreadable) {
                toastAndFinish(getString(R.string.share_unreadable))
                return
            }
            // Said before the hand-off, not instead of it: the toast explains the
            // missing preview, and `finish` still has to come after startActivity.
            Toast.makeText(this, R.string.receipt_unreadable_image, Toast.LENGTH_SHORT).show()
        }

        handOffToEntryForm(receipt)
    }

    /**
     * Tries every text the share carried, and then all of them joined.
     *
     * Parsed as a notification: shared receipts read like app text rather than
     * bank prose, and the notification templates are the ones written for that
     * voice. The joined attempt is last because an app that puts "Paid to Anita"
     * in the subject and "₹450" in the body has said the whole thing between
     * them, and neither half parses alone.
     */
    private fun SharedReceipt.firstParse(): RawTxn? {
        val parser = SpendLensApp.graphOf(this@ShareReceiverActivity).parser
        val origin = callingPackage ?: sourcePackage
        val now = System.currentTimeMillis()

        val attempts = if (texts.size > 1) texts + combined else texts
        val fromText = attempts.firstNotNullOfOrNull { body ->
            parser.parse(
                ParserInput(
                    source = Source.NOTIFICATION,
                    packageName = origin,
                    body = body,
                    timestamp = now
                )
            )
        }
        if (fromText != null) return fromText

        // Google Pay names the file after the payment it depicts, which is a
        // complete transaction - amount, direction, counterparty and the exact
        // time - written by the payment app and needing no OCR to read. Tried
        // after the text because text, when an app sends any, carries more.
        return ReceiptFileName.parse(
            fileName = imageName,
            currency = MoneyFormat.displayCurrency,
            observedAt = now,
            sourcePackage = origin
        )
    }

    private fun ingest(raw: RawTxn) {
        val graph = SpendLensApp.graphOf(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val result = runCatching { graph.ingestor.ingest(raw) }.getOrNull()
            withContext(Dispatchers.Main) {
                toastAndFinish(
                    when (result) {
                        is TransactionIngestor.Result.Duplicate ->
                            getString(R.string.share_already_known)
                        else -> addedMessage(raw)
                    }
                )
            }
        }
    }

    /**
     * "Added to SpendLens", or "Added · 5 Feb 2025" for anything not from today.
     *
     * Receipts get shared long after the fact - the whole reason this rail is
     * worth having is that it reaches payments the listener never saw. A bare
     * "Added" then sends the user to today's stream to look for a payment filed
     * eighteen months back, and they conclude it was dropped. Naming the date says
     * where to look and doubles as a check that the date was read correctly.
     */
    private fun addedMessage(raw: RawTxn): String {
        val occurred = raw.occurredAt ?: return getString(R.string.share_added)
        val date = Instant.ofEpochMilli(occurred).atZone(ZoneId.systemDefault()).toLocalDate()
        if (date == LocalDate.now(ZoneId.systemDefault())) return getString(R.string.share_added)
        return getString(R.string.share_added_on, date.format(SHARE_DATE_FORMAT))
    }

    /**
     * Opens manual entry with the receipt attached.
     *
     * The staged copy travels as a plain path rather than as a content URI: this
     * activity is `noHistory` and finishes at once, and a URI grant made to it
     * does not reliably outlive it. The file is inside this app's own cache, so
     * the receiving activity needs no permission to read it, and it is deleted
     * the moment the form is done with it.
     */
    private fun handOffToEntryForm(receipt: SharedReceipt) {
        startActivity(
            Intent(this, com.spendlens.ui.MainActivity::class.java).apply {
                action = ACTION_ADD_FROM_RECEIPT
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_RECEIPT_PATH, receipt.imageFile?.absolutePath)
                receipt.imageTakenAt?.let { putExtra(EXTRA_RECEIPT_TAKEN_AT, it) }
                // Text that did not parse is still worth carrying: a remark or a
                // payee name the user can accept rather than retype.
                putExtra(EXTRA_RECEIPT_TEXT, receipt.combined.takeIf { it.isNotBlank() })
            }
        )
        finish()
    }

    private fun toastAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        /** A shared receipt the app could not read: open entry with it attached. */
        const val ACTION_ADD_FROM_RECEIPT = "com.spendlens.action.ADD_FROM_RECEIPT"

        const val EXTRA_RECEIPT_PATH = "receipt_path"
        const val EXTRA_RECEIPT_TAKEN_AT = "receipt_taken_at"
        const val EXTRA_RECEIPT_TEXT = "receipt_text"

        private val SHARE_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}
