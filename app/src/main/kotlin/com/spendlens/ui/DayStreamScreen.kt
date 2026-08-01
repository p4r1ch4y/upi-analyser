package com.spendlens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.MoneyFormat
import com.spendlens.ui.components.ReviewChip
import com.spendlens.ui.components.TapBar
import com.spendlens.ui.components.TapBarItem
import com.spendlens.ui.components.TransactionRow
import com.spendlens.ui.entry.EntryActions
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** Everything the stream can ask the rest of the app to do. */
data class DayStreamActions(
    val onNameMerchant: (vpa: String) -> Unit = {},
    val onOpenTransaction: (String) -> Unit = {},
    val onToggleSelect: (String) -> Unit = {},
    val onToggleDaySelect: (LocalDate) -> Unit = {},
    val onToggleDayExpanded: (LocalDate) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onClearFilter: () -> Unit = {}
)

/**
 * The day stream. Organised by time, not by category - the product's whole claim
 * is that you recognise your own day, and a pie chart is not a day.
 */
@Composable
fun DayStreamScreen(
    state: DayStreamUiState,
    actions: DayStreamActions,
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
        state.trip?.let { trip ->
            item { TripBar(trip) }
        }

        state.filter?.let { filter ->
            item {
                FilterHeader(
                    filter = filter,
                    matchCount = state.matchCount,
                    totalMinor = state.matchTotalMinor,
                    onClear = actions.onClearFilter
                )
            }
        }

        if (header != null && state.filter == null) {
            item { header() }
        }

        if (state.filter == null) {
            item {
                SearchField(
                    query = state.query,
                    matchCount = state.matchCount,
                    onQuery = actions.onQuery
                )
            }

            item {
                EntryActions(
                    onAdd = actions.onAdd,
                    onImport = actions.onImport,
                    onMore = actions.onMore,
                    busy = state.importing
                )
            }
        }

        if (!state.flattened) item {
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
                    text = money(today?.spentMinor ?: 0L),
                    style = typography.displayLarge,
                    color = colors.ink
                )

                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = tapsAndMerchants(today?.tapCount ?: 0, today?.merchantCount ?: 0),
                        style = typography.bodySmall,
                        color = colors.graphite
                    )
                    // Money in, beside the count rather than inside the headline.
                    // The big number is what you spent and stays that way, but a
                    // day whose only payment was incoming used to render as a bare
                    // "₹0" above a row reading "+₹150", which looks like a fault.
                    (today?.receivedMinor ?: 0L).takeIf { it > 0L }?.let { received ->
                        Text(
                            text = stringResource(R.string.day_received_in, money(received)),
                            style = typography.bodySmall,
                            color = colors.credit
                        )
                    }
                }

                TapBar(
                    transactions = today?.transactions
                        ?.filter { !it.isCredit && !it.failed }
                        ?.map { TapBarItem(it.effectiveMinor) }
                        .orEmpty(),
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
        }

        val todayTransactions = if (state.flattened) emptyList() else today?.transactions.orEmpty()

        if (todayTransactions.isEmpty() && !state.flattened) {
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
            StreamRow(txn, state, actions)
        }

        // Searching and filtering both flatten the stream: every matching day is
        // open, because a result list that needs unfolding is not a result list.
        val remainingDays = if (state.flattened) state.days else state.earlier
        items(remainingDays, key = { it.date.toEpochDay() }) { day ->
            CollapsibleDay(day, state, actions)
        }

        if (state.matchCount == 0 && state.flattened) {
            item {
                Text(
                    text = if (state.searching) {
                        stringResource(R.string.search_no_matches, state.query)
                    } else {
                        stringResource(R.string.filter_no_matches)
                    },
                    style = typography.bodySmall,
                    color = colors.mist,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp)
                )
            }
        }

        item { Spacer(Modifier.navigationBarsPadding().height(96.dp)) }
    }
}

/**
 * The trip banner. A trip is a tag that knows its own date range, so this can say
 * how far through it you are without anyone having entered dates by hand.
 */
@Composable
private fun TripBar(trip: TripBanner, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.split)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = trip.name,
                style = typography.bodySmall,
                color = colors.paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.trip_day_of, trip.dayIndex, trip.dayCount),
                style = typography.bodySmall,
                color = colors.splitBg
            )
        }
        Text(
            text = money(trip.spentMinor),
            style = typography.bodySmall,
            color = colors.paper
        )
    }
}

