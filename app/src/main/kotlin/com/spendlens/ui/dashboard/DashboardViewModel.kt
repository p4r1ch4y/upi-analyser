package com.spendlens.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spendlens.SpendLensApp
import com.spendlens.core.model.BudgetPeriod
import com.spendlens.core.model.BudgetProgress
import com.spendlens.core.model.BudgetScope
import com.spendlens.core.model.DayBucket
import com.spendlens.core.model.MonthBucket
import com.spendlens.core.model.SpendSeries
import com.spendlens.data.BudgetRepository
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

/**
 * An explicit start and end the user picked.
 *
 * The preset chips answer "recently"; this answers "that trip", "last April",
 * "between the two salary dates". Both ends inclusive, because that is how a
 * person reads a date range they typed themselves.
 */
data class DateWindow(val start: LocalDate, val end: LocalDate) {
    val days: Long get() = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1
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
enum class GroupBy {
    MERCHANT,
    TAG,
    CHANNEL,

    /**
     * By the amount itself, most frequent first.
     *
     * The one grouping that answers "how many times have I spent ₹100?" A ledger
     * of everyday payments is mostly a short list of prices repeated - the same
     * chai, the same auto fare, the same subscription - and "₹45, forty times" is
     * a different and more actionable fact than "₹1,800 went to that shop".
     */
    AMOUNT;

