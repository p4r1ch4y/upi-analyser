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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.BudgetProgress
import com.spendlens.core.model.MonthBucket
import com.spendlens.ui.charts.BarDatum
import com.spendlens.ui.charts.ChartEmpty
import com.spendlens.ui.charts.RankedBars
import com.spendlens.ui.charts.SpendColumns
import com.spendlens.ui.charts.StatTile
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Every control the report exposes, so the screen stays a pure function of state. */
data class DashboardActions(
    val onRange: (DashboardRange) -> Unit = {},
    val onDirection: (SpendDirection) -> Unit = {},
    val onGroupBy: (GroupBy) -> Unit = {},
    val onSortBy: (SortBy) -> Unit = {},
    /** The user wants to pick their own start and end. */
    val onPickRange: () -> Unit = {},
    /** A bar was tapped: open the payments behind it. */
    val onOpenSlice: (SliceSelection) -> Unit = {},
    val onOpenBudget: (BudgetProgress) -> Unit = {},
    val onEditBudget: (BudgetProgress) -> Unit = {},
    val onNewBudget: () -> Unit = {},
    /** A single day on the spend-by-day chart, or a whole month on the comparison. */
    val onOpenDay: (LocalDate) -> Unit = {},
    val onOpenMonth: (MonthBucket) -> Unit = {}
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
    val income = state.direction == SpendDirection.INCOME

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
            Chip(stringResource(R.string.dash_range_week), state.range == DashboardRange.WEEK && state.custom == null) {
                actions.onRange(DashboardRange.WEEK)
            }
            Chip(stringResource(R.string.dash_range_month), state.range == DashboardRange.MONTH && state.custom == null) {
                actions.onRange(DashboardRange.MONTH)
            }
            Chip(stringResource(R.string.dash_range_quarter), state.range == DashboardRange.QUARTER && state.custom == null) {
                actions.onRange(DashboardRange.QUARTER)
            }
            Chip(stringResource(R.string.dash_range_year), state.range == DashboardRange.YEAR && state.custom == null) {
                actions.onRange(DashboardRange.YEAR)
            }
        }

        // A picked window, shown as its own chip rather than hidden behind the
        // presets. The chips answer "recently"; this answers "that trip", "last
        // April", "between the two salary dates".
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(
                label = state.custom?.let {
                    it.start.format(DAY_FORMAT) + " – " + it.end.format(DAY_FORMAT)
                } ?: stringResource(R.string.dash_range_custom),
                selected = state.custom != null,
                onClick = actions.onPickRange
            )
        }

        // ------------------------------------------------------------ headline
        Text(
            text = money(state.headlineMinor),
            style = typography.displayLarge,
            color = if (state.direction == SpendDirection.INCOME) colors.credit else colors.ink,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            text = stringResource(
                if (state.direction == SpendDirection.EXPENSE) R.string.dash_spent_over
                else R.string.dash_received_over,
                state.headlineCount,
                state.rangeDays
            ),
            style = typography.bodySmall,
            color = colors.graphite
        )

        // The comparison, immediately under the number it qualifies. A total on
        // its own answers nothing - this is what makes it a judgement.
        state.change?.let { change ->
            Text(
                // A near-empty previous period turns into "9127% more", which is
                // correct and unreadable - nobody holds a ninety-one-fold rise in
                // their head as a percentage. Past ten times over it reads "×92".
                text = if (change.isLarge) {
                    stringResource(
                        R.string.dash_times_vs_previous,
                        change.multiple,
                        money(kotlin.math.abs(change.deltaMinor))
                    )
                } else {
                    stringResource(
                        if (change.isUp) R.string.dash_up_vs_previous
                        else R.string.dash_down_vs_previous,
                        kotlin.math.abs(change.fraction * 100).toInt(),
                        money(kotlin.math.abs(change.deltaMinor))
                    )
                },
                style = typography.bodySmall,
                // Up is not automatically bad and down is not automatically good,
                // so neither gets a success or warning colour - only emphasis.
                color = if (change.isUp) colors.ink else colors.credit,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (state.isEmpty) {
            ChartEmpty(stringResource(R.string.dash_nothing), Modifier.padding(top = 24.dp))
            // Budgets survive an empty range: they carry their own windows, and
            // "nothing in the last 7 days" is not a reason to hide a limit that
            // is three quarters gone for the month.
            BudgetSection(
                budgets = state.rankedBudgets,
                onOpen = actions.onOpenBudget,
                onEdit = actions.onEditBudget,
                onNew = actions.onNewBudget,
                modifier = Modifier.padding(top = 28.dp)
            )
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
                value = money(state.dailyAverageMinor),
                caption = stringResource(R.string.dash_daily_average)
            )
            // Every caption follows the direction chips. "On days you spent" over
            // an income figure is a label describing the wrong side of the ledger.
            StatTile(
                value = money(state.averageOnSpendingDays),
                caption = stringResource(
                    if (income) R.string.dash_average_active_in
                    else R.string.dash_average_active
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            state.busiestDay?.let { busiest ->
                StatTile(
                    value = money(state.busiestDayMinor),
                    caption = stringResource(
                        if (income) R.string.dash_busiest_day_in else R.string.dash_busiest_day
                    ) + " · " + busiest.date.format(DAY_FORMAT)
                )
            }
            // People recognise this about themselves far more readily than an
            // average: "nothing on 9 days" lands where "₹243/day" does not.
            StatTile(
                value = state.spendFreeDays.toString(),
                caption = stringResource(
                    if (income) R.string.dash_quiet_days_in else R.string.dash_spend_free_days
                )
            )
        }

        // ------------------------------------------------------------- by day
        Section(stringResource(if (income) R.string.dash_by_day_in else R.string.dash_by_day)) {
            val values = state.buckets.map {
                if (state.direction == SpendDirection.EXPENSE) it.spentMinor else it.receivedMinor
            }
            val peakIndex = values.indices.maxByOrNull { values[it] }
            val todayIndex = state.buckets.indexOfFirst { it.date == LocalDate.now() }
                .takeIf { it >= 0 }

            SpendColumns(
                values = values,
                emphasisIndex = peakIndex,
                accentIndex = todayIndex,
                // The column was the last thing on this screen that showed a
                // number and refused to say what was behind it.
                onSelectIndex = { index ->
                    state.buckets.getOrNull(index)?.let { actions.onOpenDay(it.date) }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(state.buckets.first().date.format(DAY_FORMAT), style = typography.labelSmall, color = colors.mist)
                Text(state.buckets.last().date.format(DAY_FORMAT), style = typography.labelSmall, color = colors.mist)
            }
        }

        // -------------------------------------------------------- month by month
        //
        // The comparison the headline could only gesture at. "12% more than the
        // period before" is one neighbour; whether that is high for this person
        // needs the other eleven months on screen.
        MonthSection(
            months = state.months,
            onOpen = actions.onOpenMonth,
            modifier = Modifier.padding(top = 28.dp)
        )

        // ------------------------------------------------------------- budgets
        //
        // Above the breakdown, because it answers the question the breakdown only
        // sets up: the bars say where the money went, this says whether that was
        // more than the user meant.
        BudgetSection(
            budgets = state.rankedBudgets,
            onOpen = actions.onOpenBudget,
            onEdit = actions.onEditBudget,
            onNew = actions.onNewBudget,
            modifier = Modifier.padding(top = 28.dp)
        )

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
                Chip(stringResource(R.string.group_amount), state.groupBy == GroupBy.AMOUNT) {
                    actions.onGroupBy(GroupBy.AMOUNT)
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
                // The denominator behind every share on this list. Without it a
                // "75%" bar is a percentage of a number that appears nowhere on
                // screen - and when grouping by tag it is deliberately *not* the
                // headline total, because only tagged payments are counted.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = pluralStringResource(
                            when (state.groupBy) {
                                GroupBy.MERCHANT -> R.plurals.group_total_merchant
                                GroupBy.TAG -> R.plurals.group_total_tag
                                GroupBy.CHANNEL -> R.plurals.group_total_channel
                                GroupBy.AMOUNT -> R.plurals.group_total_amount
                            },
                            rows.size,
                            rows.size
                        ),
                        style = typography.labelSmall,
                        color = colors.graphite
                    )
                    Text(
                        text = money(state.groupTotalMinor),
                        style = typography.bodySmall,
                        color = colors.ink
                    )
                }
                RankedBars(
                    data = rows.mapIndexed { index, slice ->
                        BarDatum(
                            label = when (state.groupBy) {
                                GroupBy.CHANNEL -> channelLabel(slice.label)
                                // The label *is* the amount, in minor units. The
                                // repository leaves it a raw number because how
                                // money is written is a display decision.
                                GroupBy.AMOUNT -> money(slice.label.toLongOrNull() ?: 0L)
                                else -> slice.label
                            },
                            // Grouped by amount, the interesting figure is how
                            // often - so the bar carries the count and the
                            // caption carries what it added up to.
                            valueMinor = if (state.groupBy == GroupBy.AMOUNT) {
                                slice.count.toLong()
                            } else {
                                slice.amountMinor
                            },
                            // Grouped by amount the row reads "₹45 — 5 times",
                            // and what those five came to belongs underneath.
                            valueLabel = if (state.groupBy == GroupBy.AMOUNT) {
                                pluralTimes(slice.count)
                            } else {
                                null
                            },
                            caption = if (state.groupBy == GroupBy.AMOUNT) {
                                stringResource(R.string.dash_amount_total, money(slice.amountMinor))
                            } else {
                                pluralPayments(slice.count)
                            },
                            // Emphasis follows the largest bar, which is only the
                            // first row when sorting by amount.
                            emphasis = state.sortBy == SortBy.AMOUNT && index == 0,
                            share = state.shareOf(slice),
                            // The stored value, which for a channel is not the
                            // word on the bar: "Card" is rendered from `CARD`.
                            key = slice.label
                        )
                    },
                    maxRows = 20,
                    // The breakdown used to end here, which made it a dead end:
                    // it named the payee and then refused to say which payments
                    // added up to that figure.
                    onSelect = { bar -> actions.onOpenSlice(state.selectionFor(bar.key, bar.label)) },
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
        Text(
            text = stringResource(R.string.dash_tap_note),
            style = typography.labelSmall,
            color = colors.mist,
            modifier = Modifier.padding(top = 2.dp)
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
            amount = money(state.totalReceivedMinor),
            sign = "+",
            signColor = colors.credit
        )
        BalanceRow(
            label = stringResource(R.string.dash_expense),
            amount = money(state.totalSpentMinor),
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
                text = money(kotlin.math.abs(net)),
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

/**
 * Quieter than a filter chip: sorting reorders, it does not change the numbers.
 *
 * Quiet is not the same as invisible, though. Unselected sat at `mist` on paper
 * with no bounds at all, which read as disabled text rather than as three things
 * you can tap. The selected one now carries the sunk background every other
 * control on this screen uses, and the rest sit at `graphite`.
 */
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) colors.ink else colors.graphite,
        modifier = Modifier
            .background(
                color = if (selected) colors.paperSunk else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

private fun pluralPayments(count: Int): String =
    if (count == 1) "1 payment" else "$count payments"

private fun pluralTimes(count: Int): String =
    if (count == 1) "once" else "$count times"

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
