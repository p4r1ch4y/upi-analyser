package com.spendlens.ui.entry

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spendlens.R
import com.spendlens.core.model.Channel
import com.spendlens.core.model.Direction
import com.spendlens.core.model.MoneyFormat
import com.spendlens.data.SharedReceiptReader
import com.spendlens.ui.theme.SpendTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

/**
 * A receipt shared in from a UPI app that the parser could not read.
 *
 * The image is a file inside this app's own cache, staged by the share receiver.
 * Nothing here is asserted as fact - it is what the share carried, offered to the
 * person who can actually read the picture.
 */
data class SharedReceiptPrefill(
    val imagePath: String?,
    /** When the screenshot was taken, which is far closer to payment time than now. */
    val takenAt: Long?,
    /** Any text that came with the share and did not parse. */
    val text: String?
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
    zone: ZoneId = ZoneId.systemDefault(),
    receipt: SharedReceiptPrefill? = null
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var amountText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // Text the share carried but the parser could not read is still the user's
    // own remark more often than not, so it starts in the note rather than being
    // dropped. Trimmed to a line: a whole pasted receipt is not a note.
    var note by remember { mutableStateOf(receipt?.text?.firstLine().orEmpty()) }
    var direction by remember { mutableStateOf(Direction.DEBIT) }
    var channel by remember { mutableStateOf(Channel.UPI) }

    // A shared screenshot is taken seconds after the payment; the share itself
    // may be the following evening. The screenshot's own timestamp is therefore
    // the better default, and getting this wrong files the payment on the wrong
    // day and quietly corrupts that day's total.
    val startLocal = remember(receipt?.takenAt) {
        receipt?.takenAt
            ?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
            ?: LocalDateTime.now(zone)
    }
    var date by remember { mutableStateOf(startLocal.toLocalDate()) }
    var time by remember { mutableStateOf(startLocal.toLocalTime().withSecond(0).withNano(0)) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showFullReceipt by remember { mutableStateOf(false) }

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
                text = stringResource(
                    if (receipt == null) R.string.add_manual_transaction
                    else R.string.receipt_title
                ),
                style = typography.titleLarge,
                color = colors.ink
            )

            if (receipt != null) {
                Text(
                    // Only an image gets the apology. A share that carried text
                    // the parser could not use is a different failure, and saying
                    // "cannot read images" about it would be a lie.
                    text = stringResource(
                        if (receipt.imagePath != null) R.string.receipt_cannot_read
                        else R.string.receipt_read_some
                    ),
                    style = typography.bodySmall,
                    color = colors.graphite
                )
                receipt.imagePath?.let { path ->
                    ReceiptPreview(
                        path = path,
                        onExpand = { showFullReceipt = true }
                    )
                }
                if (receipt.takenAt != null) {
                    Text(
                        text = stringResource(R.string.receipt_time_from_image),
                        style = typography.labelSmall,
                        color = colors.mist
                    )
                }
            }

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

    if (showFullReceipt) {
        receipt?.imagePath?.let { path ->
            FullReceiptDialog(path = path, onDismiss = { showFullReceipt = false })
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

/**
 * The shared receipt, inside the form.
 *
 * This is the whole fix. The previous behaviour opened manual entry *over* the
 * screenshot and described it as leaving the receipt "still on screen behind it",
 * which it is not - the form covers it, and the user is left retyping an amount
 * from memory or bouncing between two apps. Showing it here costs one decode and
 * removes the bounce entirely.
 *
 * Cropped to the top third at a fixed height, because a UPI receipt puts the
 * amount and the payee in the first few centimetres and the rest is a reference
 * number nobody types in. The whole image is one tap away.
 */
@Composable
private fun ReceiptPreview(path: String, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val density = LocalDensity.current
    val maxPixels = remember(density) { with(density) { RECEIPT_PREVIEW_WIDTH.roundToPx() } * 2 }

    // Decoded off the main thread: a 1440x3200 screenshot is real work, and doing
    // it in composition drops the frame the sheet animates in on.
    val bitmap by produceState<Bitmap?>(initialValue = null, path, maxPixels) {
        value = withContext(Dispatchers.IO) {
            SharedReceiptReader.decode(File(path), maxPixels)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RECEIPT_PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.paperSunk)
                .clickable(onClick = onExpand),
            contentAlignment = Alignment.TopCenter
        ) {
            bitmap?.let { image ->
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.receipt_title),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            text = stringResource(R.string.receipt_expand),
            style = typography.labelSmall,
            color = colors.mist,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * The receipt at full size, over everything.
 *
 * A dialog rather than another sheet: the point is to read a picture, and every
 * pixel the form would keep is a pixel of the receipt the user cannot see.
 */
@Composable
private fun FullReceiptDialog(path: String, onDismiss: () -> Unit) {
    val colors = SpendTheme.colors
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxPixels = remember(configuration, density) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }
    }

    val bitmap by produceState<Bitmap?>(initialValue = null, path, maxPixels) {
        value = withContext(Dispatchers.IO) { SharedReceiptReader.decode(File(path), maxPixels) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.paper)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { image ->
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.receipt_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            }
            Text(
                text = stringResource(R.string.receipt_close),
                style = MaterialTheme.typography.bodySmall,
                color = colors.paper,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
                    .background(colors.ink, RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}

/** The first non-blank line, for text that starts life as a whole receipt. */
private fun String.firstLine(): String =
    lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(120)

private val RECEIPT_PREVIEW_HEIGHT = 190.dp
private val RECEIPT_PREVIEW_WIDTH = 360.dp

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
