package com.spendlens.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.Direction
import com.spendlens.ui.theme.SpendTheme
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Manual entry.
 *
 * Cash exists, and so does every payment rail this app cannot see. Without a way
 * to type one in, the day total is quietly wrong and the user has no way to fix
 * it - which undermines the one number the whole screen is built around.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    onDismiss: () -> Unit,
    onSubmit: (amountMinor: Long, name: String, direction: Direction, note: String?) -> Unit
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Direction.DEBIT) }

    val amountMinor = remember(amountText) { parseAmountMinor(amountText) }
    val canSubmit = amountMinor != null && amountMinor > 0 && name.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.paper
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.add_manual_transaction),
                style = typography.titleLarge,
                color = colors.ink
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DirectionChip(
                    label = stringResource(R.string.direction_spent),
                    selected = direction == Direction.DEBIT,
                    onClick = { direction = Direction.DEBIT }
                )
                DirectionChip(
                    label = stringResource(R.string.direction_received),
                    selected = direction == Direction.CREDIT,
                    onClick = { direction = Direction.CREDIT }
                )
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { char -> char.isDigit() || char == '.' } },
                label = { Text(stringResource(R.string.field_amount)) },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_merchant)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.field_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { amountMinor?.let { onSubmit(it, name.trim(), direction, note.trim()) } },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.graphite,
        modifier = Modifier
            .background(
                color = if (selected) colors.ink else colors.paperSunk,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

/**
 * Typed rupees to exact paise. BigDecimal rather than Double for the same reason
 * the parser uses it: `19.99 * 100` is 1998.9999... in binary floating point.
 */
internal fun parseAmountMinor(text: String): Long? {
    val cleaned = text.trim()
    if (cleaned.isEmpty()) return null
    return try {
        BigDecimal(cleaned)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
            .takeIf { it >= 0 }
    } catch (_: NumberFormatException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

/** Row of import options. Kept beside manual entry because both are "fill the gaps". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    smsSupported: Boolean,
    onDismiss: () -> Unit,
    onRescanNotifications: () -> Unit,
    onImportSms: () -> Unit,
    onImportCsv: () -> Unit
) {
    val colors = SpendTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.paper
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.import_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.ink,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            ImportOption(
                title = stringResource(R.string.import_rescan_title),
                subtitle = stringResource(R.string.import_rescan_body),
                onClick = onRescanNotifications
            )

            if (smsSupported) {
                ImportOption(
                    title = stringResource(R.string.import_sms_title),
                    subtitle = stringResource(R.string.import_sms_body),
                    onClick = onImportSms
                )
            }

            ImportOption(
                title = stringResource(R.string.import_csv_title),
                subtitle = stringResource(R.string.import_csv_body),
                onClick = onImportCsv
            )
        }
    }
}

@Composable
private fun ImportOption(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(text = title, style = typography.bodyMedium, color = colors.ink)
        Text(
            text = subtitle,
            style = typography.bodySmall,
            color = colors.graphite,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** Compact "Add" / "Import" pair that sits above the day hero. */
@Composable
fun EntryActions(
    onAdd: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionPill(text = stringResource(R.string.action_add), onClick = onAdd)
        ActionPill(
            text = if (busy) stringResource(R.string.action_importing)
            else stringResource(R.string.action_import),
            onClick = onImport,
            enabled = !busy
        )
    }
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = SpendTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (enabled) colors.ink else colors.mist,
        modifier = Modifier
            .background(colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
