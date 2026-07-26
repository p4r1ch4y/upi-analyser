package com.spendlens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spendlens.ui.theme.SpendTheme

/**
 * Transaction row with dotted leader line.
 * Receipt grammar: timestamp, merchant, leader, amount.
 */
@Composable
fun TransactionRow(
    timestamp: String,
    merchantName: String,
    amount: String,
    modifier: Modifier = Modifier,
    amountColor: Color = SpendTheme.colors.ink,
    subRow: @Composable (() -> Unit)? = null
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timestamp (38dp width)
            Text(
                text = timestamp,
                style = typography.labelMedium,
                color = colors.mist,
                modifier = Modifier.width(38.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            // Merchant name. Width-capped so a long VPA cannot squeeze the leader
            // out entirely and break the receipt grammar.
            Text(
                text = merchantName,
                style = typography.bodyMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )

            // Dotted leader line
            Spacer(
                modifier = Modifier
                    .weight(1f)
                    .height(20.dp)
                    .padding(horizontal = 4.dp)
                    .drawBehind {
                        val y = size.height - 3.dp.toPx()  // Offset -3dp from baseline
                        val dotSpacing = 4.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            drawCircle(
                                color = colors.leader,
                                radius = 0.5.dp.toPx(),
                                center = Offset(x, y)
                            )
                            x += dotSpacing
                        }
                    }
            )

            // Amount (right-aligned, tabular figures)
            Text(
                text = amount,
                style = typography.bodyLarge,  // Has tnum
                color = amountColor,
                textAlign = TextAlign.End
            )
        }

        // Sub-row (for splits, review chips, etc.)
        if (subRow != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 44.dp, bottom = 8.dp)
            ) {
                subRow()
            }
        }
    }
}

/**
 * Review chip - amber inline prompt.
 *
 * Takes no click handler of its own: the caller decides whether it is tappable,
 * so the same chip can render inert in a preview or a read-only list.
 */
@Composable
fun ReviewChip(
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Text(
        text = text,
        style = typography.bodySmall,
        color = colors.review,
        modifier = modifier
            .background(colors.reviewBg, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}
