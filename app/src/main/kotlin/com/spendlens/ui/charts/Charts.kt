package com.spendlens.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.spendlens.R
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money

/**
 * The chart vocabulary for the dashboard.
 *
 * Every chart here answers "compare magnitude", and magnitude is carried by the
 * *length* of a mark. Colour therefore encodes nothing but emphasis - one mark
 * picked out of a field of recessive ones - which is why there is no categorical
 * palette anywhere in this file and no legend on any chart. The app's own tag
 * palette was measured and its adjacent hues sit at ΔE 6.5 even for full colour
 * vision, so using it to tell series apart would have been unreadable; length and
 * a direct label are not.
 */

/** One bar. [emphasis] lifts a single mark out of the field. */
data class BarDatum(
    val label: String,
    val valueMinor: Long,
    val caption: String? = null,
    val emphasis: Boolean = false,
    /** Share of the report's total, 0..1. Rendered as a percentage on the bar. */
    val share: Float? = null,
    /**
     * The value this bar aggregates, when it differs from what is displayed.
     *
     * A channel bar reads "Card" but groups the stored value `CARD`, and anything
     * acting on the bar has to filter on the latter. Defaults to the label, which
     * is correct for merchants and tags.
     */
    val key: String = label
)

/**
 * Ranked horizontal bars.
 *
 * Horizontal because the labels are merchant names and tag names, which are long
 * and would be turned on their side or truncated by a column chart.
 *
 * With [onSelect] the rows become the way into the payments behind them. A
 * breakdown that cannot be opened is a dead end: it tells you that ₹4,320 went to
 * one payee and then refuses to say which payments those were.
 */
