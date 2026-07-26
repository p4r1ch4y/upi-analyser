package com.spendlens.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.window.DialogProperties
import com.spendlens.R
import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.MoneyFormat
import com.spendlens.ui.theme.SpendTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the user typed. Grouped so the sheet has one submit callback, not six. */
data class ManualEntry(
    val amountMinor: Long,
    val displayName: String,
    val direction: Direction,
    val channel: Channel,
    val occurredAt: Long,
    val note: String?
)

private val DATE_LABEL = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * Payment types offered for manual entry.
 *
 * These are the [Channel] values a person can actually distinguish about their own
 * spending. `UNKNOWN` is deliberately absent - the automated rails use it when a
 * message does not say, but someone typing an entry in always knows how they paid,
 * and offering "Unknown" only invites a shrug that makes the row less useful later.
 */
private val PAYMENT_TYPES = listOf(
    Channel.UPI to R.string.channel_upi,
    Channel.CASH to R.string.channel_cash,
    Channel.CARD to R.string.channel_card,
    Channel.ATM to R.string.channel_atm,
    Channel.NEFT to R.string.channel_bank_transfer
)

/**
 * Manual entry.
 *
 * Cash exists, and so does every payment rail this app cannot see. Without a way
 * to type one in, the day total is quietly wrong and the user has no way to fix
 * it - which undermines the one number the whole screen is built around.
 *
 * Date and time are editable because the common case for typing an entry is
 * remembering a payment *later* - the ₹20 chai from this morning, yesterday's
 * cash auto fare. Defaulting to now and forcing it would file those on the wrong
 * day and quietly corrupt every daily total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    onDismiss: () -> Unit,
    onSubmit: (ManualEntry) -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf(Direction.DEBIT) }
    var channel by remember { mutableStateOf(Channel.UPI) }

    val nowLocal = remember { LocalDateTime.now(zone) }
    var date by remember { mutableStateOf(nowLocal.toLocalDate()) }
    var time by remember { mutableStateOf(nowLocal.toLocalTime().withSecond(0).withNano(0)) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
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
                Chip(
                    label = stringResource(R.string.direction_spent),
                    selected = direction == Direction.DEBIT,
                    onClick = { direction = Direction.DEBIT }
                )
                Chip(
                    label = stringResource(R.string.direction_received),
                    selected = direction == Direction.CREDIT,
                    onClick = { direction = Direction.CREDIT }
                )
            }

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { char -> char.isDigit() || char == '.' } },
                label = { Text(stringResource(R.string.field_amount)) },
                prefix = { Text(MoneyFormat.symbolFor(MoneyFormat.displayCurrency).trim()) },
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

            FieldLabel(stringResource(R.string.field_payment_type))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in PAYMENT_TYPES) {
                    Chip(
                        label = stringResource(label),
                        selected = channel == value,
                        onClick = { channel = value }
                    )
                }
            }

            FieldLabel(stringResource(R.string.field_when))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Chip(
                    label = date.format(DATE_LABEL),
                    selected = false,
                    onClick = { showDatePicker = true }
                )
                Chip(
                    label = time.format(TIME_LABEL),
                    selected = false,
                    onClick = { showTimePicker = true }
                )
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.field_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val occurredAt = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                    onSubmit(
                        ManualEntry(
                            amountMinor = amountMinor ?: return@Button,
                            displayName = name.trim(),
                            direction = direction,
                            channel = channel,
                            occurredAt = occurredAt,
                            note = note.trim().takeIf { it.isNotEmpty() }
                        )
                    )
                },
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

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            confirmButton = {
                TextButton(onClick = {
                    // The picker reports UTC midnight for the day the user tapped,
                    // so it is read back in UTC. Converting through the local zone
                    // would shift the date by one either side of midnight.
                    state.selectedDateMillis?.let {
                        date = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }

    if (showTimePicker) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true
        )
        DatePickerDialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            confirmButton = {
                TextButton(onClick = {
                    time = LocalTime.of(state.hour, state.minute)
                    showTimePicker = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TimePicker(state = state)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = SpendTheme.colors.graphite,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.ink,
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
    onMore: () -> Unit,
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
        ActionPill(text = stringResource(R.string.action_more), onClick = onMore)
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
