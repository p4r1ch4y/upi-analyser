package com.spendlens.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.spendlens.core.database.SelectDayStats
import com.spendlens.core.database.Source_messages
import com.spendlens.core.database.SpendLensDatabase
import com.spendlens.core.database.Transactions
import com.spendlens.core.model.Direction
import com.spendlens.core.model.FusedTxn
import com.spendlens.core.model.RawTxn
import com.spendlens.core.model.TxnId
import com.spendlens.core.resolution.MerchantResolver
import com.spendlens.core.resolution.VpaRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Everything that touches the encrypted ledger.
 *
 * Reads are exposed as flows so the UI re-renders the moment a payment lands;
 * writes are suspending and confined to [io].
 */
class TransactionRepository(
    private val database: SpendLensDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val queries get() = database.spendLensQueries

    // ------------------------------------------------------------------ reads

    fun transactionsSince(since: Long): Flow<List<Transactions>> =
        queries.selectTransactionsSince(since).asFlow().mapToList(io)

    /** One-shot equivalent, for the exporter — a flow would never complete. */
    suspend fun transactionsSinceOnce(since: Long): List<Transactions> = withContext(io) {
        queries.selectTransactionsSince(since).executeAsList()
    }

    fun dayStats(dayStart: Long, dayEnd: Long): Flow<SelectDayStats?> =
        queries.selectDayStats(dayStart, dayEnd).asFlow().mapToOneOrNull(io)

    suspend fun dayTotalMinor(dayStart: Long, dayEnd: Long): Long = withContext(io) {
        queries.selectDayTotal(dayStart, dayEnd).executeAsOneOrNull()?.total_minor ?: 0L
    }

    suspend fun userRules(): List<VpaRule> = withContext(io) {
        queries.selectVpaRules().executeAsList().mapNotNull { row ->
            val matchType = runCatching { VpaRule.MatchType.valueOf(row.match_type) }.getOrNull()
                ?: return@mapNotNull null
            VpaRule(
                id = row.id,
                pattern = row.pattern,
                matchType = matchType,
                merchantName = row.merchant_name,
                merchantId = row.merchant_id,
                categoryId = row.category_id,
                priority = row.priority.toInt()
            )
        }
    }

    // ----------------------------------------------------------------- writes

    /**
     * Records [hash] and reports whether it had already been seen. One statement
     * decides it, so two rails delivering the same message concurrently cannot
     * both come back "new".
     */
    suspend fun markSeen(hash: String, now: Long): Boolean = withContext(io) {
        database.transactionWithResult {
            val alreadySeen = queries.isHashSeen(hash).executeAsOneOrNull() != null
            if (!alreadySeen) queries.insertSeenHash(hash, now)
            alreadySeen
        }
    }

    /** The existing row this raw transaction is another view of, if any. */
    suspend fun findFusionTarget(raw: RawTxn, windowMillis: Long): Transactions? = withContext(io) {
        raw.rrn?.takeIf { it.isNotBlank() }?.let { rrn ->
            queries.selectByRrn(rrn).executeAsOneOrNull()?.let { return@withContext it }
        }
        val at = raw.occurredAt ?: raw.observedAt
        val candidates = queries.selectFusionCandidates(
            amountMinor = raw.amountMinor,
            currency = raw.currency,
            direction = raw.direction.name,
            windowStart = at - windowMillis,
            windowEnd = at + windowMillis
        ).executeAsList()

        // Prefer a candidate whose funding account matches; otherwise the nearest in time.
        raw.accountTail?.takeIf { it.isNotBlank() }?.let { tail ->
            candidates.firstOrNull { it.counterparty_vpa != null && it.rrn?.endsWith(tail) == true }
        } ?: candidates.minByOrNull { kotlin.math.abs(it.occurred_at - at) }
    }

    /**
     * Records the message a transaction was read out of.
     *
     * Called for the first sighting and again for every rail that later fuses
     * into the same row, so the detail sheet can show that a payment was seen
     * twice and by whom.
     */
    suspend fun recordSource(txnId: String, raw: RawTxn, now: Long = System.currentTimeMillis()) =
        withContext(io) {
            val body = raw.sourceBody ?: return@withContext
            queries.insertSourceMessage(
                id = TxnId.generate(now).value,
                txn_id = txnId,
                source = raw.source.name,
                origin = raw.sourceOrigin,
                body = body,
                received_at = raw.observedAt,
                template_id = raw.templateId
            )
        }

    suspend fun sourceMessages(txnId: String): List<Source_messages> = withContext(io) {
        queries.selectSourceMessages(txnId).executeAsList()
    }

    suspend fun insert(txn: FusedTxn, templateId: String?, bodyHash: String?) = withContext(io) {
        queries.insertTransaction(
            id = txn.id.value,
            occurred_at = txn.occurredAt,
            amount_minor = txn.amount.amountMinor,
            currency = txn.amount.currency,
            direction = txn.direction.name,
            account_id = txn.accountId,
            counterparty_vpa = txn.counterpartyVpa,
            counterparty_name_raw = txn.counterpartyNameRaw,
            display_name = txn.displayName,
            merchant_id = txn.merchantId,
            category_id = txn.categoryId,
            confidence = txn.confidence.toDouble(),
            resolution_rung = txn.resolutionRung.toLong(),
            source_mask = txn.sourceMask.toLong(),
            rrn = txn.rrn,
            channel = txn.channel?.name,
            instrument = txn.instrument?.name,
            flags = txn.flags.toLong(),
            linked_txn_id = txn.linkedTxnId,
            note = txn.note,
            template_id = templateId,
            body_hash = bodyHash,
            created_at = txn.createdAt,
            updated_at = txn.updatedAt,
            deleted_at = null
        )
    }

    suspend fun merge(
        existing: Transactions,
        raw: RawTxn,
        displayName: String,
        confidence: Float,
        now: Long
    ) = withContext(io) {
        queries.mergeIntoTransaction(
            counterparty_vpa = raw.counterpartyVpa,
            counterparty_name_raw = raw.counterpartyNameRaw,
            display_name = displayName,
            rrn = raw.rrn,
            source_bit = raw.source.toMask().toLong(),
            confidence = confidence.toDouble(),
            updated_at = now,
            id = existing.id
        )
    }

    /**
     * Turns a user correction into a rule and applies it to history, so naming a
     * merchant once fixes every past payment to that VPA as well.
     */
    suspend fun nameMerchant(
        vpa: String,
        displayName: String,
        categoryId: String? = null,
        now: Long = System.currentTimeMillis()
    ) = withContext(io) {
        database.transaction {
            queries.insertVpaRule(
                id = "rule:$vpa",
                pattern = vpa,
                match_type = VpaRule.MatchType.EXACT.name,
                merchant_id = null,
                merchant_name = displayName,
                category_id = categoryId,
                priority = 100,
                origin = "USER",
                created_at = now
            )
            queries.relabelByVpa(
                display_name = displayName,
                merchant_id = null,
                category_id = categoryId,
                updated_at = now,
                vpa = vpa
            )
        }
    }

    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis()) = withContext(io) {
        database.transaction {
            queries.softDeleteTransaction(deleted_at = now, updated_at = now, id = id)
            // A soft-deleted row keeps its history, but the message is the most
            // sensitive thing stored about it, so that goes for real.
            queries.deleteSourceMessagesFor(id)
        }
    }

    /**
     * Repairs labels an earlier build's resolution ladder got wrong.
     *
     * Fixing the ladder only helps rows ingested after the fix; the display name
     * is resolved once and stored, so several hundred already-imported bank
     * messages would have kept reading "Manual entry" forever. Idempotent - once
     * repaired, the WHERE clauses match nothing.
     */
    suspend fun repairLabels(now: Long = System.currentTimeMillis()) = withContext(io) {
        database.transaction {
            queries.repairFalseManualLabels(now)
            queries.repairIgnoredCapturedNames(now)
        }
    }

    /**
     * Renames one payment.
     *
     * Where the payment carries a VPA, [nameMerchant] is better - it writes a rule
     * and replays it over every past and future payment to the same address. But
     * most bank SMS carries no VPA and no payee at all, so for those there is
     * nothing to make a rule out of and this row is all the user can fix.
     */
    suspend fun rename(id: String, displayName: String, now: Long = System.currentTimeMillis()) =
        withContext(io) {
            queries.renameTransaction(display_name = displayName, updated_at = now, id = id)
        }

    /**
     * Re-reads stored source messages with the *current* parser and relabels
     * rows that still carry a placeholder.
     *
     * Display names are resolved once and written, so every improvement to the
     * templates or the resolution ladder used to help only payments captured
     * afterwards - the ledger kept whatever it was told the first time. Because
     * the original message is now stored beside each row, the app can simply ask
     * again. A template added today retroactively names a payment from March.
     *
     * Deliberately narrow about what it will touch:
     *  - only rows whose label is still a generic placeholder,
     *  - never a row the user has renamed (FLAG_MANUAL_EDIT),
     *  - only the label. Amount, direction and date are left exactly as they
     *    were, so a re-parse can never move money or shift a day's total.
     */
    suspend fun relabelFromSources(
        labelOf: (SourceRecordRow) -> String?,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(io) {
        val candidates = queries.selectRelabelCandidates().executeAsList()
        var changed = 0
        database.transaction {
            for (row in candidates) {
                val label = labelOf(
                    SourceRecordRow(row.source, row.origin, row.body, row.received_at)
                )?.takeIf { it.isNotBlank() && it != row.display_name } ?: continue
                queries.relabelTransaction(display_name = label, updated_at = now, id = row.id)
                changed++
            }
        }
        changed
    }

    /** How many unnamed payments share this label, amount and direction. */
    suspend fun countSimilar(displayName: String, amountMinor: Long, direction: String): Int =
        withContext(io) {
            queries.countSimilarTransactions(displayName, amountMinor, direction)
                .executeAsOne().toInt()
        }

    /**
     * Renames every payment sharing a label, amount and direction.
     *
     * The bulk case for bank SMS: hundreds of rows say the same thing because the
     * bank named no payee, and they group tightly by amount. Rows the user has
     * already named are excluded, so this can never overwrite a deliberate choice.
     */
    suspend fun renameSimilar(
        displayName: String,
        amountMinor: Long,
        direction: String,
        newName: String,
        now: Long = System.currentTimeMillis()
    ) = withContext(io) {
        queries.renameSimilarTransactions(
            new_name = newName,
            updated_at = now,
            display_name = displayName,
            amount_minor = amountMinor,
            direction = direction
        )
    }

    /** Dedupe hashes are only useful for as long as a replay is plausible. */
    suspend fun pruneHashesOlderThan(cutoff: Long) = withContext(io) {
        queries.pruneOldHashes(cutoff)
    }
}

/** A stored source message, as the re-parse pass sees it. */
data class SourceRecordRow(
    val source: String,
    val origin: String?,
    val body: String,
    val receivedAt: Long
)

/** True when the row still needs a human to say what it is. */
fun Transactions.needsReview(): Boolean =
    (flags and FusedTxn.FLAG_NEEDS_REVIEW.toLong()) != 0L

fun Transactions.directionOrNull(): Direction? =
    runCatching { Direction.valueOf(direction) }.getOrNull()

fun Transactions.txnId(): TxnId = TxnId(id)

/** Resolution result carried alongside the row, for the review affordances. */
fun MerchantResolver.Resolution.needsReview(): Boolean = rung >= 6 || confidence < 0.5f
