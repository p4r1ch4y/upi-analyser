package com.spendlens.core.model

/**
 * Raw transaction as extracted from a single source.
 * Immutable, never contains "Unknown" strings - uses nulls instead.
 */
data class RawTxn(
    val source: Source,
    val observedAt: Long,
    val occurredAt: Long?,
    val amountMinor: Long,           // Always positive
    val currency: String,            // ISO 4217, never defaulted to INR
    val direction: Direction,
    val counterpartyVpa: String?,
    val counterpartyNameRaw: String?,
    val rrn: String?,                // UPI reference number
    val accountTail: String?,
    val channel: Channel?,
    val instrument: Instrument?,
    val templateId: String?,         // Which parser rule matched
    val bodyHash: String,            // Truncated SHA-256 for dedupe
    /**
     * The message this was read out of, kept verbatim.
     *
     * Stored so the user can answer "why is this row here, and is it right?"
     * without taking the parser's word for it. A tracker that shows a number it
     * cannot justify is asking to be trusted; one that can show the sentence it
     * came from can be checked.
     */
    val sourceBody: String? = null,
    /** Package name for a notification, sender ID for an SMS, file name for CSV. */
    val sourceOrigin: String? = null,
    /**
     * The bank or wallet the message came from, when it names itself.
     *
     * Most bank SMS never says who was paid - "Rs. 10.00 debited from Airtel
     * Payments Bank a/c Txn ID 8159..." has no payee anywhere in it. It does say
     * which institution moved the money, and that is worth keeping: "Airtel
     * Payments Bank ••2793" tells the user something true, where a bare
     * "Bank message" tells them nothing.
     */
    val institution: String? = null,
    /** The message says this payment failed, was declined, or was reversed. */
    val failed: Boolean = false,
    /**
     * Identifies this exact notification across re-posts, or null when there is
     * nothing stable to key on (SMS, CSV, a notification without a `when`).
     *
     * [bodyHash] folds in the post time, so an app re-posting the same
     * notification later gets a fresh hash. This instead folds in the
     * notification's own `when`, which a re-post keeps, so a second sighting is
     * the same notification again rather than a second payment.
     */
    val repostKey: String? = null
) {
    /** Truncated hash for display */
    fun shortHash(): String = bodyHash.take(12)
}

/** Fused transaction from multiple sources */
data class FusedTxn(
    val id: TxnId,
    val occurredAt: Long,
    val amount: Money,
    val direction: Direction,
    val accountId: String?,
    val counterpartyVpa: String?,
    val counterpartyNameRaw: String?,
    /** Label produced by the resolution ladder. Never blank, never "Unknown". */
    val displayName: String,
    val merchantId: String?,
    val categoryId: String?,
    val confidence: Float,           // 0.0-1.0 resolution confidence
    val resolutionRung: Int,         // 1-6, which ladder rung resolved this
    val sourceMask: Int,             // Bitwise OR of contributing sources
    val rrn: String?,
    val channel: Channel?,
    val instrument: Instrument?,
    val flags: Int = 0,              // Bitwise flags
    val linkedTxnId: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val FLAG_SELF_TRANSFER = 1
        const val FLAG_REFUND = 2
        const val FLAG_REVERSAL = 4
        const val FLAG_EXCLUDED = 8
        const val FLAG_SPLIT = 16
        const val FLAG_NEEDS_REVIEW = 32
        const val FLAG_MANUAL_EDIT = 64

        /**
         * The payment was attempted and did not go through - declined, failed,
         * or reversed.
         *
         * Kept in the ledger rather than dropped, and excluded from every total.
         * Dropping it silently is the tidier implementation but the worse
         * product: the user saw their bank say something happened, and an app
         * that shows nothing looks like it missed the payment. Showing it struck
         * through says "seen, and deliberately not counted", which is the only
         * version that survives being checked.
         */
        const val FLAG_FAILED = 128

        /**
         * A second announcement of a payment already in the ledger.
         *
         * Some payment apps re-post a completed payment later, or roll several
         * recent payments into one "N new completed transaction updates"
         * notification in the evening. Each of those payments was already
         * captured when it happened, so counting the re-announcement again would
         * inflate the day's spending with money that moved once.
         *
         * Kept in the ledger rather than dropped - the user saw the notification,
         * and an app that shows nothing looks like it missed something - but
         * excluded from every total, exactly like [FLAG_FAILED]: every query that
         * skips failed rows skips this bit too. Deliberately independent of
         * [FLAG_EXCLUDED], so the user flipping a duplicate back to counting never
         * undoes an exclusion they made themselves.
         */
        const val FLAG_DUPLICATE = 256

        /**
         * The user looked at a row flagged [FLAG_DUPLICATE] and said it counts.
         *
         * Recorded so no automatic pass ever flags that row again: the launch-time
         * repair that flags already-stored digests would otherwise undo the
         * user's decision on every start.
         */
        const val FLAG_COUNT_CONFIRMED = 512
    }

    fun hasFlag(flag: Int): Boolean = (flags and flag) != 0

    /**
     * Best label available if [displayName] were ever empty. Falls back down the
     * same order the resolution ladder uses and ends at the raw VPA - never at
     * the word "Unknown", which is the one thing this product refuses to show.
     */
    fun label(): String = displayName.ifBlank {
        counterpartyNameRaw?.takeIf { it.isNotBlank() }
            ?: counterpartyVpa?.takeIf { it.isNotBlank() }
            ?: "Manual entry"
    }
}

/** Merchant resolution */
data class Merchant(
    val id: String,
    val canonicalName: String,
    val categoryId: String?,
    val origin: String  // BUNDLED, USER, IMPORTED
)

/** Category */
data class Category(
    val id: String,
    val name: String,
    val icon: String?,
    val parentId: String?,
    val isSystem: Boolean
)
