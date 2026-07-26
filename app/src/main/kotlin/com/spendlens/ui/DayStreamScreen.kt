package com.spendlens.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.MoneyFormat
import com.spendlens.ui.components.ReviewChip
import com.spendlens.ui.components.TapBar
import com.spendlens.ui.components.TapBarItem
import com.spendlens.ui.components.TransactionRow
import com.spendlens.ui.entry.EntryActions
import com.spendlens.ui.theme.SpendTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/**
 * The day stream. Organised by time, not by category - the product's whole claim
 * is that you recognise your own day, and a pie chart is not a day.
 */
@Composable
fun DayStreamScreen(
    state: DayStreamUiState,
    onNameMerchant: (vpa: String) -> Unit,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val today = state.today

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.paper)
            .statusBarsPadding()
    ) {
        if (header != null) {
            item { header() }
        }

        item {
            EntryActions(onAdd = onAdd, onImport = onImport, busy = state.importing)
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 20.dp, bottom = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.today).uppercase(Locale.ROOT),
                        style = typography.labelSmall,
                        color = colors.graphite,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    Text(
                        text = (today?.date ?: LocalDate.now())
                            .format(DAY_FORMAT).uppercase(Locale.ROOT),
                        style = typography.labelSmall,
                        color = colors.graphite
                    )
                }

                Text(
                    text = MoneyFormat.rupees(today?.spentMinor ?: 0L),
                    style = typography.displayLarge,
                    color = colors.ink
                )

                Text(
                    text = stringResource(
                        R.string.taps_merchants,
                        today?.tapCount ?: 0,
                        today?.merchantCount ?: 0
                    ),
                    style = typography.bodySmall,
                    color = colors.graphite,
                    modifier = Modifier.padding(top = 3.dp)
                )

                TapBar(
                    transactions = today?.transactions
                        ?.filter { !it.isCredit }
                        ?.map { TapBarItem(it.amountMinor) }
                        .orEmpty(),
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }

        val todayTransactions = today?.transactions.orEmpty()

        if (todayTransactions.isEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 28.dp)) {
                    Text(
                        text = stringResource(R.string.no_transactions_yet),
                        style = typography.bodyMedium,
                        color = colors.graphite
                    )
                    Text(
                        text = stringResource(R.string.test_transaction),
                        style = typography.bodySmall,
                        color = colors.mist,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        items(todayTransactions, key = { it.id }) { txn ->
            TransactionRow(
                timestamp = txn.occurredAt.asLocalTime(),
                merchantName = txn.displayName,
                amount = txn.amountLabel(),
                amountColor = if (txn.isCredit) colors.credit else colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                subRow = txn.counterpartyVpa
                    ?.takeIf { txn.needsReview }
                    ?.let { vpa ->
                        {
                            ReviewChip(
                                text = stringResource(R.string.name_this_merchant),
                                modifier = Modifier.clickable { onNameMerchant(vpa) }
                            )
                        }
                    }
            )
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .height(1.dp)
                    .background(colors.ruleSoft)
            )
        }

        items(state.earlier, key = { it.date.toEpochDay() }) { day ->
            CollapsedDay(day)
        }

        item { Spacer(Modifier.navigationBarsPadding().height(24.dp)) }
    }
}

@Composable
private fun CollapsedDay(day: DayUi) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paperSunk)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = day.date.format(DAY_FORMAT).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite
            )
            Text(
                text = MoneyFormat.rupees(day.spentMinor),
                style = typography.displaySmall,
                color = colors.ink
            )
        }
        Text(
            text = stringResource(R.string.taps_merchants, day.tapCount, day.merchantCount),
            style = typography.bodySmall,
            color = colors.graphite,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

private fun Long.asLocalTime(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalTime().format(TIME_FORMAT)

/** Credits read as money coming back, so they carry a sign. */
private fun TxnUi.amountLabel(): String {
    val formatted = if (currency == "INR") {
        MoneyFormat.rupees(amountMinor)
    } else {
        "$currency ${amountMinor / 100}"
    }
    return if (isCredit) "+$formatted" else formatted
}
