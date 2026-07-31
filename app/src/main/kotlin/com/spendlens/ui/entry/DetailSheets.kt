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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.core.model.MoneyFormat
import com.spendlens.core.model.Split
import com.spendlens.data.TagRef
import com.spendlens.ui.TagChip
import com.spendlens.ui.theme.SpendTheme
import com.spendlens.ui.theme.money
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HEADER_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.ENGLISH)

/** The message a row was read out of, as the sheet renders it. */
data class SourceRecord(
    val source: String,
    val origin: String?,
    val body: String,
    val receivedAt: Long,
    val templateId: String?
)

/** Everything the detail sheet needs, gathered by the caller. */
data class TransactionDetail(
    val id: String,
    val occurredAt: Long,
    val displayName: String,
    val counterpartyVpa: String?,
    val amountMinor: Long,
    val isCredit: Boolean,
    val split: Split?,
    val tags: List<TagRef>,
    val note: String? = null,
    val sources: List<SourceRecord> = emptyList()
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
    onRename: () -> Unit,
    onNote: (String?) -> Unit,
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
            // Tappable: the name is the thing most often wrong, because most bank
            // messages never carry one.
            Text(
                text = detail.displayName,
                style = typography.titleLarge,
                color = colors.ink,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable(onClick = onRename)
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
                    text = (if (detail.isCredit) "+" else "") + money(detail.amountMinor),
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

            // --------------------------------------------------------- note
            // The remark typed in a UPI app never reaches the notification or the
            // SMS, so this is the only route by which that context ever arrives.
            var noteDraft by remember(detail.id) { mutableStateOf(detail.note.orEmpty()) }
            Text(
                text = stringResource(R.string.note_label).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 22.dp, bottom = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    placeholder = { Text(stringResource(R.string.note_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                SheetButton(
                    text = stringResource(R.string.action_save),
                    enabled = noteDraft.trim() != detail.note.orEmpty(),
                    onClick = { onNote(noteDraft.trim().takeIf { it.isNotEmpty() }) }
                )
            }

            // ------------------------------------------------------------ source
            SourceSection(detail.sources, zone)

            // ----------------------------------------------------------- actions
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetButton(
                    text = stringResource(R.string.action_rename),
                    onClick = onRename,
                    modifier = Modifier.weight(1f)
                )
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
/**
 * The message this row was read out of, verbatim.
 *
 * The point is auditability. Every other number on this sheet is the parser's
 * conclusion; this is the evidence, so a wrong amount or a mislabelled merchant
 * can be seen for what it is instead of being taken on faith. A payment caught on
 * two rails shows both messages, which is also the clearest possible explanation
 * of why it appears once rather than twice.
 */
@Composable
private fun SourceSection(sources: List<SourceRecord>, zone: ZoneId) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Text(
        text = stringResource(R.string.source).uppercase(Locale.ROOT),
        style = typography.labelSmall,
        color = colors.graphite,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp)
    )

    if (sources.isEmpty()) {
        Text(
            text = stringResource(R.string.source_none),
            style = typography.bodySmall,
            color = colors.mist
        )
        return
    }

    for (record in sources) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .background(colors.paperSunk, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = sourceLabel(record),
                    style = typography.labelSmall,
                    color = colors.graphite
                )
                Text(
                    text = Instant.ofEpochMilli(record.receivedAt).atZone(zone).format(HEADER_FORMAT),
                    style = typography.labelSmall,
                    color = colors.mist
                )
            }
            // The body is what the bank or app actually wrote, so it is shown
            // unwrapped and unedited - no truncation, no tidying.
            Text(
                text = record.body,
                style = typography.bodySmall,
                color = colors.ink,
                modifier = Modifier.padding(top = 6.dp)
            )
            record.templateId?.let {
                Text(
                    text = stringResource(R.string.source_matched_by, it),
                    style = typography.labelSmall,
                    color = colors.mist,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** "Notification · com.phonepe.app", "SMS · VK-HDFCBK", "Typed in by you". */
@Composable
private fun sourceLabel(record: SourceRecord): String {
    val kind = when (record.source) {
        "NOTIFICATION" -> stringResource(R.string.source_notification)
        "SMS" -> stringResource(R.string.source_sms)
        "STATEMENT" -> stringResource(R.string.source_statement)
        else -> stringResource(R.string.source_manual)
    }
    return record.origin?.let { "$kind · $it" } ?: kind
}

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
                money(split.totalMinor),
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
                money(split.myShareMinor),
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
                    text = money(share.amountMinor),
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
                    money(split.owedToMeMinor),
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
                text = stringResource(R.string.split_total, money(totalMinor)),
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
                    money(Split.evenly(totalMinor, parsedNames).myShareMinor)
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

/**
 * Renames one payment.
 *
 * The common case by a wide margin: most bank SMS never says who was paid, so the
 * ledger can only show which bank moved the money and the user is the sole source
 * of the actual answer. Where the payment does carry a VPA this writes a *rule*
 * instead, which is replayed over every past and future payment to the same
 * address - so the sheet says which of the two is about to happen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenameSheet(
    currentName: String,
    counterpartyVpa: String?,
    /** How many other unnamed payments share this label and amount. */
    similarCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (name: String, applyToSimilar: Boolean) -> Unit
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(currentName) }
    // Defaults on when there is a cluster: with hundreds of identical-looking
    // bank rows, naming one at a time is the behaviour nobody wants.
    var applyToSimilar by remember { mutableStateOf(similarCount > 0) }

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
                text = stringResource(R.string.rename_title),
                style = typography.titleLarge,
                color = colors.ink
            )
            Text(
                text = counterpartyVpa
                    ?.let { stringResource(R.string.rename_note_vpa, it) }
                    ?: stringResource(R.string.rename_note),
                style = typography.bodySmall,
                color = colors.graphite
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rename_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (similarCount > 0 && counterpartyVpa == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { applyToSimilar = !applyToSimilar },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = stringResource(R.string.rename_similar, similarCount),
                            style = typography.bodySmall,
                            color = colors.ink
                        )
                        Text(
                            text = stringResource(R.string.rename_similar_note),
                            style = typography.labelSmall,
                            color = colors.mist,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(
                        checked = applyToSimilar,
                        onCheckedChange = { applyToSimilar = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.paper,
                            checkedTrackColor = colors.split
                        )
                    )
                }
            }

            Button(
                onClick = { onConfirm(name.trim(), applyToSimilar) },
                enabled = name.isNotBlank() && name.trim() != currentName,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_rename))
            }
        }
    }
}
