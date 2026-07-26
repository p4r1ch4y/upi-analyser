package com.spendlens.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.spendlens.core.database.SelectSpendByChannel
import com.spendlens.core.database.SelectSpendByMerchant
import com.spendlens.core.database.SelectSpendByTag
import com.spendlens.core.database.SelectSpendPointsSince
import com.spendlens.core.database.SpendLensDatabase
import com.spendlens.core.database.Tags
import com.spendlens.core.model.Split
import com.spendlens.core.model.SpendEvent
import com.spendlens.core.model.SplitShare
import com.spendlens.core.model.TxnId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A tag as the UI needs it: what it is called and whether it is a trip. */
data class TagRef(
    val id: String,
    val name: String,
    val isTrip: Boolean
)

/** What the day stream needs to know about a split without loading participants. */
data class SplitSummary(
    val txnId: String,
    val totalMinor: Long,
    val myShareMinor: Long,
    val wayCount: Int
)

/** One row of a ranked bar chart. */
data class SpendSlice(
    val label: String,
    val amountMinor: Long,
    val count: Int
)

const val TAG_KIND_TRIP = "TRIP"
const val TAG_KIND_PLAIN = "PLAIN"

/**
 * Splits, tags and the aggregates the dashboard is built from.
 *
 * Kept apart from [TransactionRepository] because these are annotations *on* the
 * ledger rather than the ledger itself: the capture pipeline never touches them,
 * and nothing here can change what a payment was.
 */
