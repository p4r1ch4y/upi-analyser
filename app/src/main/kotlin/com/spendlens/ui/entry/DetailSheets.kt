package com.spendlens.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.MoneyFormat
import com.spendlens.core.model.Split
import com.spendlens.data.TagRef
import com.spendlens.ui.TagChip
import com.spendlens.ui.theme.SpendTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** Everything the detail sheet needs, gathered by the caller. */
data class TransactionDetail(
    val id: String,
    val occurredAt: Long,
    val displayName: String,
    val counterpartyVpa: String?,
    val amountMinor: Long,
    val isCredit: Boolean,
    val split: Split?,
    val tags: List<TagRef>
)

/**
 * One payment, opened.
 *
 * When it is split, the sheet leads with the distinction the stream compresses:
 * what left your account, and what of it was actually yours.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(
    detail: TransactionDetail,
    allTags: List<TagRef>,
    onDismiss: () -> Unit,
    onSplit: () -> Unit,
    onRemoveSplit: () -> Unit,
    onToggleSettled: (index: Int, settled: Boolean) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onDelete: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tagDraft by remember { mutableStateOf("") }

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
                .navigationBarsPadding()
        ) {
            Text(
                text = Instant.ofEpochMilli(detail.occurredAt).atZone(zone)
                    .format(HEADER_FORMAT).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite
            )
            Text(
                text = detail.displayName,
                style = typography.titleLarge,
                color = colors.ink,
                modifier = Modifier.padding(top = 4.dp)
            )
            detail.counterpartyVpa?.let {
                Text(text = it, style = typography.bodySmall, color = colors.mist)
            }

            if (detail.split != null) {
                SplitSummaryBox(detail.split, modifier = Modifier.padding(top = 18.dp))
                ParticipantList(
                    split = detail.split,
                    onToggleSettled = onToggleSettled,
                    modifier = Modifier.padding(top = 20.dp)
                )
            } else {
                Text(
                    text = (if (detail.isCredit) "+" else "") + MoneyFormat.rupees(detail.amountMinor),
                    style = typography.displayMedium,
                    color = if (detail.isCredit) colors.credit else colors.ink,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            // -------------------------------------------------------------- tags
            Text(
                text = stringResource(R.string.tags).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                for (tag in detail.tags) {
                    TagChip(
                        name = tag.name,
                        isTrip = tag.isTrip,
                        modifier = Modifier.clickable { onRemoveTag(tag.id) }
                    )
                }
                for (tag in allTags.filter { existing -> detail.tags.none { it.id == existing.id } }.take(4)) {
                    SuggestedTag(tag.name) { onAddTag(tag.name) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tagDraft,
                    onValueChange = { tagDraft = it },
                    label = { Text(stringResource(R.string.add_tag)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                SheetButton(
                    text = stringResource(R.string.action_add_short),
                    enabled = tagDraft.isNotBlank(),
                    onClick = {
                        onAddTag(tagDraft.trim())
                        tagDraft = ""
                    }
                )
            }

            // ----------------------------------------------------------- actions
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetButton(
                    text = stringResource(
                        if (detail.split == null) R.string.action_split else R.string.action_unsplit
                    ),
                    onClick = { if (detail.split == null) onSplit() else onRemoveSplit() },
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.paperSunk,
                        contentColor = colors.ink
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
}

/**
 * "You paid" against "Your share".
 *
 * These are the two numbers a split payment has, and conflating them is what makes
 * a tracker tell someone who fronted a group dinner that they spent ₹8,000 on food.
 */
@Composable
private fun SplitSummaryBox(split: Split, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.paperSunk)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(stringResource(R.string.you_paid), style = typography.bodySmall, color = colors.graphite)
            Text(
                MoneyFormat.rupees(split.totalMinor),
                style = typography.bodyMedium,
                color = colors.graphite
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.your_share), style = typography.bodySmall, color = colors.ink)
            Text(
                MoneyFormat.rupees(split.myShareMinor),
                style = typography.displaySmall,
                color = colors.ink
            )
        }
    }
}

