package com.spendlens.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.spendlens.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pick a start and an end for the report.
 *
 * The presets answer "recently". This answers the questions a preset cannot -
 * that trip, last April, the stretch between two salary dates - which is the
 * whole reason a fixed 7/30/90/365 eventually frustrates anyone who keeps a
 * ledger for more than a few weeks.
 *
 * Future days are not selectable. A range ending next month would divide a real
 * total by days that have not happened and report a daily average that is simply
 * wrong, and it is easier to forbid than to explain.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangePickerDialog(
    initial: DateWindow?,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
    onClear: (() -> Unit)? = null,
    zone: ZoneId = ZoneId.systemDefault()
) {
    // The picker works in UTC millis: it is a calendar, not an instant, and
    // converting through the local zone shifts the day either side of midnight.
    val utc = ZoneId.of("UTC")
    val todayUtcMillis = LocalDate.now(zone).plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.atStartOfDay(utc)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = initial?.end?.atStartOfDay(utc)?.toInstant()?.toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis < todayUtcMillis
            override fun isSelectableYear(year: Int) = year <= LocalDate.now(zone).year
        }
    )

    val start = state.selectedStartDateMillis?.toLocalDate(utc)
    val end = state.selectedEndDateMillis?.toLocalDate(utc)

    DatePickerDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        confirmButton = {
            TextButton(
                // A single tapped day is a legitimate one-day report, so the end
                // falls back to the start rather than the button staying dead.
                enabled = start != null,
                onClick = { start?.let { onConfirm(it, end ?: it) } }
            ) { Text(stringResource(R.string.action_done)) }
        },
        dismissButton = {
            Column {
                if (onClear != null && initial != null) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.dash_range_clear))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

private fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
