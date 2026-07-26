package com.spendlens.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spendlens.SpendLensApp
import com.spendlens.core.model.DayBucket
import com.spendlens.core.model.SpendSeries
import com.spendlens.data.SpendSlice
import com.spendlens.data.SplitAndTagRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** How far back the dashboard looks. */
enum class DashboardRange(val days: Long) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    YEAR(365)
}

/** Money out, or money in. The whole report flips. */
enum class SpendDirection { EXPENSE, INCOME }

/**
 * What the report groups by.
 *
 * This is the equivalent of "by category" in a conventional tracker. SpendLens
 * has no category model: a merchant name resolved from the payment itself is more
 * specific and needs no upkeep, and a tag is a category the user actually chose.
 */
enum class GroupBy { MERCHANT, TAG, CHANNEL }

enum class SortBy { AMOUNT, COUNT, NAME }

data class DashboardUiState(
    val range: DashboardRange = DashboardRange.MONTH,
    val direction: SpendDirection = SpendDirection.EXPENSE,
    val groupBy: GroupBy = GroupBy.MERCHANT,
    val sortBy: SortBy = SortBy.AMOUNT,
    val loading: Boolean = true,
    val buckets: List<DayBucket> = emptyList(),
    val slices: List<SpendSlice> = emptyList(),
    val paymentCount: Int = 0,
    val creditCount: Int = 0,
    val hasTags: Boolean = false
) {
    val totalSpentMinor: Long get() = SpendSeries.totalSpentMinor(buckets)
    val totalReceivedMinor: Long get() = buckets.sumOf { it.receivedMinor }
    val netMinor: Long get() = SpendSeries.netMinor(buckets)
    val dailyAverageMinor: Long get() = SpendSeries.dailyAverageMinor(buckets)
    val busiestDay: DayBucket? get() = SpendSeries.busiestDay(buckets)

    /** The figure the report is currently about. */
    val headlineMinor: Long
        get() = if (direction == SpendDirection.EXPENSE) totalSpentMinor else totalReceivedMinor

    val headlineCount: Int
        get() = if (direction == SpendDirection.EXPENSE) paymentCount else creditCount

    val isEmpty: Boolean get() = paymentCount == 0 && creditCount == 0

    /** Slices ordered by the current sort, each carrying its share of the total. */
    val sortedSlices: List<SpendSlice>
        get() = when (sortBy) {
            SortBy.AMOUNT -> slices.sortedByDescending { it.amountMinor }
            SortBy.COUNT -> slices.sortedByDescending { it.count }
            SortBy.NAME -> slices.sortedBy { it.label.lowercase() }
        }

    /**
     * Share of the group total, not of everything spent. Grouping by tag only
     * covers payments that carry a tag, so dividing by the overall total would
     * make every bar look small for a reason the reader cannot see.
     */
    fun shareOf(slice: SpendSlice): Float {
        val total = slices.sumOf { it.amountMinor }
        return if (total <= 0L) 0f else (slice.amountMinor.toDouble() / total).toFloat()
    }
}

/**
 * The analytics screen.
 *
 * Every number here is the user's *own* cost: a split payment contributes their
 * share, not the amount that left their account. Anything else would tell someone
 * who fronts group dinners that they have a restaurant problem.
 */
class DashboardViewModel(
    private val annotations: SplitAndTagRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) }
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private data class Query(
        val range: DashboardRange = DashboardRange.MONTH,
        val direction: SpendDirection = SpendDirection.EXPENSE,
        val groupBy: GroupBy = GroupBy.MERCHANT,
        val sortBy: SortBy = SortBy.AMOUNT
    )

    private val query = MutableStateFlow(Query())

    init {
        viewModelScope.launch {
            // collectLatest so rapid filter taps cancel the in-flight read rather
            // than racing each other onto the screen.
            query.collectLatest { load(it) }
        }
    }

    fun setRange(value: DashboardRange) { query.value = query.value.copy(range = value) }
    fun setDirection(value: SpendDirection) { query.value = query.value.copy(direction = value) }
    fun setGroupBy(value: GroupBy) { query.value = query.value.copy(groupBy = value) }

    /** Sorting is a pure reshuffle of what is already loaded, so it never re-queries. */
    fun setSortBy(value: SortBy) {
        query.value = query.value.copy(sortBy = value)
        _state.value = _state.value.copy(sortBy = value)
    }

    /** Recomputes after a split or tag changes what the numbers mean. */
    fun refresh() {
        viewModelScope.launch { load(query.value) }
    }

    private suspend fun load(q: Query) {
        _state.value = _state.value.copy(
            range = q.range, direction = q.direction, groupBy = q.groupBy, sortBy = q.sortBy,
            loading = true
        )

        val end = today()
        val start = end.minusDays(q.range.days - 1)
        val since = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = annotations.spendPoints(since, until)
        val dir = if (q.direction == SpendDirection.EXPENSE) "DEBIT" else "CREDIT"

        val slices = when (q.groupBy) {
            GroupBy.MERCHANT -> annotations.spendByMerchant(since, until, dir)
            GroupBy.TAG -> annotations.spendByTag(since, until, dir)
            GroupBy.CHANNEL -> annotations.spendByChannel(since, until, dir)
        }

        _state.value = DashboardUiState(
            range = q.range,
            direction = q.direction,
            groupBy = q.groupBy,
            sortBy = q.sortBy,
            loading = false,
            buckets = SpendSeries.byDay(events, from = start, to = end, zone = zone),
            slices = slices,
            paymentCount = events.count { !it.isCredit },
            creditCount = events.count { it.isCredit },
            // Grouping by tag is only offered once something is tagged; an empty
            // report would otherwise look like a bug.
            hasTags = annotations.spendByTag(since, until, dir).isNotEmpty()
        )
    }

    companion object {
        fun factory(graph: SpendLensApp.Graph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(annotations = graph.annotations) as T
            }
    }
}
