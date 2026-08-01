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
    /** Attempted and did not go through. Shown, never counted. */
    val failed: Boolean = false,
    val note: String? = null,
    val counterpartyVpa: String?,
    /** The rail, as stored. Null when the message never said. */
    val channel: String? = null,
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

    /**
     * Free-text match across everything a person might remember about a payment:
     * who it was, what it cost, what they tagged it, and the note they left.
     *
     * The amount is matched as plain digits so that typing "250" finds ₹250
     * without the user having to guess the formatting - "2,50" and "250.00" are
     * the same payment to them.
     */
    fun matches(query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        if (displayName.lowercase().contains(needle)) return true
        if (counterpartyVpa?.lowercase()?.contains(needle) == true) return true
        if (tags.any { it.name.lowercase().contains(needle) }) return true
        val digits = needle.filter { it.isDigit() }
        if (digits.isNotEmpty() && (amountMinor / 100).toString().contains(digits)) return true
        return false
    }
}

/**
 * The payments behind one bar on the Insights breakdown.
 *
 * Carries the window as well as the group, because the two screens must agree.
 * Tapping a bar that reads ₹4,320 over the last 30 days and landing on a stream
 * showing a year's ₹51,000 for the same payee does not read as "a different
 * range" - it reads as one of the two numbers being wrong.
 */
data class StreamFilter(
    val kind: Kind,
    /** The stored value to match: a display name, a tag name, or a channel. */
    val value: String,
    /** The value as the breakdown showed it, which is what the bar says. */
    val label: String,
    val sinceMillis: Long,
    val untilMillis: Long,
    /** "last 30 days", or the budget's own window. Shown, never parsed. */
    val rangeLabel: String,
    /**
     * Which side of the ledger the bar was measuring.
     *
     * Insights flips its whole report between expense and income, so a bar tapped
     * while it is showing income is about credits. Without this the stream would
     * open on the debits to the same payee and report a total of zero.
     */
    val credits: Boolean = false
) {
    /** [ALL] narrows by window alone, which is what a total budget is. */
    enum class Kind { ALL, MERCHANT, TAG, CHANNEL }

    fun matches(txn: TxnUi): Boolean {
        if (txn.occurredAt < sinceMillis || txn.occurredAt >= untilMillis) return false
        if (txn.isCredit != credits) return false
        return when (kind) {
            Kind.ALL -> true
            Kind.MERCHANT -> txn.displayName == value
            Kind.TAG -> txn.tags.any { it.name == value }
            // The breakdown folds every unnamed rail into one "Other" bar, so the
            // filter behind it has to accept a null channel too.
            Kind.CHANNEL -> (txn.channel ?: UNKNOWN_CHANNEL) == value
        }
    }

    companion object {
        const val UNKNOWN_CHANNEL = "UNKNOWN"
    }
}

