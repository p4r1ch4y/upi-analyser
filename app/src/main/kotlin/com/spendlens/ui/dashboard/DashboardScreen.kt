package com.spendlens.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.MoneyFormat
import com.spendlens.ui.charts.BarDatum
import com.spendlens.ui.charts.ChartEmpty
import com.spendlens.ui.charts.RankedBars
import com.spendlens.ui.charts.SpendColumns
import com.spendlens.ui.charts.StatTile
import com.spendlens.ui.theme.SpendTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * Insights.
 *
 * Deliberately four comparisons and a headline rather than a wall of widgets. Each
 * block answers one question - how much, when, where, on what, and how - and every
 * chart is the same ranked-bar grammar, so nothing has to be decoded twice.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onRangeChange: (DashboardRange) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RangeChip(R.string.dash_range_week, state.range == DashboardRange.WEEK) {
                onRangeChange(DashboardRange.WEEK)
            }
            RangeChip(R.string.dash_range_month, state.range == DashboardRange.MONTH) {
                onRangeChange(DashboardRange.MONTH)
            }
            RangeChip(R.string.dash_range_quarter, state.range == DashboardRange.QUARTER) {
                onRangeChange(DashboardRange.QUARTER)
            }
        }

        // The headline is a figure, not a one-bar chart: a single value is not a
        // comparison and does not deserve an axis.
        Text(
            text = MoneyFormat.rupees(state.totalSpentMinor),
            style = typography.displayLarge,
            color = colors.ink,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = stringResource(R.string.dash_payments) + " · ${state.paymentCount}",
            style = typography.bodySmall,
            color = colors.graphite
        )

        if (state.isEmpty) {
            ChartEmpty(stringResource(R.string.dash_nothing), Modifier.padding(top = 24.dp))
            Spacer(Modifier.navigationBarsPadding().height(80.dp))
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatTile(
                value = MoneyFormat.rupees(state.dailyAverageMinor),
                caption = stringResource(R.string.dash_daily_average)
            )
            state.busiestDay?.let { busiest ->
                StatTile(
                    value = MoneyFormat.rupees(busiest.spentMinor),
                    caption = stringResource(R.string.dash_busiest_day) +
                        " · " + busiest.date.format(DAY_FORMAT)
                )
            }
        }

        Section(stringResource(R.string.dash_by_day)) {
            val values = state.buckets.map { it.spentMinor }
            val peakIndex = values.indices.maxByOrNull { values[it] }
            val todayIndex = state.buckets.indexOfFirst { it.date == LocalDate.now() }
                .takeIf { it >= 0 }

            SpendColumns(
                values = values,
                emphasisIndex = peakIndex,
                accentIndex = todayIndex
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.buckets.first().date.format(DAY_FORMAT),
                    style = typography.labelSmall,
                    color = colors.mist
                )
                Text(
                    text = state.buckets.last().date.format(DAY_FORMAT),
                    style = typography.labelSmall,
                    color = colors.mist
                )
            }
        }

        Section(stringResource(R.string.dash_top_merchants)) {
            RankedBars(
                data = state.byMerchant.mapIndexed { index, slice ->
                    BarDatum(
                        label = slice.label,
                        valueMinor = slice.amountMinor,
                        caption = pluralPayments(slice.count),
                        emphasis = index == 0
                    )
                }
            )
        }

        if (state.byTag.isNotEmpty()) {
            Section(stringResource(R.string.dash_by_tag)) {
                RankedBars(
                    data = state.byTag.map {
                        BarDatum(it.label, it.amountMinor, pluralPayments(it.count))
                    }
                )
            }
        }

        Section(stringResource(R.string.dash_by_channel)) {
            RankedBars(
                data = state.byChannel.map {
                    BarDatum(channelLabel(it.label), it.amountMinor, pluralPayments(it.count))
                }
            )
        }

        Text(
            text = stringResource(R.string.dash_share_note),
            style = typography.labelSmall,
            color = colors.mist,
            modifier = Modifier.padding(top = 24.dp)
        )

        Spacer(Modifier.navigationBarsPadding().height(80.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = SpendTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = colors.graphite,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        content()
    }
}

@Composable
private fun RangeChip(labelRes: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.ink,
        modifier = Modifier
            .background(if (selected) colors.ink else colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

private fun pluralPayments(count: Int): String =
    if (count == 1) "1 payment" else "$count payments"

/**
 * Rail names as people say them. The app never renders the literal "Unknown" -
 * a message that did not name its rail is honestly "Other", not a gap.
 */
private fun channelLabel(raw: String): String = when (raw.uppercase(Locale.ROOT)) {
    "UPI" -> "UPI"
    "ATM" -> "ATM"
    "NEFT" -> "NEFT"
    "IMPS" -> "IMPS"
    "RTGS" -> "RTGS"
    "CARD" -> "Card"
    "CASH" -> "Cash"
    else -> "Other"
}
