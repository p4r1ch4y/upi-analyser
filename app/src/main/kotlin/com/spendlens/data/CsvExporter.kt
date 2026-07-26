package com.spendlens.data

import android.content.Context
import android.net.Uri
import com.spendlens.core.database.Transactions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes the whole ledger out as CSV, to a file the user picked.
 *
 * This is the only way data leaves SpendLens. There is no `INTERNET` permission
 * and no backup format yet, so without an export the ledger is trapped on one
 * device and one install — and the app is already telling people that
 * reinstalling erases everything. An export they own is the honest answer to
 * that until real backup exists.
 *
 * Through the Storage Access Framework, so no storage permission is needed and
 * the app only ever touches the file handed to it.
 *
 * The header uses names [com.spendlens.core.parser.CsvStatementParser] already
 * recognises, so an export can be read back in. It is not a full backup — tags,
 * splits and source messages do not survive the round trip — but the money and
 * the merchants do.
 */
class CsvExporter(
    private val context: Context,
    private val repository: TransactionRepository,
    private val annotations: SplitAndTagRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    data class Outcome(val rowCount: Int)

    /**
     * @param includeSourceMessages whether to write the original notification or
     *   SMS text. Off by default: those lines carry account numbers, payee names
     *   and phone numbers, and an export is a file that gets mailed to
     *   accountants and dropped in cloud folders. The user opts in knowing that.
     */
    suspend fun export(
        uri: Uri,
        since: Long = 0L,
        includeSourceMessages: Boolean = false
    ): Outcome = withContext(io) {
        val rows = repository.transactionsSinceOnce(since)
        val tagLinks = annotations.tagLinksOnce(since)

        var written = 0
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.bufferedWriter().use { out ->
                out.write(HEADER.joinToString(","))
                out.newLine()

                for (row in rows.sortedBy { it.occurred_at }) {
                    val split = annotations.splitFor(row.id)
                    val sources = if (includeSourceMessages) repository.sourceMessages(row.id) else emptyList()

                    val cells = listOf(
                        DATE.format(Instant.ofEpochMilli(row.occurred_at).atZone(zone)),
                        TIME.format(Instant.ofEpochMilli(row.occurred_at).atZone(zone)),
                        row.display_name,
                        // Signed, matching the convention the importer reads back.
                        signedAmount(row),
                        row.currency,
                        row.direction,
                        row.channel.orEmpty(),
                        row.counterparty_vpa.orEmpty(),
                        row.rrn.orEmpty(),
                        tagLinks[row.id].orEmpty().joinToString("; ") { it.name },
                        split?.let { major(it.myShareMinor) }.orEmpty(),
                        split?.let { major(it.totalMinor) }.orEmpty(),
                        row.note.orEmpty(),
                        sources.joinToString(" | ") { it.body }
                    )
                    out.write(cells.joinToString(",") { escape(it) })
                    out.newLine()
                    written++
                }
            }
        }
        Outcome(written)
    }

    private companion object {
        val HEADER = listOf(
            "Date", "Time", "Description", "Amount", "Currency", "Direction",
            "Channel", "VPA", "Reference", "Tags",
            "Your share", "Total paid", "Note", "Source message"
        )

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

        /** Exact decimal from minor units — never via Double. */
        fun major(amountMinor: Long): String {
            val whole = amountMinor / 100
            val fraction = (amountMinor % 100).toString().padStart(2, '0')
            return "$whole.$fraction"
        }

        fun signedAmount(row: Transactions): String =
            (if (row.direction == "DEBIT") "-" else "") + major(row.amount_minor)

        /**
         * RFC-4180: quote anything containing a comma, quote or newline, and
         * double any embedded quotes. Bank narrations and source messages contain
         * all three, and an unquoted one silently shifts every later column.
         */
        fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
    }
}
