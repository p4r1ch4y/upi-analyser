package com.spendlens.data

import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.FusedTxn
import com.spendlens.core.model.Money
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.Source
import com.spendlens.core.model.TxnId
import com.spendlens.core.resolution.MerchantResolver
import com.spendlens.core.resolution.VpaRule

/**
 * The one path from "a rail produced a RawTxn" to "the ledger reflects it".
 *
 * Order matters: dedupe by body hash, then fuse against what is already stored,
 * then resolve a name, then write. Fusion runs before resolution because a
 * transaction that turns out to be a second view of an existing payment must not
 * create a second row - it merges into the first.
 */
class TransactionIngestor(
    private val repository: TransactionRepository,
    private val resolver: MerchantResolver = MerchantResolver(),
    private val fusionWindowMillis: Long = DEFAULT_FUSION_WINDOW_MILLIS,
    private val now: () -> Long = System::currentTimeMillis
) {

    sealed interface Result {
        /** The exact same message body was already processed. */
        data object Duplicate : Result

        /** Folded into an existing row observed on another rail. */
        data class Merged(val id: String, val displayName: String, val amountMinor: Long) : Result

        data class Inserted(val txn: FusedTxn) : Result
    }

    /** Outcome of a bulk pass - a tray sweep, an SMS backfill, a CSV import. */
    data class BatchSummary(
        val inserted: Int = 0,
        val merged: Int = 0,
        val duplicates: Int = 0,
        val failed: Int = 0
    ) {
        val considered: Int get() = inserted + merged + duplicates + failed

        operator fun plus(result: Result): BatchSummary = when (result) {
            is Result.Inserted -> copy(inserted = inserted + 1)
            is Result.Merged -> copy(merged = merged + 1)
            Result.Duplicate -> copy(duplicates = duplicates + 1)
        }

        fun withFailure(): BatchSummary = copy(failed = failed + 1)
    }

    /**
     * @param rules pre-fetched user rules. A bulk import passes them in once
     *   rather than re-reading them for every message, which on an encrypted
     *   database is the difference between a few seconds and a few minutes.
     */
    suspend fun ingest(raw: RawTxn, rules: List<VpaRule>? = null): Result {
        val timestamp = now()

        if (repository.markSeen(raw.bodyHash, timestamp)) return Result.Duplicate

        val resolution = resolver.resolve(raw, userRules = rules ?: repository.userRules())

        repository.findFusionTarget(raw, fusionWindowMillis)?.let { existing ->
            // Keep whichever label the ladder is more sure of. A bank SMS that
            // fuses with a Google Pay notification should not replace "Swiggy"
            // with the merchant's raw VPA.
            val keepExisting = existing.confidence >= resolution.confidence
            val displayName = if (keepExisting) existing.display_name else resolution.displayName
            repository.merge(
                existing = existing,
                raw = raw,
                displayName = displayName,
                confidence = maxOf(existing.confidence.toFloat(), resolution.confidence),
                now = timestamp
            )
            return Result.Merged(existing.id, displayName, existing.amount_minor)
        }

        val flags = if (resolution.needsReview()) FusedTxn.FLAG_NEEDS_REVIEW else 0

        val txn = FusedTxn(
            id = TxnId.generate(timestamp),
            occurredAt = raw.occurredAt ?: raw.observedAt,
            amount = Money(raw.amountMinor, raw.currency),
            direction = raw.direction,
            accountId = null,
            counterpartyVpa = raw.counterpartyVpa,
            counterpartyNameRaw = raw.counterpartyNameRaw,
            displayName = resolution.displayName,
            merchantId = resolution.merchantId,
            categoryId = resolution.categoryId,
            confidence = resolution.confidence,
            resolutionRung = resolution.rung,
            sourceMask = raw.source.toMask(),
            rrn = raw.rrn,
            channel = raw.channel,
            instrument = raw.instrument,
            flags = flags,
            linkedTxnId = null,
            note = null,
            createdAt = timestamp,
            updatedAt = timestamp
        )

        repository.insert(txn, templateId = raw.templateId, bodyHash = raw.bodyHash)
        return Result.Inserted(txn)
    }

    /**
     * Bulk ingest, oldest first so that fusion sees history in the order it
     * happened. One bad row does not abort the batch: a backfill of six months of
     * SMS should not be lost to a single malformed message.
     */
    suspend fun ingestAll(
        raws: List<RawTxn>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): BatchSummary {
        // Read once for the whole batch. Importing a few years of bank SMS is
        // hundreds of transactions, and re-reading the rule table for each of
        // them was the bulk of the wall-clock cost.
        val rules = repository.userRules()

        var summary = BatchSummary()
        val ordered = raws.sortedBy { it.occurredAt ?: it.observedAt }
        for ((index, raw) in ordered.withIndex()) {
            summary = runCatching { ingest(raw, rules) }
                .fold(onSuccess = { summary + it }, onFailure = { summary.withFailure() })
            onProgress?.invoke(index + 1, ordered.size)
        }
        return summary
    }

    /**
     * A transaction the user typed in.
     *
     * Skips dedupe and fusion on purpose: the user is the authority here, and if
     * they enter the same ₹20 twice that is a statement about reality, not a
     * duplicate to swallow. Lands on rung 1 at full confidence, so it never shows
     * a "name this merchant" prompt.
     */
    suspend fun ingestManual(
        amountMinor: Long,
        currency: String,
        direction: Direction,
        displayName: String,
        occurredAt: Long,
        channel: Channel? = null,
        note: String? = null
    ): FusedTxn {
        val timestamp = now()
        val txn = FusedTxn(
            id = TxnId.generate(timestamp),
            occurredAt = occurredAt,
            amount = Money(amountMinor, currency),
            direction = direction,
            accountId = null,
            counterpartyVpa = null,
            counterpartyNameRaw = displayName,
            displayName = displayName,
            merchantId = null,
            categoryId = null,
            confidence = 1.0f,
            resolutionRung = 1,
            sourceMask = Source.MANUAL.toMask(),
            rrn = null,
            channel = channel,
            instrument = null,
            flags = FusedTxn.FLAG_MANUAL_EDIT,
            linkedTxnId = null,
            note = note?.takeIf { it.isNotBlank() },
            createdAt = timestamp,
            updatedAt = timestamp
        )
        repository.insert(txn, templateId = null, bodyHash = null)
        return txn
    }

    companion object {
        /** Both rails usually report the same payment within a minute of each other. */
        const val DEFAULT_FUSION_WINDOW_MILLIS: Long = 90_000
    }
}
