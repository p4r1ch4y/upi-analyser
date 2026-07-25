package com.spendlens.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextTransform
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.ui.components.*
import com.spendlens.ui.theme.SpendLensTheme
import com.spendlens.ui.theme.SpendTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            SpendLensTheme {
                DayStreamScreen()
            }
        }
    }
}

@Composable
fun DayStreamScreen() {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    
    // Mock data for demonstration
    val mockTransactions = listOf(
        MockTransaction("09:12", "Swiggy", "₹250", 25000),
        MockTransaction("10:40", "9822014455@ybl", "₹80", 8000, needsReview = true),
        MockTransaction("13:22", "Blinkit", "₹600", 60000, isSplit = true, paidAmount = "₹2,400"),
        MockTransaction("17:05", "Chai stall", "₹20", 2000),
    )
    
    val tapBarItems = mockTransactions.map { 
        TapBarItem(it.amountMinor, it.isSplit) 
    }
    
    val totalToday = mockTransactions.sumOf { it.amountMinor }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.paper)
            .statusBarsPadding()
    ) {
        
        // Today section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 20.dp, bottom = 4.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "TODAY",
                    style = typography.labelSmall,
                    color = colors.graphite,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    text = "SAT 26 JUL",
                    style = typography.labelSmall,
                    color = colors.graphite
                )
            }
            
            // Hero total
            Text(
                text = formatIndianCurrency(totalToday),
                style = typography.displayLarge,
                color = colors.ink
            )
            
            // Meta
            Text(
                text = stringResource(R.string.taps_merchants, mockTransactions.size, 4),
                style = typography.bodySmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 3.dp)
            )
            
            // Tap bar - the signature
            TapBar(
                transactions = tapBarItems,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
        
        // Transaction list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            items(mockTransactions) { txn ->
                TransactionRow(
                    timestamp = txn.timestamp,
                    merchantName = txn.merchantName,
                    amount = txn.amount,
                    modifier = Modifier.fillMaxWidth(),
                    subRow = when {
                        txn.needsReview -> {
                            { ReviewChip("Name this merchant", onClick = {}) }
                        }
                        txn.isSplit -> {
                            {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Split 4 ways",
                                        style = typography.bodySmall,
                                        color = colors.split
                                    )
                                    Text(
                                        text = "·",
                                        style = typography.bodySmall,
                                        color = colors.mist
                                    )
                                    Text(
                                        text = "${txn.paidAmount} paid",
                                        style = typography.bodySmall,
                                        color = colors.mist
                                    )
                                }
                            }
                        }
                        else -> null
                    }
                )
                
                // Row separator
                if (mockTransactions.indexOf(txn) < mockTransactions.size - 1) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.ruleSoft)
                    )
                }
            }
        }
        
        // Collapsed past day
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.paperSunk)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "FRI 25 JUL",
                        style = typography.labelSmall,
                        color = colors.graphite
                    )
                    Text(
                        text = "₹620",
                        style = typography.displaySmall,
                        color = colors.ink
                    )
                }
                Text(
                    text = "6 taps · 5 merchants",
                    style = typography.bodySmall,
                    color = colors.graphite,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }
}

data class MockTransaction(
    val timestamp: String,
    val merchantName: String,
    val amount: String,
    val amountMinor: Long,
    val needsReview: Boolean = false,
    val isSplit: Boolean = false,
    val paidAmount: String = ""
)

fun formatIndianCurrency(minorUnits: Long): String {
    val major = minorUnits / 100
    val formatted = major.toString()
        .reversed()
        .chunked(3)
        .mapIndexed { index, chunk ->
            if (index == 0) chunk else chunk.take(2)
        }
        .joinToString(",")
        .reversed()
    return "₹$formatted"
}