@Composable
fun RankedBars(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    maxRows: Int = 8,
    onSelect: ((BarDatum) -> Unit)? = null
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    if (data.isEmpty()) return

    val rows = data.take(maxRows)
    val peak = rows.maxOf { it.valueMinor }.coerceAtLeast(1L)
    val hidden = data.size - rows.size

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            val fraction = (row.valueMinor.toDouble() / peak).toFloat().coerceIn(0f, 1f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onSelect == null) Modifier
                        else Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onSelect(row) }
                            // Vertical only. The row is already full width, so
                            // horizontal padding would buy no touch area and
                            // would inset these bars 6dp from every other chart
                            // on the screen, which reads as a misalignment.
                            .padding(vertical = 4.dp)
                    )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = row.label,
                        style = typography.bodySmall,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                    )
                    if (onSelect != null) {
                        // The one affordance a bar gets. A chevron is quieter than
                        // a button and reads the same way a settings row does.
                        Text(
                            text = "›",
                            style = typography.bodySmall,
                            color = colors.mist
                        )
                    }
                    // Every bar is direct-labelled. The recessive fill sits below
                    // 3:1 against the surface on purpose, and a visible value is
                    // what makes that legal rather than merely quiet.
                    Text(
                        text = money(row.valueMinor),
                        style = typography.bodySmall,
                        color = colors.ink
                    )
                }

                if (row.caption != null) {
                    Text(
                        text = row.caption,
                        style = typography.labelSmall,
                        color = colors.graphite,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }

                val barDescription = "${row.label}: ${money(row.valueMinor)}" +
                    (row.share?.let { ", ${(it * 100).toInt()} percent of the total" } ?: "")

                Box(modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BAR_THICKNESS)
                        .semantics { contentDescription = barDescription }
                ) {
                    // Track first, so a near-zero bar still reads as "a row that
                    // exists and is nearly nothing" rather than as missing data.
                    drawRoundRect(
                        color = colors.ruleSoft,
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(size.height / 2)
                    )
                    val width = size.width * fraction
                    if (width > 0f) {
                        drawRoundRect(
                            color = if (row.emphasis) colors.split else colors.ink,
                            size = Size(width.coerceAtLeast(size.height), size.height),
                            cornerRadius = CornerRadius(size.height / 2)
                        )
                    }
                }

                // The share sits beside the bar rather than inside it: a fill that
                // is 4% of the width has nowhere to put a label, and a number that
                // jumps in and out of its bar as the data changes is worse than one
                // that stays put.
                row.share?.let { share ->
                    Text(
                        text = formatShare(share),
                        style = typography.labelSmall,
                        // The label sits at the end of the track, so on the
                        // longest bar it lands *on* the fill rather than beside
                        // it - and graphite on ink is unreadable. Above the point
                        // where the fill reaches it, the label flips to paper.
                        color = if (fraction >= LABEL_OVER_FILL) colors.paper else colors.graphite,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                    )
                }
                }
            }
        }

        // Never truncate in silence. A list that stops at twenty and says nothing
        // reads as "these are all of them", which is the one thing it is not.
        if (hidden > 0) {
            Text(
                text = pluralStringResource(R.plurals.rows_not_shown, hidden, hidden),
                style = typography.labelSmall,
                color = colors.mist,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Where the fill has reached the share label at the end of the track.
 *
 * Measured against the label's own width rather than guessed: labelSmall at this
 * size is ~34dp for "56.8%", and the widest bar is the full track, so anything
 * past roughly nine tenths puts ink under the text.
 */
private const val LABEL_OVER_FILL = 0.9f

/** "56.8%" for anything meaningful, "<0.1%" rather than a misleading "0.0%". */
private fun formatShare(share: Float): String {
    val percent = share * 100
    return when {
        percent >= 10f -> "${percent.toInt()}%"
        percent >= 0.1f -> String.format(java.util.Locale.ROOT, "%.1f%%", percent)
        percent > 0f -> "<0.1%"
        else -> "0%"
    }
}

/**
 * Daily spend as columns, one per day in the range, quiet days included.
 *
 * Emphasis rather than a colour scale: the field is recessive, the biggest day is
 * inked, and today is accented. That is the same visual grammar as the tap bar on
 * the home screen, so the two read as one system.
 */
@Composable
fun SpendColumns(
    values: List<Long>,
    modifier: Modifier = Modifier,
    emphasisIndex: Int? = null,
    accentIndex: Int? = null,
    height: androidx.compose.ui.unit.Dp = 96.dp,
    /** Given the index of the day tapped. A whole month of columns is a hit-test. */
    onSelectIndex: ((Int) -> Unit)? = null
) {
    val colors = SpendTheme.colors
    if (values.isEmpty()) return

    val peak = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val columnsDescription =
        "Daily spending over ${values.size} days, total ${money(values.sum())}" +
            (if (onSelectIndex != null) ". Tap a day to see its payments" else "")

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = columnsDescription }
            .then(
                if (onSelectIndex == null) Modifier
                else Modifier.pointerInput(values.size) {
                    detectTapGestures { offset ->
                        // Hit-tested by slot rather than by drawn bar: a day with
                        // nothing spent has no bar at all, and it still has to be
                        // openable - "why was this day empty" is a real question.
                        val slot = size.width.toFloat() / values.size
                        val index = (offset.x / slot).toInt().coerceIn(0, values.size - 1)
                        onSelectIndex(index)
                    }
                }
            )
    ) {
        val gap = COLUMN_GAP_PX
        val slot = size.width / values.size
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val radius = CornerRadius(minOf(barWidth / 2, MAX_COLUMN_RADIUS_PX))

        values.forEachIndexed { index, value ->
            // Square root, matching the tap bar: on a linear scale a single ₹8,000
            // rent payment flattens a month of ₹40 chai into invisible stubs, and
            // the shape of the month is the whole point of the chart.
            val fraction = if (value <= 0L) 0f else
                kotlin.math.sqrt(value.toDouble() / peak).toFloat()
            val barHeight = (size.height * fraction).coerceAtLeast(if (value > 0L) MIN_COLUMN_PX else 0f)
            if (barHeight <= 0f) return@forEachIndexed

            drawRoundRect(
                color = when (index) {
                    accentIndex -> colors.split
                    emphasisIndex -> colors.ink
                    else -> colors.leader
                },
                topLeft = Offset(index * slot, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius
            )
        }
    }
}

/**
 * A headline number with a caption. The dashboard leads with these rather than a
 * one-bar chart, because a single value is not a comparison.
 */
@Composable
fun StatTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(modifier = modifier) {
        Text(
            text = value,
            style = if (emphasis) typography.displayMedium else typography.displaySmall,
            color = colors.ink
        )
        Text(
            text = caption,
            style = typography.labelSmall,
            color = colors.graphite,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * A budget as a bullet bar: how much is spent, against where an even spread would
 * have you by today.
 *
 * The marker is the whole idea. "₹4,120 of ₹8,000" looks comfortable and is not,
 * if it is the 6th of the month - and no amount of restyling the fill will say
 * so. A target mark turns one number into a comparison, which is the same move
 * the headline's "up 12% on last month" makes.
 *
 * The marker overshoots the bar top and bottom rather than being drawn inside it,
 * so it stays legible whether the fill has passed it or not.
 */
@Composable
fun BudgetBar(
    fraction: Float,
    paceFraction: Float,
    over: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors

    Canvas(modifier = modifier.fillMaxWidth().height(BUDGET_BAR_HEIGHT)) {
        val barTop = (size.height - BAR_THICKNESS.toPx()) / 2
        val barHeight = BAR_THICKNESS.toPx()
        val radius = CornerRadius(barHeight / 2)

        drawRoundRect(
            color = colors.ruleSoft,
            topLeft = Offset(0f, barTop),
            size = Size(size.width, barHeight),
            cornerRadius = radius
        )

        val fillWidth = size.width * fraction.coerceIn(0f, 1f)
        if (fillWidth > 0f) {
            drawRoundRect(
                // `review` is the app's existing "this wants your attention"
                // colour - the same one a low-confidence row and a failed payment
                // carry. It is a state, not a category, so it does not break the
                // rule that nothing in this file uses colour to tell series apart.
                color = if (over) colors.review else colors.ink,
                topLeft = Offset(0f, barTop),
                size = Size(fillWidth.coerceAtLeast(barHeight), barHeight),
                cornerRadius = radius
            )
        }

        val markerX = (size.width * paceFraction.coerceIn(0f, 1f))
            .coerceIn(MARKER_WIDTH_PX, size.width - MARKER_WIDTH_PX)
        drawRoundRect(
            color = colors.graphite,
            topLeft = Offset(markerX - MARKER_WIDTH_PX / 2, barTop - MARKER_OVERSHOOT_PX),
            size = Size(MARKER_WIDTH_PX, barHeight + MARKER_OVERSHOOT_PX * 2),
            cornerRadius = CornerRadius(MARKER_WIDTH_PX / 2)
        )
    }
}

/** Shown instead of an empty chart, so a blank area never reads as a broken one. */
@Composable
fun ChartEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SpendTheme.colors.mist,
        modifier = modifier.padding(vertical = 12.dp)
    )
}

private val BAR_THICKNESS = 12.dp

/** Tall enough for the pace marker to overshoot the bar without being clipped. */
private val BUDGET_BAR_HEIGHT = 22.dp
private const val MARKER_WIDTH_PX = 3f
private const val MARKER_OVERSHOOT_PX = 8f

/** 2px of surface between adjacent marks, so they never fuse into one shape. */
private const val COLUMN_GAP_PX = 4f
private const val MIN_COLUMN_PX = 2f
private const val MAX_COLUMN_RADIUS_PX = 4f