/** A calendar day of payments, newest day first. */
data class DayUi(
    val date: LocalDate,
    val spentMinor: Long,
    /**
     * Money that came in on this day.
     *
     * Held separately rather than netted off, because the day total is a
     * *spending* figure and always has been - netting a salary against a week of
     * chai would make the number meaningless. But it cannot simply be dropped
     * either: a day whose only payment was an incoming ₹150 rendered as a bare
     * "₹0" above a row plainly showing "+₹150", which reads as the app having
     * lost track rather than as "you spent nothing".
     */
    val receivedMinor: Long = 0L,
    val transactions: List<TxnUi>
) {
    val tapCount: Int get() = transactions.size
    val merchantCount: Int get() = transactions.map { it.displayName }.distinct().size

    /** True when the day has money in but nothing out, which is what looked broken. */
    val receivedOnly: Boolean get() = receivedMinor > 0L && spentMinor == 0L
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
    val allTags: List<TagRef> = emptyList(),
    /** Non-blank while the user is searching; the stream shows only matches. */
    val query: String = "",
    /** Set when the stream was opened from a bar on Insights or from a budget. */
    val filter: StreamFilter? = null
) {
    val searching: Boolean get() = query.isNotBlank()

    /**
     * True when the stream is answering a question rather than showing a diary.
     *
     * Both searching and filtering flatten it: every matching day is open, the
     * today hero is gone, and empty days are not drawn. A result list that needs
     * unfolding is not a result list.
     */
    val flattened: Boolean get() = searching || filter != null

    val matchCount: Int get() = days.sumOf { it.transactions.size }

    /**
     * What the filtered payments came to, measured the same way the bar was.
     *
     * Failed payments are shown in the rows and left out of the figure, exactly
     * as the analytics queries do - this number has to be the one the user just
     * tapped, or the trip between the two screens costs them their trust in both.
     */
    val matchTotalMinor: Long
        get() {
            val credits = filter?.credits ?: false
            return days.sumOf { day ->
                day.transactions
                    .filter { it.isCredit == credits && !it.failed }
                    .sumOf { it.effectiveMinor }
            }
        }

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
    data object NoBrowser : DayStreamEvent
    data object Copied : DayStreamEvent
    data object Renamed : DayStreamEvent
    data object NoteSaved : DayStreamEvent
    data class RenamedMany(val count: Int) : DayStreamEvent
    /**
     * Already-resolved text, for confirmations owned by another screen.
     *
     * The rest of this sealed interface is deliberately typed so the UI owns the
     * wording, but a budget is saved from a sheet that already has the string;
     * inventing a `BudgetSaved` case to re-look-up the same resource would be
     * ceremony, not clarity.
     */
    data class Message(val text: String) : DayStreamEvent
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

    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _typeface = MutableStateFlow(settings.typeface)
    val typeface: StateFlow<String> = _typeface.asStateFlow()

    fun setThemeMode(value: String) {
        settings.themeMode = value
        _themeMode.value = value
    }

    fun setTypeface(value: String) {
        settings.typeface = value
        _typeface.value = value
    }

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    fun reportNoEmailApp() {
        viewModelScope.launch { _events.send(DayStreamEvent.NoEmailApp) }
    }

    fun reportNoBrowser() {
        viewModelScope.launch { _events.send(DayStreamEvent.NoBrowser) }
    }

    fun reportCopied() {
        viewModelScope.launch { _events.send(DayStreamEvent.Copied) }
    }

    /** Shows a message another screen has already worded. */
    fun report(text: String) {
        viewModelScope.launch { _events.send(DayStreamEvent.Message(text)) }
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
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<StreamFilter?>(null)

    fun setQuery(value: String) { query.value = value }

    /**
     * Opens the stream on the payments behind one bar of the breakdown.
     *
     * Any search in progress is dropped: the two are different questions, and
     * arriving from Insights to find the results narrowed by a word typed ten
     * minutes ago would read as the filter having failed.
     */
    fun setFilter(value: StreamFilter?) {
        filter.value = value
        if (value != null) query.value = ""
    }

    fun clearFilter() { filter.value = null }

    private val _events = Channel<DayStreamEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val since = Days.startOfToday(zone) - HISTORY_MILLIS

    val state: StateFlow<DayStreamUiState> = combine(
        repository.transactionsSince(since),
        annotations.splitsSince(since),
        annotations.tagLinksSince(since),
        annotations.allTags(),
        combine(importing, expanded, selected, query, filter) { busy, open, ticked, text, scope ->
            StreamControls(busy, open, ticked, text, scope)
        }
    ) { rows, splits, tagLinks, tags, controls ->
        val (busy, open, ticked, text, scope) = controls
        val days = groupByDay(rows, splits, tagLinks, text, scope)
        DayStreamUiState(
            days = days,
            loading = false,
            importing = busy,
            expandedDays = open,
            // A row that has since been deleted must not stay ticked, or the
            // action bar offers to split payments that are no longer there.
            selected = ticked intersect days.flatMap { day -> day.transactions.map { it.id } }.toSet(),
            // The banner answers "how is the trip going", which is not the
            // question either a search or a breakdown filter is asking.
            trip = if (text.isBlank() && scope == null) tripBanner(days, tags) else null,
            allTags = tags,
            query = text,
            filter = scope
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayStreamUiState())

    /** True when this build can read SMS at all, so the UI can hide the option. */
    val smsSupported: Boolean get() = smsImporter.declaresReadSms()

    /** The SMS permissions this build declares, so the UI asks for all of them at once. */
    val smsPermissions: List<String> get() = smsImporter.declaredSmsPermissions()

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

    /**
     * Renames a payment.
     *
     * With a VPA this writes a rule that is replayed over every payment to the
     * same address; without one there is nothing to generalise from, so only this
     * row changes. Most bank SMS falls in the second case.
     */
    fun rename(txnId: String, displayName: String, applyToSimilar: Boolean = false) {
        val txn = state.value.transaction(txnId) ?: return
        viewModelScope.launch {
            val vpa = txn.counterpartyVpa
            when {
                vpa != null -> {
                    repository.nameMerchant(vpa, displayName)
                    _events.send(DayStreamEvent.Renamed)
                }
                applyToSimilar -> {
                    val direction = if (txn.isCredit) "CREDIT" else "DEBIT"
                    val count = repository.countSimilar(txn.displayName, txn.amountMinor, direction)
                    repository.renameSimilar(txn.displayName, txn.amountMinor, direction, displayName)
                    _events.send(DayStreamEvent.RenamedMany(count))
                }
                else -> {
                    repository.rename(txnId, displayName)
                    _events.send(DayStreamEvent.Renamed)
                }
            }
        }
    }

    /**
     * Attaches a note to a payment.
     *
     * The remark typed in a UPI app never leaves that app - it is in neither the
     * notification nor the bank SMS - so this is the only way that context ever
     * reaches the ledger.
     */
    fun setNote(txnId: String, note: String?) {
        viewModelScope.launch {
            repository.setNote(txnId, note)
            _events.send(DayStreamEvent.NoteSaved)
        }
    }

    /** The newest payment, for the quick-note tile. */
    suspend fun mostRecentId(): String? = repository.mostRecent()?.id

    /** How many unnamed payments the rename sheet would sweep up. */
    suspend fun similarCount(txnId: String): Int {
        val txn = state.value.transaction(txnId) ?: return 0
        if (txn.counterpartyVpa != null) return 0
        val direction = if (txn.isCredit) "CREDIT" else "DEBIT"
        return (repository.countSimilar(txn.displayName, txn.amountMinor, direction) - 1)
            .coerceAtLeast(0)
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
        tagLinks: Map<String, List<TagRef>>,
        query: String,
        filter: StreamFilter?
    ): List<DayUi> {
        val today = Days.localDate(System.currentTimeMillis(), zone)
        val byDate = rows
            .map { it.toUi(splits[it.id], tagLinks[it.id].orEmpty()) }
            .filter { filter == null || filter.matches(it) }
            .filter { it.matches(query) }
            .groupBy { Days.localDate(it.occurredAt, zone) }

        // While searching or filtered, an empty day is noise - the user asked a
        // question and wants the answers, not a calendar with a gap where today
        // would be.
        val dates = if (query.isNotBlank() || filter != null) {
            byDate.keys.sortedDescending()
        } else {
            // Otherwise today always renders even when empty: the empty state
            // lives in the hero, not in place of the whole screen.
            (byDate.keys + today).distinct().sortedDescending()
        }

        return dates.map { date ->
            val transactions = byDate[date].orEmpty().sortedBy { it.occurredAt }
            DayUi(
                date = date,
                spentMinor = transactions
                    .filter { !it.isCredit && !it.failed }
                    .sumOf { it.effectiveMinor },
                receivedMinor = transactions
                    .filter { it.isCredit && !it.failed }
                    .sumOf { it.effectiveMinor },
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
            spentMinor = transactions.filter { !it.isCredit && !it.failed }.sumOf { it.effectiveMinor }
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
        failed = (flags and FusedTxn.FLAG_FAILED.toLong()) != 0L,
        note = note,
        counterpartyVpa = counterparty_vpa,
        channel = channel,
        split = split,
        tags = tags
    )

    /** Grouped so `combine` stays within its five-flow overload. */
    private data class StreamControls(
        val importing: Boolean,
        val expanded: Set<LocalDate>,
        val selected: Set<String>,
        val query: String,
        val filter: StreamFilter?
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
