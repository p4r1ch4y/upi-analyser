package com.spendlens.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spendlens.SpendLensApp
import com.spendlens.core.database.Transactions
import com.spendlens.core.model.Direction
import com.spendlens.core.model.FusedTxn
import com.spendlens.core.model.Split
import com.spendlens.data.CsvStatementImporter
import com.spendlens.data.Days
import com.spendlens.data.SmsInboxImporter
import com.spendlens.data.SplitAndTagRepository
import com.spendlens.data.CsvExporter
import com.spendlens.data.SettingsStore
import com.spendlens.data.SplitSummary
import com.spendlens.data.TagRef
import com.spendlens.data.TransactionIngestor
import com.spendlens.data.TransactionRepository
import com.spendlens.service.UpiNotificationListener
import com.spendlens.ui.entry.ManualEntry
import com.spendlens.ui.entry.SourceRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One payment, already shaped for the row that renders it. */
data class TxnUi(
    val id: String,
    val occurredAt: Long,
    val displayName: String,
    val amountMinor: Long,
    val currency: String,
    val isCredit: Boolean,
    val needsReview: Boolean,
    val counterpartyVpa: String?,
    val split: SplitSummary? = null,
    val tags: List<TagRef> = emptyList()
) {
    /**
     * What this payment actually cost the user: their share when it was split,
     * the full amount otherwise.
     *
     * This is the number the row and every total use. Showing the gross would tell
     * someone who fronted a group dinner that they spent ₹8,000 on food, which is
     * both wrong and exactly the kind of thing that makes people stop trusting a
     * money app.
     */
    val effectiveMinor: Long get() = split?.myShareMinor ?: amountMinor

    val isSplit: Boolean get() = split != null
}

/** A calendar day of payments, newest day first. */
data class DayUi(
    val date: LocalDate,
    val spentMinor: Long,
    val transactions: List<TxnUi>
) {
    val tapCount: Int get() = transactions.size
    val merchantCount: Int get() = transactions.map { it.displayName }.distinct().size
}

/** The trip banner across the top of the stream. */
data class TripBanner(
    val tagId: String,
    val name: String,
    val dayIndex: Int,
    val dayCount: Int,
    val spentMinor: Long
)

data class DayStreamUiState(
    val days: List<DayUi> = emptyList(),
    val loading: Boolean = true,
    val importing: Boolean = false,
    /** Which past days are open. Today is always open and is not listed here. */
    val expandedDays: Set<LocalDate> = emptySet(),
    /** Transactions the user has ticked, for a bulk split or tag. */
    val selected: Set<String> = emptySet(),
    val trip: TripBanner? = null,
    val allTags: List<TagRef> = emptyList()
) {
    val today: DayUi? get() = days.firstOrNull()
    val earlier: List<DayUi> get() = days.drop(1)
    val selecting: Boolean get() = selected.isNotEmpty()

    fun isExpanded(date: LocalDate): Boolean = date in expandedDays

    private val allTransactions: List<TxnUi> get() = days.flatMap { it.transactions }

    val selectedTotalMinor: Long
        get() = allTransactions.filter { it.id in selected }.sumOf { it.amountMinor }

    fun selectedTransactions(): List<TxnUi> = allTransactions.filter { it.id in selected }

    fun transaction(id: String): TxnUi? = allTransactions.firstOrNull { it.id == id }
}

/** One-shot feedback. Typed rather than pre-formatted so the UI owns the wording. */
sealed interface DayStreamEvent {
    data class Exported(val rowCount: Int) : DayStreamEvent
    data object NoEmailApp : DayStreamEvent
    data class Imported(val summary: TransactionIngestor.BatchSummary) : DayStreamEvent
    data object TransactionAdded : DayStreamEvent
    data object ListenerNotConnected : DayStreamEvent
    data object SmsUnavailable : DayStreamEvent
    data class SplitApplied(val count: Int) : DayStreamEvent
    data class Tagged(val count: Int, val tagName: String) : DayStreamEvent
    data class Failed(val reason: String?) : DayStreamEvent
}