    /**
     * The sort this grouping is worth reading in.
     *
     * Amount-frequency is about the *count*; ranking it by total would just
     * rebuild the merchant chart with worse labels.
     */
    val defaultSort: SortBy get() = if (this == AMOUNT) SortBy.COUNT else SortBy.AMOUNT
}

enum class SortBy { AMOUNT, COUNT, NAME }

/**
 * A bar the user tapped, carrying everything needed to open the payments behind
 * it - including the window, so the stream it lands on counts the same rows.
 */
data class SliceSelection(
    val groupBy: GroupBy,
    /** The stored value the bar aggregates, which is not always what it reads. */
    val key: String,
    val label: String,
    val sinceMillis: Long,
    val untilMillis: Long,
    /** The report was showing income, so the bar is about credits, not payments. */
    val credits: Boolean
)

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
    val hasTags: Boolean = false,
    /** The same span immediately before, for the comparison line. */
    val previous: List<DayBucket> = emptyList(),
    /** Limits the user set, each with its own window and what has gone into it. */
    val budgets: List<BudgetProgress> = emptyList(),
    /**
     * A year of months, oldest first.
     *
     * Independent of the range chips on purpose. "How does this month compare to
     * the last twelve" is a different question from "what did I spend in the last
     * 7 days", and re-scoping it to the chip would make the comparison vanish
     * exactly when someone narrows the range to look closely at something.
     */
    val months: List<MonthBucket> = emptyList(),
    /** The window the report currently covers, so a tapped bar can carry it. */
    val sinceMillis: Long = 0L,
    val untilMillis: Long = 0L,
    /** Set when the user picked their own dates; the range chips then defer to it. */
    val custom: DateWindow? = null
) {
    /** How many days the report actually covers, preset or picked. */
    val rangeDays: Long get() = custom?.days ?: range.days
    /** Everything needed to open the stream on one bar of the breakdown. */
    fun selectionFor(key: String, label: String) = SliceSelection(
        groupBy = groupBy,
        key = key,
        label = label,
        sinceMillis = sinceMillis,
        untilMillis = untilMillis,
        credits = direction == SpendDirection.INCOME
    )

    /**
     * Budgets worth interrupting the report for, loudest first.
     *
     * An untouched budget is real but is not news, so it sorts last rather than
     * being hidden - a budget that disappears when nothing has been spent against
     * it looks deleted.
     */
    val rankedBudgets: List<BudgetProgress>
        get() = budgets.sortedWith(
            compareBy({ it.state.ordinal }, { -it.fraction })
        )

    /**
     * True while the report is about money coming in.
     *
     * Every summary below reads this. The direction chips used to flip only the
     * headline and the breakdown, leaving the comparison line and all four stat
     * tiles reporting *spending* underneath an income headline - "₹71,104 income,
     * biggest day ₹1,50,000, days you spent nothing: 225". Numbers that look like
     * answers and are about something else.
     */
    private val credits: Boolean get() = direction == SpendDirection.INCOME

    val change: SpendSeries.Change?
        get() = SpendSeries.changeVsPrevious(buckets, previous, credits)

    val spendFreeDays: Int get() = SpendSeries.spendFreeDays(buckets, credits)
    val averageOnSpendingDays: Long get() = SpendSeries.averageOnSpendingDays(buckets, credits)

    /**
     * The single largest payment in the window.
     *
     * Separate from the biggest *day*: one ₹8,000 rent payment and a day of forty
     * small ones look identical in a daily total and are completely different
     * facts about how someone spends.
     */
    val biggestSlice: SpendSlice? get() = slices.maxByOrNull { it.amountMinor }

    val totalSpentMinor: Long get() = SpendSeries.totalSpentMinor(buckets)
    val totalReceivedMinor: Long get() = buckets.sumOf { it.receivedMinor }
    val netMinor: Long get() = SpendSeries.netMinor(buckets)
    val dailyAverageMinor: Long get() = SpendSeries.dailyAverageMinor(buckets, credits)
    val busiestDay: DayBucket? get() = SpendSeries.busiestDay(buckets, credits)

    /** The busiest day's figure on the side the report is currently about. */
    val busiestDayMinor: Long
        get() = busiestDay?.let { SpendSeries.amountOf(it, credits) } ?: 0L

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
            // Amount labels are numbers wearing a string, so sorting them
            // alphabetically would file ₹1,000 before ₹45.
            SortBy.NAME -> if (groupBy == GroupBy.AMOUNT) {
                slices.sortedBy { it.label.toLongOrNull() ?: Long.MAX_VALUE }
            } else {
                slices.sortedBy { it.label.lowercase() }
            }
        }

    /**
     * Share of the group total, not of everything spent. Grouping by tag only
     * covers payments that carry a tag, so dividing by the overall total would
     * make every bar look small for a reason the reader cannot see.
     */
    fun shareOf(slice: SpendSlice): Float {
        // Grouped by amount the bar's magnitude is how *often*, so the share
        // beside it has to be a share of payments too. A bar measuring frequency
        // next to a percentage measuring money contradicts itself on every row.
        if (groupBy == GroupBy.AMOUNT) {
            val payments = slices.sumOf { it.count }
            return if (payments <= 0) 0f else slice.count.toFloat() / payments
        }
        val total = groupTotalMinor
        return if (total <= 0L) 0f else (slice.amountMinor.toDouble() / total).toFloat()
    }

    /**
     * The denominator every share on the breakdown is a fraction of.
     *
     * Shown on screen, because a bar reading "75%" of a number that appears
     * nowhere is not a fact the reader can check. Grouping by tag makes the point:
     * this is the total of *tagged* payments, deliberately not the headline.
     */
    val groupTotalMinor: Long get() = slices.sumOf { it.amountMinor }
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
    private val budgetRepository: BudgetRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) }
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    private data class Query(
        val range: DashboardRange = DashboardRange.MONTH,
        val direction: SpendDirection = SpendDirection.EXPENSE,
        val groupBy: GroupBy = GroupBy.MERCHANT,
        val sortBy: SortBy = SortBy.AMOUNT,
        val custom: DateWindow? = null
    )

    private val query = MutableStateFlow(Query())

    init {
        viewModelScope.launch {
            // collectLatest so rapid filter taps cancel the in-flight read rather
            // than racing each other onto the screen.
            query.collectLatest { load(it) }
        }
    }

    /** Picking a preset drops any custom window - they are two answers to one question. */
    fun setRange(value: DashboardRange) {
        query.value = query.value.copy(range = value, custom = null)
    }

    /**
     * Reports over dates the user chose. Ends are swapped if they arrive
     * backwards, because a picker that returns them out of order should not
     * silently produce an empty report.
     */
    fun setCustomRange(start: LocalDate, end: LocalDate) {
        val window = if (end.isBefore(start)) DateWindow(end, start) else DateWindow(start, end)
        query.value = query.value.copy(custom = window)
    }

    fun clearCustomRange() { query.value = query.value.copy(custom = null) }
    fun setDirection(value: SpendDirection) { query.value = query.value.copy(direction = value) }
    /** Switching grouping also switches to the sort that grouping is worth reading in. */
    fun setGroupBy(value: GroupBy) {
        query.value = query.value.copy(groupBy = value, sortBy = value.defaultSort)
    }

    /** Sorting is a pure reshuffle of what is already loaded, so it never re-queries. */
    fun setSortBy(value: SortBy) {
        query.value = query.value.copy(sortBy = value)
        _state.value = _state.value.copy(sortBy = value)
    }

    /** Recomputes after a split or tag changes what the numbers mean. */
    fun refresh() {
        viewModelScope.launch { load(query.value) }
    }

    // ---------------------------------------------------------------- budgets

    /**
     * Creates or updates a budget. A null [id] creates; anything else edits in
     * place, which deliberately keeps the original anchor so that raising a limit
     * mid-month does not restart the month under the user.
     */
    fun saveBudget(
        id: String?,
        name: String,
        scope: BudgetScope,
        scopeValue: String?,
        period: BudgetPeriod,
        limitMinor: Long,
        currency: String
    ) {
        viewModelScope.launch {
            runCatching {
                budgetRepository.save(id, name, scope, scopeValue, period, limitMinor, currency)
            }
            refresh()
        }
    }

    fun deleteBudget(id: String) {
        viewModelScope.launch {
            budgetRepository.delete(id)
            refresh()
        }
    }

    /**
     * What this scope actually cost over the last whole period.
     *
     * Offered as the starting figure in the budget sheet. A blank amount field is
     * why budget features go unused: nobody knows what a reasonable limit is for
     * themselves until they are shown one, and the only honest source is what
     * they already did.
     */
    suspend fun suggestedLimitMinor(
        scope: BudgetScope,
        scopeValue: String?,
        period: BudgetPeriod
    ): Long = budgetRepository.lastPeriodSpendMinor(scope, scopeValue, period, today())

    /**
     * Names the budget sheet can offer, biggest spend first.
     *
     * Read fresh rather than taken from the breakdown on screen, because that is
     * grouped by whatever the user last picked - offering channel names as
     * merchants to budget against would be nonsense.
     */
    suspend fun budgetableNames(scope: BudgetScope): List<String> {
        val end = today()
        val start = end.minusDays(SUGGESTION_WINDOW_DAYS - 1)
        val since = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return when (scope) {
            BudgetScope.MERCHANT -> annotations.spendByMerchant(since, until, "DEBIT", limit = 30)
            BudgetScope.TAG -> annotations.spendByTag(since, until, "DEBIT", limit = 30)
            BudgetScope.TOTAL -> emptyList()
        }.map { it.label }
    }

    private suspend fun load(q: Query) {
        _state.value = _state.value.copy(
            range = q.range, direction = q.direction, groupBy = q.groupBy, sortBy = q.sortBy,
            custom = q.custom, loading = true
        )

        // A picked window wins over the chips. Its end is clamped to today so a
        // range chosen into the future does not report an average over days that
        // have not happened.
        val end = q.custom?.end?.coerceAtMost(today()) ?: today()
        val start = q.custom?.start ?: end.minusDays(q.range.days - 1)
        val since = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = annotations.spendPoints(since, until)
        val dir = if (q.direction == SpendDirection.EXPENSE) "DEBIT" else "CREDIT"

        // A year of months, read on its own window rather than the range chips'.
        val yearStart = end.withDayOfMonth(1).minusMonths(MONTHS_COMPARED - 1L)
        val monthEvents = annotations.spendPoints(
            yearStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            until
        )

        // The same span again, immediately before, so the headline can say which
        // way things are going rather than just how much.
        val prevEnd = start.minusDays(1)
        val prevStart = prevEnd.minusDays(q.range.days - 1)
        val prevEvents = annotations.spendPoints(
            prevStart.atStartOfDay(zone).toInstant().toEpochMilli(),
            since
        )

        val slices = when (q.groupBy) {
            GroupBy.MERCHANT -> annotations.spendByMerchant(since, until, dir)
            GroupBy.TAG -> annotations.spendByTag(since, until, dir)
            GroupBy.CHANNEL -> annotations.spendByChannel(since, until, dir)
            GroupBy.AMOUNT -> annotations.spendByAmount(since, until, dir)
        }

        _state.value = DashboardUiState(
            range = q.range,
            direction = q.direction,
            groupBy = q.groupBy,
            sortBy = q.sortBy,
            custom = q.custom,
            loading = false,
            buckets = SpendSeries.byDay(events, from = start, to = end, zone = zone),
            slices = slices,
            paymentCount = events.count { !it.isCredit },
            creditCount = events.count { it.isCredit },
            // Grouping by tag is only offered once something is tagged; an empty
            // report would otherwise look like a bug.
            hasTags = annotations.spendByTag(since, until, dir).isNotEmpty(),
            previous = SpendSeries.byDay(prevEvents, from = prevStart, to = prevEnd, zone = zone),
            // Budgets carry their own windows and ignore the range chips above
            // them entirely: a monthly limit measured over "last 7 days" would be
            // a different number every time someone tapped a filter.
            budgets = runCatching { budgetRepository.progress(end) }.getOrDefault(emptyList()),
            months = SpendSeries.byMonth(
                monthEvents,
                monthsBack = MONTHS_COMPARED,
                endingIn = end,
                zone = zone
            ),
            sinceMillis = since,
            untilMillis = until
        )
    }

    companion object {
        /** How far back the budget sheet looks for names worth budgeting against. */
        private const val SUGGESTION_WINDOW_DAYS = 90L

        /** A year, so the same month last year is on screen beside this one. */
        private const val MONTHS_COMPARED = 12

        fun factory(graph: SpendLensApp.Graph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DashboardViewModel(
                        annotations = graph.annotations,
                        budgetRepository = graph.budgets
                    ) as T
            }
    }
}
