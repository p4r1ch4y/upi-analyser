package com.spendlens.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import com.spendlens.core.parser.ParserInput
import com.spendlens.core.parser.TransactionParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads real history out of the SMS inbox.
 *
 * This is the only rail that reaches genuinely into the past. Android exposes no
 * notification history to third-party apps, so a fresh install can otherwise only
 * ever see payments made from that moment on; the bank SMS inbox, by contrast,
 * routinely holds months or years of transaction messages.
 *
 * Everything happens on-device against the local content provider. Nothing is
 * uploaded - the app holds no INTERNET permission to upload it with.
 */
class SmsInboxImporter(
    private val context: Context,
    private val parser: TransactionParser,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * True when this build declares the SMS permissions *and* the user has granted
     * them. The `standard` flavour declares neither, so this is permanently false
     * there and the UI hides the affordance rather than offering a dead button.
     */
    fun isAvailable(): Boolean = declaresReadSms() && hasReadSmsPermission()

    fun hasReadSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether this build's manifest asks for READ_SMS at all. */
    fun declaresReadSms(): Boolean = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(Manifest.permission.READ_SMS) == true
    }.getOrDefault(false)

    /**
     * Parses every inbox message newer than [since] that any template recognises.
     *
     * Returns the parsed transactions rather than writing them, so the caller owns
     * dedupe and fusion - re-running an import is therefore harmless.
     */
    suspend fun readInbox(since: Long = 0L, limit: Int = DEFAULT_LIMIT): List<RawTxn> =
        withContext(io) {
            if (!isAvailable()) return@withContext emptyList()

            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val raws = mutableListOf<RawTxn>()
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    projection,
                    "${Telephony.Sms.DATE} >= ?",
                    arrayOf(since.toString()),
                    "${Telephony.Sms.DATE} DESC LIMIT $limit"
                )?.use { cursor ->
                    val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    while (cursor.moveToNext()) {
                        val body = cursor.getString(bodyColumn) ?: continue
                        val sender = cursor.getString(addressColumn) ?: continue
                        val sentAt = cursor.getLong(dateColumn)

                        parser.parse(
                            ParserInput(
                                source = Source.SMS,
                                sender = sender,
                                body = body,
                                timestamp = sentAt
                            )
                        )?.let(raws::add)
                    }
                }
            }.onFailure { Log.e(TAG, "Could not read the SMS inbox", it) }

            raws
        }

    private companion object {
        const val TAG = "SmsInboxImporter"

        /**
         * A busy inbox holds tens of thousands of messages. Reading the most recent
         * few thousand covers years of banking without making the import feel hung.
         */
        const val DEFAULT_LIMIT = 5_000
    }
}
