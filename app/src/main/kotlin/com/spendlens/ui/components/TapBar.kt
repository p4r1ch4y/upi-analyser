package com.spendlens.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spendlens.ui.theme.SpendTheme
import kotlin.math.sqrt

/**
 * The signature tap bar - one mark per transaction.
 * Square-root scaling so small payments stay visible.
 * This is the visual that makes the product distinctive.
 */
@Composable
fun TapBar(
    transactions: List<TapBarItem>,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    
    if (transactions.isEmpty()) {
        Box(modifier = modifier.height(30.dp))
        return
    }

    // Calculate day max for scaling
    val dayMax = transactions.maxOfOrNull { it.amountMinor } ?: 1L
    val median = transactions.map { it.amountMinor }.sorted()
        .let { it.getOrNull(it.size / 2) ?: 0L }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val markWidth = 4.dp.toPx()
        val gapWidth = 3.dp.toPx()
        val maxHeight = 30.dp.toPx()
        val minHeight = 5.dp.toPx()

        transactions.forEachIndexed { index, item ->
            // Square-root scaling: small payments stay visible
            val normalized = sqrt(item.amountMinor.toFloat() / dayMax)
            val height = (minHeight + (maxHeight - minHeight) * normalized)
                .coerceIn(minHeight, maxHeight)

            // Color: mist below median, ink above, split for group transactions
            val color = when {
                item.isSplit -> colors.split
                item.amountMinor >= median -> colors.ink
                else -> colors.mist
            }

            val x = index * (markWidth + gapWidth)
            val y = maxHeight - height

            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = androidx.compose.ui.geometry.Size(markWidth, height)
            )
        }
    }
}

data class TapBarItem(
    val amountMinor: Long,
    val isSplit: Boolean = false
)
