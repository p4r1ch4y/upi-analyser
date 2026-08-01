package com.spendlens.core.parser

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source

/**
 * Reads a payment out of the *name* of a shared receipt image.
 *
 * Google Pay names the file it hands to the share sheet after the payment it
 * depicts:
 *
 * ```
 * 1738737495 - 165.00 To Krishnendu Diyan on Google Pay.png
 * 1759307622 - 150.00 From Kaustab Sarkar on Google Pay.png
 * ```
 *
 * That is a unix timestamp, an amount, a direction and a counterparty - every
 * field the ledger needs, written by the payment app itself and needing no OCR
 * to read. It is a better source than most bank SMS, which routinely name no
 * payee at all.
 *
 * The obvious objection is that a file name is user-editable and therefore not
 * evidence. True, and the pattern is drawn tightly enough that it costs nothing:
 * it demands the leading epoch, the two-decimal amount, an exact `To`/`From`, and
 * a trailing `on <app>`. A renamed screenshot does not match by accident, and
 * anything that does not match falls through to the entry form exactly as before.
 *
 * PhonePe, by contrast, names its receipts `TransactionReceipt4551195680020140631`
 * and says nothing. Those still need the picture read, which is the open OCR
 * question in `fdroid/README.md`.
 */
object ReceiptFileName {

    /**
     * `<epoch seconds> - <amount> <To|From> <name> on <app>.<ext>`
     *
     * The name is *greedy* so it binds to the last ` on `, not the first. A payee
     * called "Ration on Wheels" is an ordinary Indian shop name, and a non-greedy
     * name silently files it as "Ration" paid on "Wheels on Google Pay".
     */
    private val GOOGLE_PAY = Regex(
        """^(?<epoch>\d{9,13})\s*-\s*(?<amount>[\d,]+\.\d{2})\s+(?<direction>To|From)\s+(?<name>.+)\s+on\s+(?<app>[\w\s]+?)\s*\.\w{2,5}$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A payment read from [fileName], or null when the name says nothing.
     *
     * [currency] is passed in rather than assumed: the name carries digits and no
     * symbol, and this app's rule is that a currency is never invented. The caller
     * knows what the user is working in; this function does not.
     */
    fun parse(
        fileName: String?,
        currency: String,
        observedAt: Long,
        sourcePackage: String? = null
    ): RawTxn? {
        val match = GOOGLE_PAY.matchEntire(fileName?.trim().orEmpty()) ?: return null

        val amountMinor = TransactionTemplate.parseAmountMinor(
            match.group("amount") ?: return null
        ) ?: return null
        if (amountMinor <= 0L) return null

        val direction = when (match.group("direction")?.lowercase()) {
            "to" -> Direction.DEBIT
            "from" -> Direction.CREDIT
            else -> return null
        }

        val name = match.group("name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= TransactionTemplate.MAX_NAME_LENGTH }
            ?: return null

        // Seconds or millis, depending on the app. Ten digits is a second-epoch
        // well past 2001; thirteen is millis. Anything outside a sane window is
        // refused rather than filed decades away.
        val raw = match.group("epoch")?.toLongOrNull() ?: return null
        val occurredAt = if (raw < SECONDS_CUTOFF) raw * 1000 else raw
        if (occurredAt !in EARLIEST_PLAUSIBLE..(observedAt + FUTURE_TOLERANCE)) return null

        return RawTxn(
            source = Source.NOTIFICATION,
            observedAt = observedAt,
            occurredAt = occurredAt,
            amountMinor = amountMinor,
            currency = currency,
            direction = direction,
            counterpartyVpa = null,
            counterpartyNameRaw = name,
            rrn = null,
            accountTail = null,
            // Every app that names receipts this way is a UPI app, and the name
            // says which one - so the rail is known rather than guessed.
            channel = Channel.UPI,
            instrument = null,
            templateId = TEMPLATE_ID,
            // Hashed on the name alone, without a post time: sharing the same
            // receipt twice is the same payment and must collapse, which is the
            // opposite of the notification rail's problem.
            bodyHash = TransactionTemplate.dedupeHash(match.value, 0L),
            sourceBody = match.value,
            sourceOrigin = sourcePackage,
            institution = match.group("app")?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /** Kotlin's `groups` by name needs a cast on JVM below 1.7; this keeps it local. */
    private fun MatchResult.group(name: String): String? = groups[name]?.value

    const val TEMPLATE_ID = "receipt-filename-gpay"

    /** Below this, an epoch is in seconds; above it, in milliseconds. */
    private const val SECONDS_CUTOFF = 100_000_000_000L

    /** 1 Jan 2010. UPI did not exist before 2016; this is generous on purpose. */
    private const val EARLIEST_PLAUSIBLE = 1_262_304_000_000L

    /** A receipt may be shared from a phone whose clock is a little ahead. */
    private const val FUTURE_TOLERANCE = 24L * 60 * 60 * 1000
}