/**
 * What the stream is currently narrowed to, and the way out of it.
 *
 * Built as a hero rather than a chip because arriving here is a navigation, not a
 * refinement: the user tapped a bar that said ₹4,320 and this has to open with
 * the same figure, over the same window, or the trip across screens costs them
 * their trust in both numbers.
 */
@Composable
private fun FilterHeader(
    filter: StreamFilter,
    matchCount: Int,
    totalMinor: Long,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.paperSunk)
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp, bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        when (filter.kind) {
                            StreamFilter.Kind.ALL -> R.string.filter_kind_all
                            StreamFilter.Kind.MERCHANT -> R.string.filter_kind_merchant
                            StreamFilter.Kind.TAG -> R.string.filter_kind_tag
                            StreamFilter.Kind.CHANNEL -> R.string.filter_kind_channel
                        }
                    ).uppercase(Locale.ROOT),
                    style = typography.labelSmall,
                    color = colors.graphite
                )
                Text(
                    text = filter.label,
                    style = typography.titleLarge,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = stringResource(R.string.filter_clear),
                style = typography.bodySmall,
                color = colors.ink,
                modifier = Modifier
                    .background(colors.paper, RoundedCornerShape(6.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = money(totalMinor),
                style = typography.displaySmall,
                color = colors.ink
            )
            Text(
                text = pluralStringResource(R.plurals.tap_count, matchCount, matchCount) +
                    " · " + filter.rangeLabel,
                style = typography.bodySmall,
                color = colors.graphite
            )
        }
    }
}

/**
 * A past day. Collapsed it is a single line; tapping opens it in place.
 *
 * Long-pressing the header ticks the whole day, which is what "mark a day as
 * splittable" means in practice - select it, then split or tag everything on it
 * in one action.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsibleDay(
    day: DayUi,
    state: DayStreamUiState,
    actions: DayStreamActions
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val expanded = state.isExpanded(day.date) || state.flattened
    val allSelected = day.transactions.isNotEmpty() && day.transactions.all { it.id in state.selected }

    Column(modifier = Modifier.fillMaxWidth().background(colors.paperSunk)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { actions.onToggleDayExpanded(day.date) },
                    onLongClick = { actions.onToggleDaySelect(day.date) }
                )
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.selecting) {
                        SelectionDot(
                            selected = allSelected,
                            modifier = Modifier
                                .padding(end = 9.dp)
                                .clickable { actions.onToggleDaySelect(day.date) }
                        )
                    }
                    Text(
                        text = day.date.format(DAY_FORMAT).uppercase(Locale.ROOT),
                        style = typography.labelSmall,
                        color = colors.graphite
                    )
                }
                Text(
                    text = money(day.spentMinor),
                    style = typography.displaySmall,
                    color = colors.ink
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tapsAndMerchants(day.tapCount, day.merchantCount),
                        style = typography.bodySmall,
                        color = colors.graphite
                    )
                    day.receivedMinor.takeIf { it > 0L }?.let { received ->
                        Text(
                            text = stringResource(R.string.day_received_in, money(received)),
                            style = typography.bodySmall,
                            color = colors.credit
                        )
                    }
                }
                Text(
                    text = stringResource(
                        if (expanded) R.string.day_hide else R.string.day_show
                    ),
                    style = typography.bodySmall,
                    color = colors.mist
                )
            }
        }

        if (expanded) {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                for (txn in day.transactions) {
                    StreamRow(txn, state, actions)
                }
            }
        }
    }
}

/**
 * One payment.
 *
 * Tap opens it; long-press starts a selection. Once a selection exists, tap
 * toggles instead of opening - otherwise ticking a run of payments means
 * long-pressing every single one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamRow(
    txn: TxnUi,
    state: DayStreamUiState,
    actions: DayStreamActions
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val selected = txn.id in state.selected

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.splitBg else colors.paper)
            .combinedClickable(
                onClick = {
                    if (state.selecting) actions.onToggleSelect(txn.id)
                    else actions.onOpenTransaction(txn.id)
                },
                onLongClick = { actions.onToggleSelect(txn.id) }
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.selecting) {
                SelectionDot(selected = selected, modifier = Modifier.padding(start = 18.dp))
            }
            TransactionRow(
                timestamp = txn.occurredAt.asLocalTime(),
                merchantName = txn.displayName,
                amount = txn.amountLabel(),
                amountColor = if (txn.isCredit) colors.credit else colors.ink,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 18.dp),
                subRow = null
            )
        }

        // Sub-rows: why this row needs attention, and what happened to it.
        val vpa = txn.counterpartyVpa
        if (txn.needsReview && vpa != null) {
            ReviewChip(
                text = stringResource(R.string.name_this_merchant),
                modifier = Modifier
                    .padding(start = 62.dp, bottom = 8.dp)
                    .clickable { actions.onNameMerchant(vpa) }
            )
        }

        if (txn.failed) {
            Text(
                text = stringResource(R.string.failed_not_counted),
                style = typography.bodySmall,
                color = colors.review,
                modifier = Modifier.padding(start = 62.dp, bottom = 9.dp)
            )
        }

        txn.split?.let { split ->
            Row(
                modifier = Modifier.padding(start = 62.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.split_paid, money(split.totalMinor)),
                    style = typography.bodySmall,
                    color = colors.split
                )
            }
        }

        if (txn.tags.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 62.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (tag in txn.tags.take(3)) {
                    TagChip(tag.name, isTrip = tag.isTrip)
                }
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .height(1.dp)
                .background(colors.ruleSoft)
        )
    }
}

/**
 * Search across name, VPA, tag and amount.
 *
 * Built from a bare text field rather than an OutlinedTextField: the stock
 * Material box brings a heavy border, a floating label and 56dp of chrome, none
 * of which belongs in a screen whose whole grammar is a printed receipt. What is
 * left is a rule, a letterspaced prompt, and the match count sitting where the
 * dotted leader would be on a transaction row.
 */
