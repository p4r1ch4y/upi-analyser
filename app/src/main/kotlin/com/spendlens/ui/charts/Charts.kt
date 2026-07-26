package com.spendlens.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.spendlens.core.model.MoneyFormat
import com.spendlens.ui.theme.SpendTheme

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
    val emphasis: Boolean = false
)

/**
 * Ranked horizontal bars.
 *
 * Horizontal because the labels are merchant names and tag names, which are long
 * and would be turned on their side or truncated by a column chart.
 */
@Composable
fun RankedBars(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    maxRows: Int = 8
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    if (data.isEmpty()) return

    val rows = data.take(maxRows)
    val peak = rows.maxOf { it.valueMinor }.coerceAtLeast(1L)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            val fraction = (row.valueMinor.toDouble() / peak).toFloat().coerceIn(0f, 1f)
            Column(modifier = Modifier.fillMaxWidth()) {
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
                    // Every bar is direct-labelled. The recessive fill sits below
                    // 3:1 against the surface on purpose, and a visible value is
                    // what makes that legal rather than merely quiet.
                    Text(
                        text = MoneyFormat.rupees(row.valueMinor),
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

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BAR_THICKNESS)
                        .padding(top = 5.dp)
                        .semantics {
                            contentDescription = "${row.label}: ${MoneyFormat.rupees(row.valueMinor)}"
                        }
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
            }
        }
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
    height: androidx.compose.ui.unit.Dp = 96.dp
) {
    val colors = SpendTheme.colors
    if (values.isEmpty()) return

    val peak = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val total = values.sum()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription =
                    "Daily spending over ${values.size} days, total ${MoneyFormat.rupees(total)}"
            }
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

/** 2px of surface between adjacent marks, so they never fuse into one shape. */
private const val COLUMN_GAP_PX = 4f
private const val MIN_COLUMN_PX = 2f
private const val MAX_COLUMN_RADIUS_PX = 4f
