package com.spendlens.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.Alignment
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

/** Every control the report exposes, so the screen stays a pure function of state. */
data class DashboardActions(
    val onRange: (DashboardRange) -> Unit = {},
    val onDirection: (SpendDirection) -> Unit = {},
    val onGroupBy: (GroupBy) -> Unit = {},
    val onSortBy: (SortBy) -> Unit = {}
)

/**
 * Insights.
 *
 * The filter row mirrors what a conventional tracker puts at the top of a report
 * — what kind of money, over what period, grouped how — but the grouping options
 * are the ones this app can actually stand behind. There is no category model to
 * pick from: a merchant name resolved from the payment itself is more specific
 * and needs no upkeep, and a tag is a category the user genuinely chose.
 *
 * One mark language throughout: magnitude is length, colour is only ever
 * emphasis, and every row is direct-labelled with its amount and share.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
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
        // ------------------------------------------------------------- filters
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(stringResource(R.string.dash_expense), state.direction == SpendDirection.EXPENSE) {
                actions.onDirection(SpendDirection.EXPENSE)
            }
            Chip(stringResource(R.string.dash_income), state.direction == SpendDirection.INCOME) {
                actions.onDirection(SpendDirection.INCOME)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(stringResource(R.string.dash_range_week), state.range == DashboardRange.WEEK) {
                actions.onRange(DashboardRange.WEEK)
            }
            Chip(stringResource(R.string.dash_range_month), state.range == DashboardRange.MONTH) {
                actions.onRange(DashboardRange.MONTH)
            }
            Chip(stringResource(R.string.dash_range_quarter), state.range == DashboardRange.QUARTER) {
                actions.onRange(DashboardRange.QUARTER)
            }
            Chip(stringResource(R.string.dash_range_year), state.range == DashboardRange.YEAR) {
                actions.onRange(DashboardRange.YEAR)
            }
        }

        // ------------------------------------------------------------ headline
        Text(
            text = MoneyFormat.rupees(state.headlineMinor),
            style = typography.displayLarge,
            color = if (state.direction == SpendDirection.INCOME) colors.credit else colors.ink,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = stringResource(
                if (state.direction == SpendDirection.EXPENSE) R.string.dash_spent_over
                else R.string.dash_received_over,
                state.headlineCount,
                state.range.days
            ),
            style = typography.bodySmall,
            color = colors.graphite
        )

        if (state.isEmpty) {
            ChartEmpty(stringResource(R.string.dash_nothing), Modifier.padding(top = 24.dp))
            Spacer(Modifier.navigationBarsPadding().height(80.dp))
            return@Column
        }

        // -------------------------------------------------------- balance card
        BalanceCard(state, modifier = Modifier.padding(top = 20.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
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

        // ------------------------------------------------------------- by day
        Section(stringResource(R.string.dash_by_day)) {
            val values = state.buckets.map {
                if (state.direction == SpendDirection.EXPENSE) it.spentMinor else it.receivedMinor
            }
            val peakIndex = values.indices.maxByOrNull { values[it] }
            val todayIndex = state.buckets.indexOfFirst { it.date == LocalDate.now() }
                .takeIf { it >= 0 }

            SpendColumns(values = values, emphasisIndex = peakIndex, accentIndex = todayIndex)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(state.buckets.first().date.format(DAY_FORMAT), style = typography.labelSmall, color = colors.mist)
                Text(state.buckets.last().date.format(DAY_FORMAT), style = typography.labelSmall, color = colors.mist)
            }
        }

        // ----------------------------------------------------------- breakdown
        Section(stringResource(R.string.dash_breakdown)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.group_merchant), state.groupBy == GroupBy.MERCHANT) {
                    actions.onGroupBy(GroupBy.MERCHANT)
                }
                // Offered only once something is tagged: an empty report would
                // otherwise look like a bug rather than an empty category.
                if (state.hasTags || state.groupBy == GroupBy.TAG) {
                    Chip(stringResource(R.string.group_tag), state.groupBy == GroupBy.TAG) {
                        actions.onGroupBy(GroupBy.TAG)
                    }
                }
                Chip(stringResource(R.string.group_channel), state.groupBy == GroupBy.CHANNEL) {
                    actions.onGroupBy(GroupBy.CHANNEL)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.dash_sort_by),
                    style = typography.labelSmall,
                    color = colors.graphite
                )
                SortChip(stringResource(R.string.sort_amount), state.sortBy == SortBy.AMOUNT) {
                    actions.onSortBy(SortBy.AMOUNT)
                }
                SortChip(stringResource(R.string.sort_count), state.sortBy == SortBy.COUNT) {
                    actions.onSortBy(SortBy.COUNT)
                }
                SortChip(stringResource(R.string.sort_name), state.sortBy == SortBy.NAME) {
                    actions.onSortBy(SortBy.NAME)
                }
            }

            val rows = state.sortedSlices
            if (rows.isEmpty()) {
                ChartEmpty(stringResource(R.string.dash_nothing_grouped), Modifier.padding(top = 8.dp))
            } else {
                RankedBars(
                    data = rows.mapIndexed { index, slice ->
                        BarDatum(
                            label = if (state.groupBy == GroupBy.CHANNEL) channelLabel(slice.label) else slice.label,
                            valueMinor = slice.amountMinor,
                            caption = pluralPayments(slice.count),
                            // Emphasis follows the largest bar, which is only the
                            // first row when sorting by amount.
                            emphasis = state.sortBy == SortBy.AMOUNT && index == 0,
                            share = state.shareOf(slice)
                        )
                    },
                    maxRows = 20,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
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

/**
 * In, out, and what is left.
 *
 * Deliberately three lines rather than the four a bank statement uses: SpendLens
 * sees payments, not balances, so it cannot honestly print an opening or closing
 * balance. Claiming one would mean inventing a starting figure nobody gave it.
 */
@Composable
private fun BalanceCard(state: DashboardUiState, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val net = state.netMinor

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
    ) {
        BalanceRow(
            label = stringResource(R.string.dash_income),
            amount = MoneyFormat.rupees(state.totalReceivedMinor),
            sign = "+",
            signColor = colors.credit
        )
        BalanceRow(
            label = stringResource(R.string.dash_expense),
            amount = MoneyFormat.rupees(state.totalSpentMinor),
            sign = "−",
            signColor = colors.review,
            topRule = true
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.paperSunk)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(if (net >= 0) R.string.dash_net_out else R.string.dash_net_in),
                style = typography.bodySmall,
                color = colors.ink
            )
            Text(
                text = MoneyFormat.rupees(kotlin.math.abs(net)),
                style = typography.displaySmall,
                color = if (net >= 0) colors.ink else colors.credit
            )
        }
    }
}

@Composable
private fun BalanceRow(
    label: String,
    amount: String,
    sign: String,
    signColor: androidx.compose.ui.graphics.Color,
    topRule: Boolean = false
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (topRule) Modifier.border(0.dp, colors.rule) else Modifier)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(label, style = typography.bodySmall, color = colors.graphite)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(amount, style = typography.bodyMedium, color = colors.ink)
            Text(sign, style = typography.bodySmall, color = signColor)
        }
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
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.ink,
        modifier = Modifier
            .background(if (selected) colors.ink else colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/** Quieter than a filter chip: sorting reorders, it does not change the numbers. */
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) colors.ink else colors.mist,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

private fun pluralPayments(count: Int): String =
    if (count == 1) "1 payment" else "$count payments"

/**
 * Rail names as people say them. The app never renders the literal "Unknown" —
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
