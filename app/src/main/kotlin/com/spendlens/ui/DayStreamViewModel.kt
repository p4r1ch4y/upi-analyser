package com.spendlens.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spendlens.SpendLensApp
import com.spendlens.core.database.Transactions
import com.spendlens.core.model.Direction
import com.spendlens.core.model.FusedTxn
import com.spendlens.data.CsvStatementImporter
import com.spendlens.data.Days
import com.spendlens.data.SmsInboxImporter
import com.spendlens.data.TransactionIngestor
import com.spendlens.data.TransactionRepository
import com.spendlens.service.UpiNotificationListener
import com.spendlens.ui.entry.ManualEntry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** One payment, already shaped for the row that renders it. */
data class TxnUi(
    val id: String,
    val occurredAt: Long,
    val displayName: String,
    val amountMinor: Long,
    val currency: String,
    val isCredit: Boolean,
    val needsReview: Boolean,
    val counterpartyVpa: String?
)

/** A calendar day of payments, newest day first. */
data class DayUi(
    val date: LocalDate,
    val spentMinor: Long,
    val transactions: List<TxnUi>
) {
    val tapCount: Int get() = transactions.size
    val merchantCount: Int get() = transactions.map { it.displayName }.distinct().size
}

data class DayStreamUiState(
    val days: List<DayUi> = emptyList(),
    val loading: Boolean = true,
    /** True while an import is running, so the UI can block a second one. */
    val importing: Boolean = false
) {
    val today: DayUi? get() = days.firstOrNull()
    val earlier: List<DayUi> get() = days.drop(1)
}

/** One-shot feedback. Typed rather than pre-formatted so the UI owns the wording. */
sealed interface DayStreamEvent {
    data class Imported(val summary: TransactionIngestor.BatchSummary) : DayStreamEvent
    data object TransactionAdded : DayStreamEvent
    data object ListenerNotConnected : DayStreamEvent
    data object SmsUnavailable : DayStreamEvent
    data class Failed(val reason: String?) : DayStreamEvent
}

class DayStreamViewModel(
    private val repository: TransactionRepository,
    private val ingestor: TransactionIngestor,
    private val smsImporter: SmsInboxImporter,
    private val csvImporter: CsvStatementImporter,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val importing = MutableStateFlow(false)

    private val _events = Channel<DayStreamEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val state: StateFlow<DayStreamUiState> =
        repository.transactionsSince(Days.startOfToday(zone) - HISTORY_MILLIS)
            .map { rows -> DayStreamUiState(days = groupByDay(rows), loading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayStreamUiState())

    val isImporting: StateFlow<Boolean> = importing.asStateFlow()

    /** True when this build can read SMS at all, so the UI can hide the option. */
    val smsSupported: Boolean get() = smsImporter.declaresReadSms()

    // --------------------------------------------------------------- corrections

    /**
     * Naming a merchant is a rule, not an edit: it is stored and replayed over
     * every past payment to the same VPA.
     */
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

    /** Re-reads whatever is currently in the notification tray. */
    fun rescanNotifications() {
        if (!UpiNotificationListener.rescanTray()) {
            viewModelScope.launch { _events.send(DayStreamEvent.ListenerNotConnected) }
        }
    }

    /** Pulls transaction messages out of the SMS inbox, however far back they go. */
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

    private fun groupByDay(rows: List<Transactions>): List<DayUi> {
        val today = Days.localDate(System.currentTimeMillis(), zone)
        val byDate = rows
            .map { it.toUi() }
            .groupBy { Days.localDate(it.occurredAt, zone) }

        // Today always renders, even with nothing in it - the empty state lives
        // in the hero, not in place of the whole screen.
        val dates = (byDate.keys + today).distinct().sortedDescending()

        return dates.map { date ->
            val transactions = byDate[date].orEmpty().sortedBy { it.occurredAt }
            DayUi(
                date = date,
                spentMinor = transactions.filter { !it.isCredit }.sumOf { it.amountMinor },
                transactions = transactions
            )
        }
    }

    private fun Transactions.toUi() = TxnUi(
        id = id,
        occurredAt = occurred_at,
        displayName = display_name,
        amountMinor = amount_minor,
        currency = currency,
        isCredit = direction == Direction.CREDIT.name,
        needsReview = (flags and FusedTxn.FLAG_NEEDS_REVIEW.toLong()) != 0L,
        counterpartyVpa = counterparty_vpa
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
                        csvImporter = graph.csvImporter
                    ) as T
            }
    }
}