@Composable
private fun SearchField(query: String, matchCount: Int, onQuery: (String) -> Unit) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val focus = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = typography.bodyMedium.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            style = typography.bodyMedium,
                            color = colors.mist
                        )
                    }
                    inner()
                }
            )

            if (query.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.search_clear),
                    style = typography.labelSmall,
                    color = colors.graphite,
                    modifier = Modifier
                        .clickable { onQuery(""); focus.clearFocus() }
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                )
            }
        }

        // The rule is the field. It thickens and inks when there is a query, so
        // the state of the search is legible without a box around it.
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 7.dp)
                .height(if (query.isEmpty()) 1.dp else 1.5.dp)
                .background(if (query.isEmpty()) colors.rule else colors.ink)
        )

        if (query.isNotEmpty()) {
            Text(
                text = stringResource(R.string.search_matches, matchCount),
                style = typography.labelSmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
internal fun TagChip(name: String, isTrip: Boolean, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    Text(
        text = name,
        style = MaterialTheme.typography.bodySmall,
        // Trips take the accent; plain tags stay neutral. Identity always comes
        // from the label, never from the colour, so the chips stay readable for
        // everyone and no tag needs a legend.
        color = if (isTrip) colors.paper else colors.graphite,
        modifier = modifier
            .background(
                color = if (isTrip) colors.split else colors.ruleSoft,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun SelectionDot(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    Box(
        modifier = modifier
            .size(16.dp)
            .then(
                if (selected) Modifier.background(colors.split, CircleShape)
                else Modifier.border(1.5.dp, colors.leader, CircleShape)
            )
    )
}

/** Sticky bar shown while payments are ticked. */
@Composable
fun SelectionBar(
    count: Int,
    totalMinor: Long,
    onSplit: () -> Unit,
    onTag: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.ink)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.selection_count, count),
                style = typography.bodySmall,
                color = colors.paper
            )
            Text(
                text = money(totalMinor),
                style = typography.bodySmall,
                color = colors.mist
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarAction(stringResource(R.string.action_split), onSplit)
            BarAction(stringResource(R.string.action_tag), onTag)
            BarAction(stringResource(R.string.action_clear), onClear)
        }
    }
}

@Composable
private fun BarAction(text: String, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = colors.paper,
        modifier = Modifier
            .background(colors.graphite, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

/** "4 taps · 1 merchant", with the grammar left to the resource system. */
@Composable
private fun tapsAndMerchants(taps: Int, merchants: Int): String =
    pluralStringResource(R.plurals.tap_count, taps, taps) + " · " +
        pluralStringResource(R.plurals.merchant_count, merchants, merchants)

private fun Long.asLocalTime(zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(this).atZone(zone).toLocalTime().format(TIME_FORMAT)

/** Credits read as money coming in; a split reads as the user's own share. */
@Composable
private fun TxnUi.amountLabel(): String =
    (if (isCredit) "+" else "") + money(effectiveMinor)