@Composable
private fun ParticipantList(
    split: Split,
    onToggleSettled: (index: Int, settled: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.split_n_ways, split.wayCount).uppercase(Locale.ROOT),
            style = typography.labelSmall,
            color = colors.graphite,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        split.shares.forEachIndexed { index, share ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !share.isMe) { onToggleSettled(index, !share.isSettled) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(if (share.isMe) colors.split else colors.ruleSoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = share.name.take(1).uppercase(Locale.ROOT),
                        style = typography.labelSmall,
                        color = if (share.isMe) colors.paper else colors.graphite
                    )
                }
                Text(
                    text = share.name,
                    style = typography.bodySmall,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                // Settled shares are struck through rather than removed, so the
                // split still shows what it was, not just what is left.
                Text(
                    text = MoneyFormat.rupees(share.amountMinor),
                    style = typography.bodySmall,
                    color = if (share.isSettled) colors.mist else colors.ink,
                    textDecoration = if (share.isSettled) TextDecoration.LineThrough else null
                )
            }
        }

        if (split.owedToMeMinor > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .background(colors.splitBg, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.owed_to_you), style = typography.bodySmall, color = colors.split)
                Text(
                    MoneyFormat.rupees(split.owedToMeMinor),
                    style = typography.bodySmall,
                    color = colors.split
                )
            }
        }
    }
}

/**
 * Split a set of payments N ways.
 *
 * Names are optional. Someone splitting a bill at the table wants a number, not a
 * form; naming people is for when they intend to chase the money later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitSheet(
    paymentCount: Int,
    totalMinor: Long,
    onDismiss: () -> Unit,
    onConfirm: (names: List<String>) -> Unit
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var ways by remember { mutableStateOf(2) }
    var names by remember { mutableStateOf("") }

    val parsedNames = remember(names, ways) {
        val typed = names.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val others = if (typed.isNotEmpty()) typed else (1 until ways).map { "Person $it" }
        listOf(Split.ME) + others
    }
    val effectiveWays = parsedNames.size

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
                text = stringResource(R.string.split_title, paymentCount),
                style = typography.titleLarge,
                color = colors.ink
            )
            Text(
                text = stringResource(R.string.split_total, MoneyFormat.rupees(totalMinor)),
                style = typography.bodySmall,
                color = colors.graphite
            )

            Text(
                text = stringResource(R.string.split_ways).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in 2..8) {
                    WayChip(
                        label = option.toString(),
                        selected = names.isBlank() && ways == option,
                        onClick = { ways = option; names = "" }
                    )
                }
            }

            OutlinedTextField(
                value = names,
                onValueChange = { names = it },
                label = { Text(stringResource(R.string.split_names)) },
                supportingText = { Text(stringResource(R.string.split_names_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(
                    R.string.split_preview,
                    effectiveWays,
                    MoneyFormat.rupees(Split.evenly(totalMinor, parsedNames).myShareMinor)
                ),
                style = typography.bodySmall,
                color = colors.split
            )

            Button(
                onClick = { onConfirm(parsedNames) },
                enabled = effectiveWays >= 2,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_split))
            }
        }
    }
}

/** Tag a set of payments, optionally as a trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSheet(
    paymentCount: Int,
    existing: List<TagRef>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, isTrip: Boolean) -> Unit
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var name by remember { mutableStateOf("") }
    var isTrip by remember { mutableStateOf(false) }

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
                text = stringResource(R.string.tag_title, paymentCount),
                style = typography.titleLarge,
                color = colors.ink
            )

            if (existing.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    for (tag in existing.take(8)) {
                        TagChip(
                            name = tag.name,
                            isTrip = tag.isTrip,
                            modifier = Modifier.clickable { onConfirm(tag.name, tag.isTrip) }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tag_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WayChip(
                    label = stringResource(R.string.tag_plain),
                    selected = !isTrip,
                    onClick = { isTrip = false }
                )
                WayChip(
                    label = stringResource(R.string.tag_trip),
                    selected = isTrip,
                    onClick = { isTrip = true }
                )
            }
            if (isTrip) {
                Text(
                    text = stringResource(R.string.tag_trip_hint),
                    style = typography.bodySmall,
                    color = colors.graphite
                )
            }

            Button(
                onClick = { onConfirm(name.trim(), isTrip) },
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_tag))
            }
        }
    }
}

@Composable
private fun WayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) colors.paper else colors.ink,
        modifier = Modifier
            .background(if (selected) colors.ink else colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
    )
}

@Composable
private fun SuggestedTag(name: String, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Text(
        text = "+ $name",
        style = MaterialTheme.typography.bodySmall,
        color = colors.mist,
        modifier = Modifier
            .border(1.dp, colors.leader, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun SheetButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = SpendTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.ink,
            contentColor = colors.paper
        ),
        modifier = modifier
    ) {
        Text(text)
    }
}
