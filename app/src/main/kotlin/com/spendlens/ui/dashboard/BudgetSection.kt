package com.spendlens.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.BudgetProgress
import com.spendlens.core.model.BudgetScope
import com.spendlens.ui.charts.BudgetBar
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WINDOW_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * Budgets on the report.
 *
 * Placed above the breakdown, because it answers the question the breakdown only
 * sets up. "₹4,320 went to food delivery" is a fact; "you are ₹900 past where you
 * meant to be with 11 days to go" is the thing a person can act on, and it is the
 * only part of this screen that says *should* rather than *did*.
 */
@Composable
fun BudgetSection(
    budgets: List<BudgetProgress>,
    onOpen: (BudgetProgress) -> Unit,
    onEdit: (BudgetProgress) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.budget_section).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite
            )
            if (budgets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.budget_add),
                    style = typography.labelSmall,
                    color = colors.ink,
                    modifier = Modifier
                        .clickable(onClick = onNew)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }

        if (budgets.isEmpty()) {
            BudgetEmptyState(onNew)
            return@Column
        }

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for (progress in budgets) {
                BudgetRow(
                    progress = progress,
                    onOpen = { onOpen(progress) },
                    onEdit = { onEdit(progress) }
                )
            }
        }
    }
}

/**
 * One budget.
 *
 * Three lines, in the order the question is actually asked: what it is, how it
 * stands, and what that means for the rest of the period. The third line is the
 * one worth having - a bar without it is a progress indicator, not advice.
 */
@Composable
private fun BudgetRow(
    progress: BudgetProgress,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                Text(
                    text = progress.budget.name,
                    style = typography.bodyMedium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = scopeLine(progress),
                    style = typography.labelSmall,
                    color = colors.mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Text(
                text = stringResource(
                    R.string.budget_of,
                    money(progress.spentMinor),
                    money(progress.limitMinor)
                ),
                style = typography.bodySmall,
                color = colors.ink
            )
        }

        BudgetBar(
            fraction = progress.fraction,
            paceFraction = progress.paceFraction,
            over = progress.isOver,
            modifier = Modifier.padding(top = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = verdict(progress),
                style = typography.bodySmall,
                // The one place this screen colours a judgement. `review` is the
                // app's existing "wants your attention" tone, the same one a
                // low-confidence row carries - a state, never a category.
                color = when (progress.state) {
                    BudgetProgress.State.OVER,
                    BudgetProgress.State.AHEAD_OF_PACE -> colors.review
                    else -> colors.graphite
                },
                modifier = Modifier.weight(1f).padding(end = 10.dp)
            )
            Text(
                text = stringResource(R.string.budget_edit),
                style = typography.labelSmall,
                color = colors.mist,
                modifier = Modifier
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * The first budget is the hard one to set, so the empty state does the setting up
 * rather than just naming the feature.
 */
@Composable
private fun BudgetEmptyState(onNew: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
            .clickable(onClick = onNew)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(
            text = stringResource(R.string.budget_empty_title),
            style = typography.bodyMedium,
            color = colors.ink
        )
        Text(
            text = stringResource(R.string.budget_empty_body),
            style = typography.bodySmall,
            color = colors.graphite,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = stringResource(R.string.budget_empty_action),
            style = typography.bodySmall,
            color = colors.split,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/** "Everything · 1–31 Aug" — what is counted, over what window. */
@Composable
private fun scopeLine(progress: BudgetProgress): String {
    val scope = when (progress.budget.scope) {
        BudgetScope.TOTAL -> stringResource(R.string.budget_scope_total)
        BudgetScope.TAG, BudgetScope.MERCHANT -> progress.budget.scopeValue.orEmpty()
    }
    val window = progress.start.format(WINDOW_FORMAT) + " – " + progress.end.format(WINDOW_FORMAT)
    return "$scope · $window"
}

/** "11 days left", or the last day of the period, which is not a countdown. */
@Composable
private fun daysLeftLabel(daysLeft: Int): String =
    if (daysLeft <= 0) stringResource(R.string.budget_last_day)
    else pluralStringResource(R.plurals.budget_days_left, daysLeft, daysLeft)

/**
 * The sentence under the bar.
 *
 * Deliberately different wording per state rather than one template with numbers
 * swapped in: "₹0 left" and "₹900 over" are not the same news, and a person
 * skimming should be able to tell which one they got without reading the number.
 */
@Composable
private fun verdict(progress: BudgetProgress): String = when (progress.state) {
    BudgetProgress.State.OVER -> stringResource(
        R.string.budget_over_by,
        money(-progress.remainingMinor)
    )

    BudgetProgress.State.AHEAD_OF_PACE -> stringResource(
        R.string.budget_ahead_of_pace,
        money(progress.projectedMinor),
        money(progress.dailyAllowanceMinor)
    )

    BudgetProgress.State.UNTOUCHED -> daysLeftLabel(progress.daysLeft) +
        " · " + stringResource(R.string.budget_nothing_yet)

    BudgetProgress.State.ON_TRACK -> stringResource(
        R.string.budget_on_track,
        money(progress.remainingMinor),
        money(progress.dailyAllowanceMinor)
    )
}
