package com.spendlens.ui.entry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.dp
import com.spendlens.BuildConfig
import com.spendlens.R
import com.spendlens.core.model.MoneyFormat
import com.spendlens.data.SettingsStore
import com.spendlens.ui.theme.SpendTheme
import java.util.Locale

/**
 * Currency, export, and getting hold of the developer.
 *
 * These three sit together because they are all "the app talking to the world
 * outside itself" — and in an app with no network permission, that world is
 * reached only through other apps: the file picker, and the mail client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    currency: String,
    themeMode: String,
    typeface: String,
    exporting: Boolean,
    onDismiss: () -> Unit,
    onCurrency: (String) -> Unit,
    onThemeMode: (String) -> Unit,
    onTypeface: (String) -> Unit,
    onExport: (includeSourceMessages: Boolean) -> Unit,
    onFeedback: () -> Unit,
    onPrivacy: () -> Unit,
    onOpenSite: () -> Unit,
    onCopyEmail: () -> Unit
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var includeSources by remember { mutableStateOf(false) }

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
                .navigationBarsPadding()
        ) {
            // ------------------------------------------------------- currency
            SectionLabel(stringResource(R.string.settings_currency))
            Text(
                text = stringResource(R.string.settings_currency_note),
                style = typography.bodySmall,
                color = colors.graphite,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (code in SettingsStore.COMMON_CURRENCIES) {
                    CurrencyChip(
                        code = code,
                        selected = code == currency,
                        onClick = { onCurrency(code) }
                    )
                }
            }

            // ----------------------------------------------------- appearance
            SectionLabel(stringResource(R.string.settings_appearance), top = 26.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(stringResource(R.string.theme_system), themeMode == SettingsStore.THEME_SYSTEM) {
                    onThemeMode(SettingsStore.THEME_SYSTEM)
                }
                OptionChip(stringResource(R.string.theme_light), themeMode == SettingsStore.THEME_LIGHT) {
                    onThemeMode(SettingsStore.THEME_LIGHT)
                }
                OptionChip(stringResource(R.string.theme_dark), themeMode == SettingsStore.THEME_DARK) {
                    onThemeMode(SettingsStore.THEME_DARK)
                }
            }

            Text(
                text = stringResource(R.string.settings_typeface),
                style = typography.bodySmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionChip(stringResource(R.string.type_grotesque), typeface == SettingsStore.TYPE_GROTESQUE) {
                    onTypeface(SettingsStore.TYPE_GROTESQUE)
                }
                OptionChip(stringResource(R.string.type_serif), typeface == SettingsStore.TYPE_SERIF) {
                    onTypeface(SettingsStore.TYPE_SERIF)
                }
            }
            Text(
                text = stringResource(R.string.settings_typeface_note),
                style = typography.labelSmall,
                color = colors.mist,
                modifier = Modifier.padding(top = 6.dp)
            )

            // -------------------------------------------------------- privacy
            // First, not buried at the bottom: "can this app see my bank
            // messages and phone home with them" is the question a new user
            // actually has, and the answer is the product.
            SectionLabel(stringResource(R.string.settings_privacy), top = 26.dp)
            ActionRow(
                text = stringResource(R.string.settings_privacy_action),
                onClick = onPrivacy
            )

            // --------------------------------------------------------- export
            SectionLabel(stringResource(R.string.settings_export), top = 26.dp)
            Text(
                text = stringResource(R.string.settings_export_note),
                style = typography.bodySmall,
                color = colors.graphite
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clickable { includeSources = !includeSources },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_export_sources),
                        style = typography.bodySmall,
                        color = colors.ink
                    )
                    // Spelled out rather than left as a bare toggle: this is the
                    // difference between a spreadsheet of amounts and a file
                    // containing account numbers and payee phone numbers.
                    Text(
                        text = stringResource(R.string.settings_export_sources_note),
                        style = typography.labelSmall,
                        color = colors.mist,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(
                    checked = includeSources,
                    onCheckedChange = { includeSources = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.paper,
                        checkedTrackColor = colors.split
                    )
                )
            }

            ActionRow(
                text = stringResource(
                    if (exporting) R.string.settings_exporting else R.string.settings_export_action
                ),
                enabled = !exporting,
                onClick = { onExport(includeSources) }
            )

            // ------------------------------------------------------- feedback
            SectionLabel(stringResource(R.string.settings_feedback), top = 26.dp)
            Text(
                text = stringResource(R.string.settings_feedback_note),
                style = typography.bodySmall,
                color = colors.graphite
            )
            ActionRow(
                text = stringResource(R.string.settings_feedback_action),
                onClick = onFeedback
            )

            // Shown plainly as well as wired to intents. If no mail app resolves
            // — or the user simply wants to write from a laptop — the address has
            // to be readable and copyable rather than hidden behind a button that
            // may do nothing.
            ContactLine(
                value = DEVELOPER_EMAIL,
                hint = stringResource(R.string.settings_tap_to_copy),
                onClick = onCopyEmail
            )
            ContactLine(
                value = DEVELOPER_SITE.removePrefix("https://").removeSuffix("/"),
                hint = stringResource(R.string.settings_tap_to_open),
                onClick = onOpenSite
            )

            // ---------------------------------------------------------- about
            Text(
                text = stringResource(
                    R.string.settings_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.FLAVOR
                ),
                style = typography.labelSmall,
                color = colors.mist,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, top: androidx.compose.ui.unit.Dp = 8.dp) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = SpendTheme.colors.graphite,
        modifier = Modifier.padding(top = top, bottom = 6.dp)
    )
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.ink,
        modifier = Modifier
            .background(if (selected) colors.ink else colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun CurrencyChip(code: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        // The symbol beside the code, so the choice is obvious without guessing
        // what "MYR" looks like once it is formatting your day total. Currencies
        // whose "symbol" is just the code render once, not as "AED AED".
        text = MoneyFormat.symbolFor(code).trim().let { symbol ->
            if (symbol == code) code else "$symbol $code"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.ink,
        modifier = Modifier
            .background(if (selected) colors.ink else colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

@Composable
private fun ContactLine(value: String, hint: String, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = value, style = typography.bodySmall, color = colors.ink)
        Text(text = hint, style = typography.labelSmall, color = colors.mist)
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = SpendTheme.colors
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (enabled) colors.split else colors.mist,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

/**
 * Opens the user's mail client with a pre-filled report.
 *
 * `ACTION_SENDTO` with a `mailto:` URI, which only mail apps can handle — a plain
 * `ACTION_SEND` would offer to share the report through every app on the phone,
 * which for a message about someone's finance app is the wrong list.
 *
 * The body carries the build and device only. Nothing about the user's ledger
 * goes in, and they see the whole draft in their mail client before it is sent —
 * which matters in an app whose entire claim is that it cannot transmit anything.
 */
fun sendFeedback(context: Context): Boolean {
    val subject = "SpendLens ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}) — feedback"
    val body = buildString {
        appendLine()
        appendLine()
        appendLine("---")
        appendLine("Please describe what happened above. If it is a parsing problem,")
        appendLine("open the payment, copy the text under Source, and change the")
        appendLine("amounts, account digits and names before pasting it here.")
        appendLine()
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}, build ${BuildConfig.VERSION_CODE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }

    // Attempted rather than pre-checked: resolveActivity is only as truthful as
    // the <queries> declaration behind it, and a wrong one reports "no email app"
    // on a phone that plainly has one.
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Opens the project site in whatever browser the user has. */
fun openSite(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_SITE))
    return runCatching { context.startActivity(intent) }.isSuccess
}

const val DEVELOPER_EMAIL = "iamcsubrata@gmail.com"
const val DEVELOPER_SITE = "https://p4r1ch4y.github.io/SpendLens/"
