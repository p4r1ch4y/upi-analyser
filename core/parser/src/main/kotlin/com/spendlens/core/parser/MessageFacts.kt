package com.spendlens.core.parser

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/**
 * Facts that sit in a message alongside the payment itself: which institution
 * sent it, and when it says the payment happened.
 *
 * Separate from the templates because these are the same wherever they appear.
 * Every template would otherwise have to capture them, and most of them cannot -
 * the bank name is usually in a trailer ("-PNB", "Download PNB ONE-PNB") that has
 * nothing to do with the sentence stating the amount.
 */
object MessageFacts {

    /**
     * Institutions, longest name first so "State Bank of India" wins over "Bank".
     *
     * Short forms map to the full name because that is what a person recognises,
     * and because the same bank writes "PNB" in one message and "Punjab National
     * Bank" in the next - a ledger that lists both as separate payees is worse
     * than one that lists neither.
     */
    private val INSTITUTIONS: List<Pair<Regex, String>> = listOf(
        "Airtel Payments Bank" to "Airtel Payments Bank",
        "Paytm Payments Bank" to "Paytm Payments Bank",
        "Jio Payments Bank" to "Jio Payments Bank",
        "State Bank of India" to "State Bank of India",
        "Punjab National Bank" to "Punjab National Bank",
        "Kotak Mahindra Bank" to "Kotak Mahindra Bank",
        "AU Small Finance Bank" to "AU Small Finance Bank",
        "IDFC FIRST Bank" to "IDFC FIRST Bank",
        "Bank of Baroda" to "Bank of Baroda",
        "Federal Bank" to "Federal Bank",
        "Canara Bank" to "Canara Bank",
        "Union Bank" to "Union Bank of India",
        "Indian Bank" to "Indian Bank",
        "Central Bank" to "Central Bank of India",
        "Bandhan Bank" to "Bandhan Bank",
        "IndusInd Bank" to "IndusInd Bank",
        "HDFC Bank" to "HDFC Bank",
        "ICICI Bank" to "ICICI Bank",
        "Axis Bank" to "Axis Bank",
        "Yes Bank" to "Yes Bank",
        "IDBI Bank" to "IDBI Bank",
        "UCO Bank" to "UCO Bank",
        "RBL Bank" to "RBL Bank",
        "HDFC" to "HDFC Bank",
        "ICICI" to "ICICI Bank",
        "PNB" to "Punjab National Bank",
        "SBI" to "State Bank of India"
    ).map { (needle, name) -> Regex("""\b${Regex.escape(needle)}\b""", RegexOption.IGNORE_CASE) to name }

    /** The institution the message names, or null when it names none. */
    fun institution(text: String): String? =
        INSTITUTIONS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second

    /**
     * `a/c XX2793`, `Ac XXXXXXXX00022793`, `account XXXXXXXX7080`. Last four
     * digits only, so the varying mask length does not matter.
     */
    private val ACCOUNT = Regex(
        """\b(?:a/c|ac|acct|account|card)\s*(?:no\.?)?\s*[:\s]*[Xx*]+\s*(\d{3,})""",
        RegexOption.IGNORE_CASE
    )

    fun accountTail(text: String): String? =
        ACCOUNT.find(text)?.groupValues?.getOrNull(1)?.takeLast(4)

    private val DATE_TIME_SHAPES: List<Pair<Regex, String>> = listOf(
        // Dt 26-12-23 21:58   |   on 12-02-24 19:07
        Regex("""\b(?:Dt|on|dated)\s+(\d{2}-\d{2}-\d{2})\s+(\d{2}:\d{2}(?::\d{2})?)""", RegexOption.IGNORE_CASE)
            to "dd-MM-yy HH:mm:ss",
        // 20-01-2024 19:29:11 (often comma-delimited, no preposition)
        Regex("""\b(\d{2}-\d{2}-\d{4})[,\s]+(\d{2}:\d{2}(?::\d{2})?)""")
            to "dd-MM-yyyy HH:mm:ss",
        Regex("""\b(?:on|dated)\s+(\d{2}/\d{2}/\d{4})\s+(?:at\s+)?(\d{2}:\d{2}(?::\d{2})?)""", RegexOption.IGNORE_CASE)
            to "dd/MM/yyyy HH:mm:ss"
    )

    /**
     * When the message says the payment happened, if it says so credibly.
     *
     * Only accepted within [MAX_DRIFT_DAYS] of [receivedAt]. A bank SMS arrives
     * seconds after the payment, so a stated time that disagrees by more than a
     * couple of days is a misparse, not a late message - and filing a payment in
     * the wrong month is far worse than filing it an hour off. The case this
     * genuinely fixes is the one either side of midnight, where the SMS lands on
     * the day after the payment and the day's total is wrong at both ends.
     */
    fun statedTime(text: String, receivedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        for ((pattern, format) in DATE_TIME_SHAPES) {
            val match = pattern.find(text) ?: continue
            val date = match.groupValues[1]
            val time = match.groupValues[2].let { if (it.length == 5) "$it:00" else it }
            val parsed = runCatching {
                LocalDateTime.parse(
                    "$date $time",
                    DateTimeFormatter.ofPattern(format, Locale.ENGLISH)
                ).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull() ?: continue

            if (abs(parsed - receivedAt) <= MAX_DRIFT_MILLIS) return parsed
        }
        return null
    }

    private const val MAX_DRIFT_DAYS = 3L
    private const val MAX_DRIFT_MILLIS = MAX_DRIFT_DAYS * 24 * 60 * 60 * 1000
}
