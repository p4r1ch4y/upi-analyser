package com.spendlens.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.MonthBucket
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
private val MONTH_YEAR_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

/**
 * A year of months, in and out.
 *
 * The one comparison the report could not make. "12% more than the period
 * before" is a single line about a single neighbour; whether this month is
 * genuinely high, or just high for a Tuesday in August, needs the other eleven on
 * screen. This is the same job a competitor's monthly bar chart does, without the
 * legend: each row is direct-labelled and the two marks are told apart by
 * position, not by hue.
 *
 * Every row opens the stream on that month, which is what makes an outlier
 * answerable rather than merely visible.
 */
@Composable
fun MonthSection(
    months: List<MonthBucket>,
    onOpen: (MonthBucket) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    if (months.isEmpty()) return

    // Months before the ledger began are not "months you spent nothing" - they
    // are months the app was not watching, and a run of them at the top pushes
    // the real data off the screen. Empty months *inside* the span stay, because
    // there they are a genuine fact about the person.
    val visible = months.dropWhile { it.count == 0 }.ifEmpty { months.takeLast(1) }

    // One scale across every row, because the whole point is comparing them. A
    // per-row scale would draw twelve full bars and say nothing.
    val peak = visible.maxOf { maxOf(it.spentMinor, it.receivedMinor) }.coerceAtLeast(1L)
    val busiest = visible.maxByOrNull { it.spentMinor }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.dash_month_by_month).uppercase(Locale.ROOT),
            style = typography.labelSmall,
            color = colors.graphite,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (bucket in visible) {
                MonthRow(
                    bucket = bucket,
                    peak = peak,
                    emphasis = bucket == busiest && bucket.spentMinor > 0L,
                    onClick = { onOpen(bucket) }
                )
            }
        }

        Text(
            text = stringResource(R.string.dash_month_note),
            style = typography.labelSmall,
            color = colors.mist,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/**
 * One month: label, what went out, and - only when there was any - what came in.
 *
 * Money in is drawn as a second, thinner mark under the first rather than as a
 * differently coloured bar of the same weight. Out is the subject of this screen;
 * in is context, and giving them equal visual weight would imply a comparison
 * nobody asked for.
 */
@Composable
private fun MonthRow(
    bucket: MonthBucket,
    peak: Long,
    emphasis: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    val label = bucket.start.format(
        if (bucket.month == 1 || bucket.start == bucket.start.withDayOfYear(1)) MONTH_YEAR_LABEL
        else MONTH_LABEL
    )
    val description = "$label: ${money(bucket.spentMinor)} out" +
        (if (bucket.receivedMinor > 0L) ", ${money(bucket.receivedMinor)} in" else "")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
            .semantics { contentDescription = description }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(text = label, style = typography.bodySmall, color = colors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (bucket.receivedMinor > 0L) {
                    Text(
                        text = "+" + money(bucket.receivedMinor),
                        style = typography.labelSmall,
                        color = colors.credit
                    )
                }
                Text(
                    text = money(bucket.spentMinor),
                    style = typography.bodySmall,
                    color = if (bucket.spentMinor > 0L) colors.ink else colors.mist
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxWidth().height(BAR_BLOCK).padding(top = 4.dp)) {
            // Square root, the same scaling the tap bar and the daily columns
            // use. One ₹1,50,000 month against eleven ordinary ones flattens
            // every ordinary month to an identical 5% stub on a linear scale, and
            // "which months were heavier" is the only thing the mark is for -
            // the exact figures are already on the row beside it.
            val out = size.width * scaled(bucket.spentMinor, peak)
            val inn = size.width * scaled(bucket.receivedMinor, peak)
            val thick = OUT_THICKNESS_PX
            val thin = IN_THICKNESS_PX

            drawRoundRect(
                color = colors.ruleSoft,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, thick),
                cornerRadius = CornerRadius(thick / 2)
            )
            if (out > 0f) {
                drawRoundRect(
                    // Ink for the field, the accent for the peak - the same
                    // pairing RankedBars uses. This was `leader` on a `ruleSoft`
                    // track, two greys four steps apart, which made eleven of the
                    // twelve spending bars invisible: the income marks read fine
                    // and the subject of the chart did not appear at all.
                    color = if (emphasis) colors.split else colors.ink,
                    topLeft = Offset(0f, 0f),
                    size = Size(out.coerceAtLeast(thick), thick),
                    cornerRadius = CornerRadius(thick / 2)
                )
            }
            if (inn > 0f) {
                drawRoundRect(
                    color = colors.credit,
                    topLeft = Offset(0f, thick + GAP_PX),
                    size = Size(inn.coerceAtLeast(thin), thin),
                    cornerRadius = CornerRadius(thin / 2)
                )
            }
        }
    }
}

/** Square-root scaling, matching the tap bar and the daily columns. */
private fun scaled(value: Long, peak: Long): Float =
    if (value <= 0L) 0f
    else kotlin.math.sqrt(value.toDouble() / peak).toFloat().coerceIn(0f, 1f)

private val BAR_BLOCK = 18.dp
private const val OUT_THICKNESS_PX = 10f
private const val IN_THICKNESS_PX = 4f
private const val GAP_PX = 3f