class SplitAndTagRepository(
    private val database: SpendLensDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val queries get() = database.spendLensQueries

    // ----------------------------------------------------------------- splits

    /** Every split in the window, keyed by transaction, for the stream. */
    fun splitsSince(since: Long): Flow<Map<String, SplitSummary>> =
        queries.selectSplitsSince(since).asFlow().mapToList(io).map { rows ->
            rows.associate { row ->
                row.txn_id to SplitSummary(
                    txnId = row.txn_id,
                    totalMinor = row.total_minor,
                    myShareMinor = row.my_share_minor,
                    // Participants are a second query, so the stream renders the
                    // count from the shares it will load only when a row opens.
                    wayCount = 0
                )
            }
        }

    suspend fun splitFor(txnId: String): Split? = withContext(io) {
        val row = queries.selectSplitForTxn(txnId).executeAsOneOrNull() ?: return@withContext null
        val participants = queries.selectSplitParticipants(row.id).executeAsList()
        if (participants.isEmpty()) return@withContext null

        Split(
            totalMinor = row.total_minor,
            shares = participants.map {
                SplitShare(
                    name = it.name,
                    amountMinor = it.share_minor,
                    isMe = it.is_me != 0L,
                    settledAt = it.settled_at
                )
            }
        )
    }

    /** Participant row ids, so the sheet can settle one without re-deriving keys. */
    suspend fun participantIds(txnId: String): List<String> = withContext(io) {
        val split = queries.selectSplitForTxn(txnId).executeAsOneOrNull() ?: return@withContext emptyList()
        queries.selectSplitParticipants(split.id).executeAsList().map { it.id }
    }

    /**
     * Replaces any existing split on this payment.
     *
     * Whole thing in one transaction: a split whose participants failed to write
     * would report that the user owes nothing on a payment they fronted.
     */
    suspend fun saveSplit(txnId: String, split: Split) = withContext(io) {
        val timestamp = now()
        val splitId = TxnId.generate(timestamp).value

        database.transaction {
            queries.selectSplitForTxn(txnId).executeAsOneOrNull()?.let {
                queries.deleteSplitParticipants(it.id)
            }
            queries.deleteSplit(txnId)

            queries.insertSplit(
                id = splitId,
                txn_id = txnId,
                total_minor = split.totalMinor,
                my_share_minor = split.myShareMinor,
                settled_minor = split.settledMinor
            )
            split.shares.forEachIndexed { index, share ->
                queries.insertSplitParticipant(
                    id = "$splitId-$index",
                    split_id = splitId,
                    name = share.name,
                    share_minor = share.amountMinor,
                    is_me = if (share.isMe) 1L else 0L,
                    settled_at = share.settledAt
                )
            }
        }
    }

    /** Splits every payment in [txnIds] the same number of ways. */
    suspend fun splitAll(txnIds: List<String>, names: List<String>, amountsMinor: Map<String, Long>) {
        for (txnId in txnIds) {
            val total = amountsMinor[txnId] ?: continue
            saveSplit(txnId, Split.evenly(total, names))
        }
    }

    suspend fun removeSplit(txnId: String) = withContext(io) {
        database.transaction {
            queries.selectSplitForTxn(txnId).executeAsOneOrNull()?.let {
                queries.deleteSplitParticipants(it.id)
            }
            queries.deleteSplit(txnId)
        }
    }

    /** Marks one person as having paid you back, or un-marks them. */
    suspend fun setSettled(txnId: String, participantIndex: Int, settled: Boolean) = withContext(io) {
        val row = queries.selectSplitForTxn(txnId).executeAsOneOrNull() ?: return@withContext
        val participants = queries.selectSplitParticipants(row.id).executeAsList()
        val target = participants.getOrNull(participantIndex) ?: return@withContext

        database.transaction {
            queries.setParticipantSettled(settledAt = if (settled) now() else null, id = target.id)
            val settledTotal = participants
                .filter { it.is_me == 0L }
                .sumOf { if (it.id == target.id) (if (settled) it.share_minor else 0L) else (it.settled_at?.let { _ -> it.share_minor } ?: 0L) }
            queries.updateSplitSettled(settledMinor = settledTotal, id = row.id)
        }
    }

    // ------------------------------------------------------------------- tags

    fun allTags(): Flow<List<TagRef>> =
        queries.selectAllTags().asFlow().mapToList(io).map { rows -> rows.map { it.toRef() } }

    /** Every (transaction, tag) pair in the window, so the stream avoids N queries. */
    fun tagLinksSince(since: Long): Flow<Map<String, List<TagRef>>> =
        queries.selectTagLinksSince(since).asFlow().mapToList(io).map { rows ->
            rows.groupBy({ it.txn_id }) { TagRef(it.id, it.name, it.kind == TAG_KIND_TRIP) }
        }

    /** One-shot equivalent of [tagLinksSince], for the exporter. */
    suspend fun tagLinksOnce(since: Long): Map<String, List<TagRef>> = withContext(io) {
        queries.selectTagLinksSince(since).executeAsList()
            .groupBy({ it.txn_id }) { TagRef(it.id, it.name, it.kind == TAG_KIND_TRIP) }
    }

    suspend fun tagsFor(txnId: String): List<TagRef> = withContext(io) {
        queries.selectTagsForTxn(txnId).executeAsList().map { it.toRef() }
    }

    /** The trip covering [at], for the home screen banner. */
    fun tripAt(at: Long): Flow<Tags?> =
        queries.selectTripAt(at).asFlow().mapToList(io).map { it.firstOrNull() }

    /**
     * Finds a tag by name or creates it. Names are unique, so tagging by typing
     * the same word twice reuses the tag rather than growing a second one.
     */
    suspend fun ensureTag(
        name: String,
        isTrip: Boolean = false,
        startsAt: Long? = null,
        endsAt: Long? = null
    ): TagRef? = withContext(io) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null

        val timestamp = now()
        queries.insertTag(
            id = TxnId.generate(timestamp).value,
            name = trimmed,
            kind = if (isTrip) TAG_KIND_TRIP else TAG_KIND_PLAIN,
            starts_at = startsAt,
            ends_at = endsAt,
            created_at = timestamp
        )
        queries.selectTagByName(trimmed).executeAsOneOrNull()?.toRef()
    }

    suspend fun tag(txnIds: List<String>, tagId: String) = withContext(io) {
        database.transaction {
            txnIds.forEach { queries.tagTransaction(txn_id = it, tag_id = tagId) }
        }
    }

    suspend fun untag(txnId: String, tagId: String) = withContext(io) {
        queries.untagTransaction(txnId = txnId, tagId = tagId)
    }

    suspend fun deleteTag(tagId: String) = withContext(io) {
        database.transaction {
            queries.deleteTagLinks(tagId)
            queries.deleteTag(tagId)
        }
    }

    // -------------------------------------------------------------- analytics

    suspend fun spendByMerchant(
        since: Long, until: Long, direction: String = "DEBIT", limit: Int = 40
    ): List<SpendSlice> = withContext(io) {
        queries.selectSpendByMerchant(since, until, direction, limit.toLong()).executeAsList().map { it.toSlice() }
    }

    suspend fun spendByChannel(since: Long, until: Long, direction: String = "DEBIT"): List<SpendSlice> =
        withContext(io) {
            queries.selectSpendByChannel(since, until, direction).executeAsList().map { it.toSlice() }
        }

    suspend fun spendByTag(
        since: Long, until: Long, direction: String = "DEBIT", limit: Int = 40
    ): List<SpendSlice> = withContext(io) {
        queries.selectSpendByTag(since, until, direction, limit.toLong()).executeAsList().map { it.toSlice() }
    }

    suspend fun spendPoints(since: Long, until: Long): List<SpendEvent> = withContext(io) {
        queries.selectSpendPointsSince(since, until).executeAsList().map { it.toPoint() }
    }

    private companion object {
        fun Tags.toRef() = TagRef(id = id, name = name, isTrip = kind == TAG_KIND_TRIP)

        fun SelectSpendByMerchant.toSlice() =
            SpendSlice(label, effective_minor ?: 0L, txn_count.toInt())

        fun SelectSpendByChannel.toSlice() =
            SpendSlice(label, effective_minor ?: 0L, txn_count.toInt())

        fun SelectSpendByTag.toSlice() =
            SpendSlice(label, effective_minor ?: 0L, txn_count.toInt())

        fun SelectSpendPointsSince.toPoint() = SpendEvent(
            occurredAt = occurred_at,
            effectiveMinor = effective_minor ?: 0L,
            isCredit = direction == "CREDIT"
        )
    }
}
