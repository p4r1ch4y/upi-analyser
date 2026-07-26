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
    QUARTER(90)
}

data class DashboardUiState(
    val range: DashboardRange = DashboardRange.MONTH,
    val loading: Boolean = true,
    val buckets: List<DayBucket> = emptyList(),
    val byMerchant: List<SpendSlice> = emptyList(),
    val byTag: List<SpendSlice> = emptyList(),
    val byChannel: List<SpendSlice> = emptyList(),
    val paymentCount: Int = 0
) {
    val totalSpentMinor: Long get() = SpendSeries.totalSpentMinor(buckets)
    val dailyAverageMinor: Long get() = SpendSeries.dailyAverageMinor(buckets)
    val busiestDay: DayBucket? get() = SpendSeries.busiestDay(buckets)
    val isEmpty: Boolean get() = paymentCount == 0
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

    private val range = MutableStateFlow(DashboardRange.MONTH)

    init {
        viewModelScope.launch {
            range.collectLatest { selected -> load(selected) }
        }
    }

    fun setRange(value: DashboardRange) {
        range.value = value
    }

    /** Recomputes after a split or tag changes what the numbers mean. */
    fun refresh() {
        viewModelScope.launch { load(range.value) }
    }

    private suspend fun load(selected: DashboardRange) {
        _state.value = _state.value.copy(range = selected, loading = true)

        val end = today()
        val start = end.minusDays(selected.days - 1)
        val since = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val events = annotations.spendPoints(since, until)

        _state.value = DashboardUiState(
            range = selected,
            loading = false,
            buckets = SpendSeries.byDay(events, from = start, to = end, zone = zone),
            byMerchant = annotations.spendByMerchant(since, until),
            byTag = annotations.spendByTag(since, until),
            byChannel = annotations.spendByChannel(since, until),
            paymentCount = events.count { !it.isCredit }
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
