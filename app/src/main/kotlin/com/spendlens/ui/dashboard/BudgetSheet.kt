package com.spendlens.ui.dashboard

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.Budget
import com.spendlens.core.model.BudgetPeriod
import com.spendlens.core.model.BudgetScope
import com.spendlens.core.model.MoneyFormat
import com.spendlens.ui.entry.parseAmountMinor
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money

/** What the sheet hands back. Grouped so there is one submit callback, not six. */
data class BudgetDraft(
    val id: String?,
    val name: String,
    val scope: BudgetScope,
    val scopeValue: String?,
    val period: BudgetPeriod,
    val limitMinor: Long
)

/**
 * Set a budget, or change one.
 *
 * The order of the fields is the order of the decision: what am I limiting, how
 * often does it reset, and only then how much. Asking for the amount first is
 * what makes budget forms feel like homework - the number is meaningless until
 * the scope is fixed, and by then the person has forgotten why they opened it.
 *
 * [suggestLimitMinor] supplies what that scope actually cost last period, which
 * is offered as the starting figure. A blank amount box is the real reason these
 * features go unused: nobody knows what a reasonable limit is for themselves
 * until they are shown one, and the only honest source is what they already did.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetSheet(
    existing: Budget?,
    namesFor: suspend (BudgetScope) -> List<String>,
    suggestLimitMinor: suspend (BudgetScope, String?, BudgetPeriod) -> Long,
    onDismiss: () -> Unit,
    onSubmit: (BudgetDraft) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var scope by remember { mutableStateOf(existing?.scope ?: BudgetScope.TOTAL) }
    var scopeValue by remember { mutableStateOf(existing?.scopeValue) }
    var period by remember { mutableStateOf(existing?.period ?: BudgetPeriod.MONTHLY) }
    var amountText by remember {
        mutableStateOf(existing?.let { MoneyFormat.plain(it.limitMinor) } ?: "")
    }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    /** True until the user types in the amount box, so a suggestion never overwrites them. */
    var amountUntouched by remember { mutableStateOf(existing == null) }

    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    var suggestionMinor by remember { mutableStateOf(0L) }

    LaunchedEffect(scope) {
        if (scope == BudgetScope.TOTAL) {
            choices = emptyList()
            scopeValue = null
            return@LaunchedEffect
        }
        val offered = namesFor(scope)
        // A budget already pointed at this name stays selectable even when the
        // name has dropped off the suggestion window. Otherwise opening a quiet
        // budget to raise its limit would silently clear what it covers, and the
        // save button would go dead for no visible reason.
        val kept = existing
            ?.scopeValue
            ?.takeIf { existing.scope == scope && it !in offered }
        choices = listOfNotNull(kept) + offered
        if (scopeValue !in choices) scopeValue = kept
    }

    LaunchedEffect(scope, scopeValue, period) {
        val ready = scope == BudgetScope.TOTAL || scopeValue != null
        suggestionMinor = if (ready) suggestLimitMinor(scope, scopeValue, period) else 0L
        if (amountUntouched && suggestionMinor > 0L) {
            amountText = MoneyFormat.plain(suggestionMinor)
        }
    }

    val limitMinor = remember(amountText) { parseAmountMinor(amountText) }
    val scopeReady = scope == BudgetScope.TOTAL || scopeValue != null
    val effectiveName = name.trim().ifEmpty { defaultName(scope, scopeValue) }
    val canSubmit = scopeReady && limitMinor != null && limitMinor > 0L && effectiveName.isNotEmpty()

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
                    if (existing == null) R.string.budget_new_title else R.string.budget_edit_title
                ),
                style = typography.titleLarge,
                color = colors.ink
            )

            // ------------------------------------------------------------ scope
            FieldLabel(stringResource(R.string.budget_field_scope))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.budget_scope_total), scope == BudgetScope.TOTAL) {
                    scope = BudgetScope.TOTAL
                    scopeValue = null
                }
                Chip(stringResource(R.string.budget_scope_tag), scope == BudgetScope.TAG) {
                    scope = BudgetScope.TAG
                }
                Chip(stringResource(R.string.budget_scope_merchant), scope == BudgetScope.MERCHANT) {
                    scope = BudgetScope.MERCHANT
                }
            }

            if (scope != BudgetScope.TOTAL) {
                if (choices.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (scope == BudgetScope.TAG) R.string.budget_no_tags
                            else R.string.budget_no_merchants
                        ),
                        style = typography.bodySmall,
                        color = colors.mist
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (choice in choices) {
                            Chip(choice, scopeValue == choice) { scopeValue = choice }
                        }
                    }
                }
            }

            // ----------------------------------------------------------- period
            FieldLabel(stringResource(R.string.budget_field_period))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.budget_period_weekly), period == BudgetPeriod.WEEKLY) {
                    period = BudgetPeriod.WEEKLY
                }
                Chip(stringResource(R.string.budget_period_monthly), period == BudgetPeriod.MONTHLY) {
                    period = BudgetPeriod.MONTHLY
                }
            }
            if (existing == null) {
                Text(
                    text = stringResource(
                        if (period == BudgetPeriod.WEEKLY) R.string.budget_anchor_weekly
                        else R.string.budget_anchor_monthly
                    ),
                    style = typography.labelSmall,
                    color = colors.mist
                )
            }

            // ----------------------------------------------------------- amount
            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountUntouched = false
                    amountText = it.filter { char -> char.isDigit() || char == '.' }
                },
                label = { Text(stringResource(R.string.budget_field_limit)) },
                prefix = { Text(MoneyFormat.symbolFor(MoneyFormat.displayCurrency).trim()) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            if (suggestionMinor > 0L) {
                Text(
                    text = stringResource(
                        if (period == BudgetPeriod.WEEKLY) R.string.budget_suggestion_week
                        else R.string.budget_suggestion_month,
                        money(suggestionMinor)
                    ),
                    style = typography.bodySmall,
                    color = colors.graphite,
                    modifier = Modifier
                        .clickable {
                            amountUntouched = false
                            amountText = MoneyFormat.plain(suggestionMinor)
                        }
                        .padding(vertical = 2.dp)
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.budget_field_name)) },
                placeholder = {
                    Text(
                        text = defaultName(scope, scopeValue),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    onSubmit(
                        BudgetDraft(
                            id = existing?.id,
                            name = effectiveName,
                            scope = scope,
                            scopeValue = scopeValue.takeIf { scope != BudgetScope.TOTAL },
                            period = period,
                            limitMinor = limitMinor ?: return@Button
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

            if (existing != null && onDelete != null) {
                Text(
                    text = stringResource(R.string.budget_delete),
                    style = typography.bodySmall,
                    color = colors.review,
                    modifier = Modifier
                        .clickable { onDelete(existing.id) }
                        .padding(vertical = 8.dp)
                )
            }
        }
    }
}

/** What the budget is called when the user does not bother naming it. */
private fun defaultName(scope: BudgetScope, scopeValue: String?): String = when (scope) {
    BudgetScope.TOTAL -> "Everything"
    BudgetScope.TAG, BudgetScope.MERCHANT -> scopeValue.orEmpty()
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
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(
                color = if (selected) colors.ink else colors.paperSunk,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}
