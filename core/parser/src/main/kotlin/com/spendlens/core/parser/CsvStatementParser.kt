package com.spendlens.core.parser

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parses a bank or UPI-app statement exported as CSV. Pure Kotlin, no Android -
 * the caller is responsible for getting the bytes off disk.
 *
 * Indian bank exports agree on almost nothing, so columns are located by header
 * *synonym* rather than by position, and both conventions for representing
 * direction are accepted:
 *
 *  - separate `Withdrawal` / `Deposit` columns (most bank exports), or
 *  - one signed `Amount` column (most app exports).
 */
object CsvStatementParser {

    data class Outcome(
        val transactions: List<RawTxn>,
        /** 1-based line numbers that carried data but could not be read. */
        val skippedLines: List<Int>
    )

    fun parse(
        lines: List<String>,
        defaultCurrency: String = "INR",
        observedAt: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Outcome {
        val nonEmpty = lines.map { it.trim() }.withIndex().filter { it.value.isNotEmpty() }
        val headerEntry = nonEmpty.firstOrNull() ?: return Outcome(emptyList(), emptyList())

        val header = splitCsvLine(headerEntry.value).map { it.trim().lowercase(Locale.ROOT) }
        val columns = ColumnMap.from(header)
            ?: return Outcome(emptyList(), listOf(headerEntry.index + 1))

        val transactions = mutableListOf<RawTxn>()
        val skipped = mutableListOf<Int>()

        for ((index, line) in nonEmpty.drop(1)) {
            val cells = splitCsvLine(line)
            val txn = runCatching { columns.toRawTxn(cells, defaultCurrency, observedAt, zone) }.getOrNull()
            if (txn != null) transactions += txn else skipped += index + 1
        }

        return Outcome(transactions, skipped)
    }

    /** Where each field lives in this particular export. */
    internal data class ColumnMap(
        val date: Int,
        val description: Int?,
        val amount: Int?,
        val debit: Int?,
        val credit: Int?,
        val reference: Int?
    ) {
        fun toRawTxn(
            cells: List<String>,
            defaultCurrency: String,
            observedAt: Long,
            zone: ZoneId
        ): RawTxn? {
            val occurredAt = cells.getOrNull(date)?.let { parseDate(it, zone) } ?: return null

            val (amountMinor, direction) = when {
                debit != null || credit != null -> {
                    val debited = debit?.let { cells.getOrNull(it) }?.let(::parseMinor)
                    val credited = credit?.let { cells.getOrNull(it) }?.let(::parseMinor)
                    when {
                        debited != null && debited > 0 -> debited to Direction.DEBIT
                        credited != null && credited > 0 -> credited to Direction.CREDIT
                        else -> return null
                    }
                }
                amount != null -> {
                    val raw = cells.getOrNull(amount)?.trim() ?: return null
                    // A leading minus, or a trailing DR marker, is the only signal
                    // of direction in single-column exports.
                    val negative = raw.startsWith("-") || raw.endsWith("DR", ignoreCase = true)
                    val value = parseMinor(raw) ?: return null
                    if (value == 0L) return null
                    value to if (negative) Direction.DEBIT else Direction.CREDIT
                }
                else -> return null
            }

            val descriptionText = description?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val referenceText = reference?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            return RawTxn(
                source = Source.STATEMENT,
                observedAt = observedAt,
                occurredAt = occurredAt,
                amountMinor = amountMinor,
                currency = defaultCurrency,
                direction = direction,
                counterpartyVpa = descriptionText?.let(::findVpa),
                counterpartyNameRaw = descriptionText,
                rrn = referenceText,
                accountTail = null,
                channel = Channel.UNKNOWN,
                instrument = null,
                templateId = "csv.statement.v1",
                // The row's own content is its identity, so re-importing the same
                // file is a no-op rather than a doubled ledger.
                bodyHash = hash("$occurredAt|$amountMinor|$direction|$descriptionText|$referenceText")
            )
        }

        companion object {
            private val DATE_HEADERS = listOf("txn date", "transaction date", "value date", "posted on", "date")
            private val DESCRIPTION_HEADERS =
                listOf("description", "narration", "particulars", "details", "remarks", "merchant", "name")
            private val AMOUNT_HEADERS = listOf("transaction amount", "amount (inr)", "amount", "amt")
            private val DEBIT_HEADERS =
                listOf("withdrawal amt", "withdrawal", "debit amount", "debit", "paid out")
            private val CREDIT_HEADERS =
                listOf("deposit amt", "deposit", "credit amount", "credit", "paid in")
            private val REFERENCE_HEADERS =
                listOf("chq/ref no", "reference no", "reference", "ref no", "transaction id", "txn id", "utr", "rrn")

            fun from(header: List<String>): ColumnMap? {
                // Exact header match first; only then a contains-match, so that a
                // column literally called "amount" is not shadowed by
                // "withdrawal amount" appearing earlier in the row.
                fun find(candidates: List<String>): Int? =
                    candidates.firstNotNullOfOrNull { candidate ->
                        header.indexOfFirst { it == candidate }.takeIf { it >= 0 }
                    } ?: candidates.firstNotNullOfOrNull { candidate ->
                        header.indexOfFirst { it.contains(candidate) }.takeIf { it >= 0 }
                    }

                val date = find(DATE_HEADERS) ?: return null
                val debit = find(DEBIT_HEADERS)
                val credit = find(CREDIT_HEADERS)
                val amount = find(AMOUNT_HEADERS)
                if (debit == null && credit == null && amount == null) return null

                return ColumnMap(
                    date = date,
                    description = find(DESCRIPTION_HEADERS),
                    amount = amount,
                    debit = debit,
                    credit = credit,
                    reference = find(REFERENCE_HEADERS)
                )
            }
        }
    }

    private val DATE_TIME_FORMATS = listOf(
        "yyyy-MM-dd HH:mm:ss", "dd/MM/yyyy HH:mm:ss", "dd-MM-yyyy HH:mm"
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    private val DATE_FORMATS = listOf(
        "dd/MM/yyyy", "dd-MM-yyyy", "yyyy-MM-dd", "dd/MM/yy", "dd-MM-yy",
        "dd MMM yyyy", "dd-MMM-yyyy", "dd-MMM-yy", "MM/dd/yyyy"
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }

    internal fun parseDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = raw.trim().trim('"')
        if (text.isEmpty()) return null

        for (format in DATE_TIME_FORMATS) {
            try {
                return LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try the next shape
            }
        }
        for (format in DATE_FORMATS) {
            try {
                return LocalDate.parse(text, format).atStartOfDay(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                // try the next shape
            }
        }
        // Some exports just dump epoch millis.
        return text.toLongOrNull()?.takeIf { it > 100_000_000_000L }
    }

    /**
     * `Rs.` deliberately has no trailing `\b`: with one, `Rs\.?\b` backtracks off
     * the full stop (a `.` before a space is not a word boundary), leaves ". 250"
     * behind, and BigDecimal reads that as 0.25.
     */
    private val CURRENCY_NOISE = Regex("""(?i)\bINR\b|\bRs\.?|\bDR\b|\bCR\b""")

    /** Exact decimal to minor units; never via Double. */
    internal fun parseMinor(raw: String): Long? {
        val cleaned = raw.trim().trim('"')
            .replace(CURRENCY_NOISE, "")
            .replace("₹", "")
            .replace(",", "")
            .replace(" ", "")
            .removePrefix("+")
            .removePrefix("-")
        if (cleaned.isEmpty()) return null
        return try {
            BigDecimal(cleaned).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private val VPA = Regex("""[\w.\-]{2,}@[a-zA-Z]{2,}""")

    /**
     * Pulls the VPA out of a bank narration.
     *
     * Narrations are hyphen-delimited (`UPI-SWIGGY-swiggy@ybl`) and so are plenty
     * of real VPAs (`9733230455-3@ybl`), so a plain regex swallows the whole
     * string. The two are told apart by case: banks shout their narration tags in
     * capitals, while VPAs are handled case-insensitively and written lowercase.
     * Leading hyphen-segments that contain an uppercase letter and no lowercase
     * one are therefore narration, not part of the address.
     */
    internal fun findVpa(text: String): String? {
        val candidate = VPA.find(text)?.value ?: return null
        val segments = candidate.split('-')
        val firstRealSegment = segments.indexOfFirst { segment ->
            !(segment.any { it.isUpperCase() } && segment.none { it.isLowerCase() })
        }
        val kept = if (firstRealSegment <= 0) segments else segments.drop(firstRealSegment)
        return kept.joinToString("-").lowercase(Locale.ROOT).takeIf { it.contains('@') }
    }

    private fun hash(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }

    /**
     * Minimal RFC-4180 split: commas separate, double quotes group, and a doubled
     * quote inside a quoted field is a literal quote. Enough for bank exports,
     * which quote narration fields precisely because they contain commas.
     */
    internal fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        cells += current.toString()
        return cells
    }
}
