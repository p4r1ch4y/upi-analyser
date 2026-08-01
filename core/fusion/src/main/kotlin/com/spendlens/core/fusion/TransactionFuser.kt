package com.spendlens.core.fusion

import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source

/**
 * Where a message came from: the rail, and the app or sender within it.
 *
 * The origin matters as much as the rail. Two bank SMS about one payment can
 * arrive from two different sender IDs; two notifications from the *same* UPI app
 * are two different payments.
 */
data class SourceRef(val source: Source, val origin: String?)

/**
 * Whether a stored payment can be a second *view* of an incoming one, rather than
 * a different payment that happens to cost the same.
 *
 * This is the rule that keeps repeat payments apart. Fusion exists because one
 * payment is announced twice - a UPI notification and then the bank's SMS - and
 * matching on amount, currency and direction inside a short window is how those
 * two are recognised as one. But that same match is indistinguishable from
 * genuinely paying the same shop the same amount twice within a few minutes,
 * which is completely ordinary: two chais, two auto fares, splitting a bill by
 * sending ₹200 twice.
 *
 * The thing that tells them apart is *who is talking*. A UPI app posts one
 * notification per payment - it does not announce the same payment twice - so a
 * second notification from the same package is a second payment, always. The same
 * goes for a second SMS from the same sender ID. Only a message from a source the
 * stored payment has not already been seen on can be another view of it.
 *
 * An exact RRN match is exempt and handled by the caller: an RRN identifies the
 * payment itself, so two messages carrying one are the same payment no matter who
 * sent them.
 */
fun canFuseAcrossSources(incoming: SourceRef, alreadySeenOn: List<SourceRef>): Boolean =
    alreadySeenOn.none { seen ->
        seen.source == incoming.source && seen.origin.equalsOrigin(incoming.origin)
    }

/**
 * Origins compare case-insensitively, and two unknown origins count as the same.
 *
 * Treating null as "could be anything" would let a second notification with no
 * package name fuse into the first, which is the exact collapse this is here to
 * stop. Unknown is treated as one origin, not as a wildcard.
 */
private fun String?.equalsOrigin(other: String?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> equals(other, ignoreCase = true)
}

/**
 * Cross-source transaction fusion.
 * Merges fields rather than deduplicating - highest-trust source wins per field.
 */
class TransactionFuser {

    data class FusionResult(
        val matchedTransactions: List<RawTxn>,
        val confidence: Float,
        val mergedFields: MergedFields
    )

    data class MergedFields(
        val amountMinor: Long,
        val currency: String,
        val counterpartyNameRaw: String?,
        val counterpartyVpa: String?,
        val rrn: String?,
        val accountTail: String?,
        val occurredAt: Long,
        val sourceMask: Int
    )

    /**
     * Match incoming transaction against recent transactions.
     * Returns null if no match, or FusionResult if matched.
     */
    fun findMatch(
        incoming: RawTxn,
        recentTxns: List<RawTxn>,
        windowSeconds: Long = 90
    ): FusionResult? {
        
        // Check for exact RRN match first
        if (!incoming.rrn.isNullOrBlank()) {
            val rrnMatch = recentTxns.find { it.rrn == incoming.rrn }
            if (rrnMatch != null) {
                return FusionResult(
                    matchedTransactions = listOf(rrnMatch, incoming),
                    confidence = 1.0f,
                    mergedFields = mergeFields(listOf(rrnMatch, incoming))
                )
            }
        }

        // Amount + currency + direction within time window
        val timeWindowStart = incoming.observedAt - (windowSeconds * 1000)
        val timeWindowEnd = incoming.observedAt + (windowSeconds * 1000)

        val amountMatches = recentTxns.filter { txn ->
            txn.amountMinor == incoming.amountMinor &&
            txn.currency == incoming.currency &&
            txn.direction == incoming.direction &&
            txn.observedAt in timeWindowStart..timeWindowEnd
        }

        if (amountMatches.isEmpty()) return null

        // If account tail also matches, higher confidence
        val tailMatch = amountMatches.find { txn ->
            !txn.accountTail.isNullOrBlank() &&
            !incoming.accountTail.isNullOrBlank() &&
            txn.accountTail == incoming.accountTail
        }

        return if (tailMatch != null) {
            FusionResult(
                matchedTransactions = listOf(tailMatch, incoming),
                confidence = 0.9f,
                mergedFields = mergeFields(listOf(tailMatch, incoming))
            )
        } else {
            FusionResult(
                matchedTransactions = listOf(amountMatches.first(), incoming),
                confidence = 0.8f,
                mergedFields = mergeFields(listOf(amountMatches.first(), incoming))
            )
        }
    }

    /**
     * Merge fields from multiple sources using trust hierarchy.
     * Trust order per field:
     * - counterpartyName: NOTIFICATION > STATEMENT > SMS
     * - rrn: SMS > STATEMENT > NOTIFICATION
     * - accountTail: SMS > STATEMENT
     * - amount/currency: STATEMENT > SMS > NOTIFICATION
     */
    private fun mergeFields(txns: List<RawTxn>): MergedFields {
        val sourceMask = txns.fold(0) { mask, txn -> mask or txn.source.toMask() }

        // Counterparty name: NOTIFICATION > STATEMENT > SMS
        val counterpartyName = txns
            .sortedByDescending { sourcePriority(it.source, listOf(Source.NOTIFICATION, Source.STATEMENT, Source.SMS)) }
            .firstNotNullOfOrNull { it.counterpartyNameRaw }

        // RRN: SMS > STATEMENT > NOTIFICATION
        val rrn = txns
            .sortedByDescending { sourcePriority(it.source, listOf(Source.SMS, Source.STATEMENT, Source.NOTIFICATION)) }
            .firstNotNullOfOrNull { it.rrn }

        // Account tail: SMS > STATEMENT
        val accountTail = txns
            .sortedByDescending { sourcePriority(it.source, listOf(Source.SMS, Source.STATEMENT)) }
            .firstNotNullOfOrNull { it.accountTail }

        // Amount/currency: STATEMENT > SMS > NOTIFICATION
        val amountSource = txns
            .sortedByDescending { sourcePriority(it.source, listOf(Source.STATEMENT, Source.SMS, Source.NOTIFICATION)) }
            .first()

        // VPA: any non-null
        val vpa = txns.firstNotNullOfOrNull { it.counterpartyVpa }

        // Occurred at: earliest
        val occurredAt = txns.mapNotNull { it.occurredAt }.minOrNull() ?: txns.first().observedAt

        return MergedFields(
            amountMinor = amountSource.amountMinor,
            currency = amountSource.currency,
            counterpartyNameRaw = counterpartyName,
            counterpartyVpa = vpa,
            rrn = rrn,
            accountTail = accountTail,
            occurredAt = occurredAt,
            sourceMask = sourceMask
        )
    }

    private fun sourcePriority(source: Source, order: List<Source>): Int {
        val index = order.indexOf(source)
        return if (index >= 0) order.size - index else 0
    }
}