class DayStreamViewModel(
    private val repository: TransactionRepository,
    private val ingestor: TransactionIngestor,
    private val smsImporter: SmsInboxImporter,
    private val csvImporter: CsvStatementImporter,
    private val annotations: SplitAndTagRepository,
    private val settings: SettingsStore,
    private val exporter: CsvExporter,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    /**
     * Held as state, not read straight off the formatter, because changing a
     * global does not recompose anything. Writing it here is what redraws every
     * amount on screen.
     */
    private val _currency = MutableStateFlow(settings.currency)
    val currency: StateFlow<String> = _currency.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun reportNoEmailApp() {
        viewModelScope.launch { _events.send(DayStreamEvent.NoEmailApp) }
    }

    fun setCurrency(code: String) {
        settings.currency = code   // also updates MoneyFormat
        _currency.value = code
    }

    /** Writes the ledger to a file the user picked through the system picker. */
    fun exportTo(uri: Uri, includeSourceMessages: Boolean) {
        if (_exporting.value) return
        viewModelScope.launch {
            _exporting.value = true
            runCatching { exporter.export(uri, includeSourceMessages = includeSourceMessages) }
                .fold(
                    onSuccess = { _events.send(DayStreamEvent.Exported(it.rowCount)) },
                    onFailure = { _events.send(DayStreamEvent.Failed(it.message)) }
                )
            _exporting.value = false
        }
    }

    private val importing = MutableStateFlow(false)
    private val expanded = MutableStateFlow<Set<LocalDate>>(emptySet())
    private val selected = MutableStateFlow<Set<String>>(emptySet())

    private val _events = Channel<DayStreamEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val since = Days.startOfToday(zone) - HISTORY_MILLIS

    val state: StateFlow<DayStreamUiState> = combine(
        repository.transactionsSince(since),
        annotations.splitsSince(since),
        annotations.tagLinksSince(since),
        annotations.allTags(),
        combine(importing, expanded, selected) { busy, open, ticked -> Triple(busy, open, ticked) }
    ) { rows, splits, tagLinks, tags, (busy, open, ticked) ->
        val days = groupByDay(rows, splits, tagLinks)
        DayStreamUiState(
            days = days,
            loading = false,
            importing = busy,
            expandedDays = open,
            // A row that has since been deleted must not stay ticked, or the
            // action bar offers to split payments that are no longer there.
            selected = ticked intersect days.flatMap { day -> day.transactions.map { it.id } }.toSet(),
            trip = tripBanner(days, tags),
            allTags = tags
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayStreamUiState())

    /** True when this build can read SMS at all, so the UI can hide the option. */
    val smsSupported: Boolean get() = smsImporter.declaresReadSms()

    // -------------------------------------------------------------- expansion

    fun toggleDay(date: LocalDate) {
        expanded.value = if (date in expanded.value) expanded.value - date else expanded.value + date
    }

    // -------------------------------------------------------------- selection

    fun toggleSelection(id: String) {
        selected.value = if (id in selected.value) selected.value - id else selected.value + id
    }

    /**
     * Ticks or unticks a whole day. This is what "mark a day as splittable" means:
     * select the day, then split or tag everything in it in one go.
     */
    fun toggleDaySelection(date: LocalDate) {
        val ids = state.value.days.firstOrNull { it.date == date }
            ?.transactions?.map { it.id }?.toSet().orEmpty()
        if (ids.isEmpty()) return
        selected.value = if (ids.all { it in selected.value }) {
            selected.value - ids
        } else {
            selected.value + ids
        }
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    // ----------------------------------------------------------------- splits

    /**
     * Splits every ticked payment [ways] ways.
     *
     * Each payment is split on its own total rather than the selection being
     * pooled and divided, so the arithmetic stays true per row and a payment can
     * later be settled or un-split by itself.
     */
    fun splitSelected(ways: Int, names: List<String>) {
        val targets = state.value.selectedTransactions()
        if (targets.isEmpty() || ways < 2) return

        viewModelScope.launch {
            runCatching {
                for (txn in targets) {
                    annotations.saveSplit(txn.id, Split.evenly(txn.amountMinor, names))
                }
            }.fold(
                onSuccess = {
                    clearSelection()
                    _events.send(DayStreamEvent.SplitApplied(targets.size))
                },
                onFailure = { _events.send(DayStreamEvent.Failed(it.message)) }
            )
        }
    }

    fun splitOne(txnId: String, names: List<String>) {
        val txn = state.value.transaction(txnId) ?: return
        viewModelScope.launch {
            runCatching { annotations.saveSplit(txnId, Split.evenly(txn.amountMinor, names)) }
                .onFailure { _events.send(DayStreamEvent.Failed(it.message)) }
        }
    }

    fun removeSplit(txnId: String) {
        viewModelScope.launch { annotations.removeSplit(txnId) }
    }

    fun setSettled(txnId: String, participantIndex: Int, settled: Boolean) {
        viewModelScope.launch { annotations.setSettled(txnId, participantIndex, settled) }
    }

    suspend fun splitDetail(txnId: String): Split? = annotations.splitFor(txnId)

    /** The messages this row was read out of, for the detail sheet's Source section. */
    suspend fun sourcesFor(txnId: String): List<SourceRecord> =
        repository.sourceMessages(txnId).map {
            SourceRecord(
                source = it.source,
                origin = it.origin,
                body = it.body,
                receivedAt = it.received_at,
                templateId = it.template_id
            )
        }

    // ------------------------------------------------------------------- tags

    /** Tags every ticked payment, creating the tag if it is a new name. */
    fun tagSelected(name: String, isTrip: Boolean = false) {
        val ids = state.value.selected.toList()
        if (ids.isEmpty()) return
        applyTag(ids, name, isTrip) { _events.send(DayStreamEvent.Tagged(ids.size, name)) }
    }

    fun tagOne(txnId: String, name: String, isTrip: Boolean = false) {
        applyTag(listOf(txnId), name, isTrip)
    }

    private fun applyTag(
        ids: List<String>,
        name: String,
        isTrip: Boolean,
        onDone: suspend () -> Unit = {}
    ) {
        viewModelScope.launch {
            runCatching {
                // A trip spans the payments it is being put on, which is what lets
                // the banner say "day 3 of 5" without asking for dates.
                val target = state.value.days.flatMap { it.transactions }.filter { it.id in ids }
                val starts = target.minOfOrNull { it.occurredAt }
                val ends = target.maxOfOrNull { it.occurredAt }

                val tag = annotations.ensureTag(
                    name = name,
                    isTrip = isTrip,
                    startsAt = if (isTrip) starts else null,
                    endsAt = if (isTrip) ends else null
                ) ?: return@runCatching
                annotations.tag(ids, tag.id)
            }.fold(
                onSuccess = { clearSelection(); onDone() },
                onFailure = { _events.send(DayStreamEvent.Failed(it.message)) }
            )
        }
    }

    fun untag(txnId: String, tagId: String) {
        viewModelScope.launch { annotations.untag(txnId, tagId) }
    }

    // --------------------------------------------------------------- corrections

    fun nameMerchant(vpa: String, displayName: String) {
        viewModelScope.launch { repository.nameMerchant(vpa, displayName) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.softDelete(id) }
    }

    // ------------------------------------------------------------------- entry

    fun addManual(entry: ManualEntry, currency: String = "INR") {
        viewModelScope.launch {
            runCatching {
                ingestor.ingestManual(
                    amountMinor = entry.amountMinor,
                    currency = currency,
                    direction = entry.direction,
                    displayName = entry.displayName,
                    occurredAt = entry.occurredAt,
                    channel = entry.channel,
                    note = entry.note
                )
            }.fold(
                onSuccess = { _events.send(DayStreamEvent.TransactionAdded) },
                onFailure = { _events.send(DayStreamEvent.Failed(it.message)) }
            )
        }
    }

    // ------------------------------------------------------------------ import

    fun rescanNotifications() {
        if (!UpiNotificationListener.rescanTray()) {
            viewModelScope.launch { _events.send(DayStreamEvent.ListenerNotConnected) }
        }
    }

    fun importSmsHistory() {
        if (!smsImporter.isAvailable()) {
            viewModelScope.launch { _events.send(DayStreamEvent.SmsUnavailable) }
            return
        }
        runImport { ingestor.ingestAll(smsImporter.readInbox()) }
    }

    fun importCsv(uri: Uri) {
        runImport {
            val outcome = csvImporter.read(uri)
            val summary = ingestor.ingestAll(outcome.transactions)
            summary.copy(failed = summary.failed + outcome.skippedLines.size)
        }
    }

    private fun runImport(block: suspend () -> TransactionIngestor.BatchSummary) {
        if (importing.value) return
        viewModelScope.launch {
            importing.value = true
            runCatching { block() }.fold(
                onSuccess = { _events.send(DayStreamEvent.Imported(it)) },
                onFailure = { _events.send(DayStreamEvent.Failed(it.message)) }
            )
            importing.value = false
        }
    }

    // ----------------------------------------------------------------- shaping

    private fun groupByDay(
        rows: List<Transactions>,
        splits: Map<String, SplitSummary>,
        tagLinks: Map<String, List<TagRef>>
    ): List<DayUi> {
        val today = Days.localDate(System.currentTimeMillis(), zone)
        val byDate = rows
            .map { it.toUi(splits[it.id], tagLinks[it.id].orEmpty()) }
            .groupBy { Days.localDate(it.occurredAt, zone) }

        // Today always renders, even with nothing in it - the empty state lives in
        // the hero, not in place of the whole screen.
        val dates = (byDate.keys + today).distinct().sortedDescending()

        return dates.map { date ->
            val transactions = byDate[date].orEmpty().sortedBy { it.occurredAt }
            DayUi(
                date = date,
                spentMinor = transactions.filter { !it.isCredit }.sumOf { it.effectiveMinor },
                transactions = transactions
            )
        }
    }

    /** The trip covering today, with how far through it we are. */
    private fun tripBanner(days: List<DayUi>, tags: List<TagRef>): TripBanner? {
        val trips = tags.filter { it.isTrip }
        if (trips.isEmpty()) return null

        val tagged = days.flatMap { it.transactions }
            .flatMap { txn -> txn.tags.filter { it.isTrip }.map { it to txn } }
            .groupBy({ it.first.id }, { it.second })

        // The trip in progress if there is one, otherwise the most recent.
        val today = Days.localDate(System.currentTimeMillis(), zone)
        val candidates = tagged.entries.mapNotNull { (tagId, transactions) ->
            val trip = trips.firstOrNull { it.id == tagId } ?: return@mapNotNull null
            val dates = transactions.map { Days.localDate(it.occurredAt, zone) }
            val start = dates.min()
            val end = maxOf(dates.max(), today.takeIf { !it.isBefore(start) && !it.isAfter(dates.max()) } ?: dates.max())
            Triple(trip, start, end)
        }

        val (trip, start, end) = candidates
            .filter { (_, start, end) -> !today.isBefore(start) && !today.isAfter(end) }
            .maxByOrNull { it.second.toEpochDay() }
            ?: candidates.maxByOrNull { it.third.toEpochDay() }
            ?: return null

        val transactions = tagged[trip.id].orEmpty()
        val dayCount = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        val dayIndex = (ChronoUnit.DAYS.between(start, minOf(today, end)) + 1).toInt().coerceIn(1, dayCount)

        return TripBanner(
            tagId = trip.id,
            name = trip.name,
            dayIndex = dayIndex,
            dayCount = dayCount,
            spentMinor = transactions.filter { !it.isCredit }.sumOf { it.effectiveMinor }
        )
    }

    private fun Transactions.toUi(split: SplitSummary?, tags: List<TagRef>) = TxnUi(
        id = id,
        occurredAt = occurred_at,
        displayName = display_name,
        amountMinor = amount_minor,
        currency = currency,
        isCredit = direction == Direction.CREDIT.name,
        needsReview = (flags and FusedTxn.FLAG_NEEDS_REVIEW.toLong()) != 0L,
        counterpartyVpa = counterparty_vpa,
        split = split,
        tags = tags
    )

    companion object {
        private const val HISTORY_MILLIS = 365L * 24 * 60 * 60 * 1000  // a year

        fun factory(graph: SpendLensApp.Graph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DayStreamViewModel(
                        repository = graph.repository,
                        ingestor = graph.ingestor,
                        smsImporter = graph.smsImporter,
                        csvImporter = graph.csvImporter,
                        annotations = graph.annotations,
                        settings = graph.settings,
                        exporter = graph.csvExporter
                    ) as T
            }
    }
}
