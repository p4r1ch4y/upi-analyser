package com.spendlens.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.core.model.Budget
import com.spendlens.core.model.BudgetProgress
import com.spendlens.core.model.BudgetScope
import com.spendlens.core.model.MonthBucket
import com.spendlens.core.model.Split
import com.spendlens.service.QuickNoteTile
import com.spendlens.service.ShareReceiverActivity
import com.spendlens.service.TransactionCaptureService
import com.spendlens.service.UpiNotificationListener
import com.spendlens.ui.dashboard.BudgetSheet
import com.spendlens.ui.dashboard.DashboardActions
import com.spendlens.ui.dashboard.DashboardScreen
import com.spendlens.ui.dashboard.DashboardViewModel
import com.spendlens.ui.dashboard.GroupBy
import com.spendlens.ui.dashboard.SliceSelection
import com.spendlens.data.SharedReceiptReader
import com.spendlens.ui.entry.AddTransactionSheet
import com.spendlens.ui.entry.SharedReceiptPrefill
import com.spendlens.ui.entry.ImportSheet
import com.spendlens.ui.entry.MoreSheet
import com.spendlens.ui.entry.PrivacySheet
import com.spendlens.ui.entry.RenameSheet
import com.spendlens.ui.entry.DEVELOPER_EMAIL
import com.spendlens.ui.entry.openSite
import com.spendlens.ui.entry.sendFeedback
import com.spendlens.ui.entry.SourceRecord
import com.spendlens.ui.entry.SplitSheet
import com.spendlens.ui.entry.TagSheet
import com.spendlens.ui.entry.TransactionDetail
import com.spendlens.ui.entry.TransactionDetailSheet
import com.spendlens.ui.theme.SpendLensTheme
import com.spendlens.ui.theme.LocalCurrency
import com.spendlens.ui.theme.SpendTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Tab { STREAM, DASHBOARD }

/**
 * How long "press back again" stays armed.
 *
 * Long enough to be a deliberate second press, short enough that a back tapped a
 * minute later is not read as confirming something the user has forgotten about.
 */
private const val BACK_TO_EXIT_WINDOW_MILLIS = 2_500L

class MainActivity : ComponentActivity() {

    private val viewModel: DayStreamViewModel by viewModels {
        DayStreamViewModel.factory(SpendLensApp.graphOf(this))
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.factory(SpendLensApp.graphOf(this))
    }

    /**
     * Notification access is granted in system Settings, so there is no callback
     * to observe - it is re-read on every resume and drives recomposition.
     */
    private var listenerEnabled by mutableStateOf(false)

    /** Set from the launching intent, and again by onNewIntent. */
    private var pendingAction by mutableStateOf<String?>(null)

    /**
     * The receipt a share handed over, if this launch came from one.
     *
     * Held on the activity rather than in composition so that it survives the
     * recomposition the action triggers, and is cleared in exactly one place -
     * when the form that owns it closes.
     */
    private var pendingReceipt by mutableStateOf<SharedReceiptPrefill?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /**
     * Asks for the inbox and the live feed together.
     *
     * Two permissions, two rails: `READ_SMS` reaches history from before install,
     * `RECEIVE_SMS` is what lets the receiver fire on a message arriving. Asking
     * for only the first left the `full` flavour with a registered `SMS_RECEIVED`
     * receiver that could never fire — the import worked, so the feature looked
     * healthy, while every bank SMS after install was silently missed.
     *
     * The import still runs on `READ_SMS` alone, because a user who grants one
     * and refuses the other should get the history they said yes to.
     */
    private val requestSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.READ_SMS] == true) viewModel.importSmsHistory()
        }

    private val pickStatement =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(viewModel::importCsv)
        }

    /**
     * Set just before the picker opens, because the choice cannot ride along with
     * a CreateDocument contract and the activity may be recreated while the
     * picker is in front of it.
     */
    private var exportIncludesSources = false

    private val createExportFile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            uri?.let { viewModel.exportTo(it, exportIncludesSources) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        listenerEnabled = isNotificationListenerEnabled()
        requestPostNotificationsIfNeeded()
        pendingAction = intent?.action
        pendingReceipt = intent?.readReceiptPrefill()

        setContent {
            // Read as state so a tile tap while the app is already open still
            // routes: onNewIntent updates it and the effect below re-runs.
            val intentAction = pendingAction
            // Collected before the theme so a change repaints everything.
            val themeMode by viewModel.themeMode.collectAsState()
            val typeface by viewModel.typeface.collectAsState()

            SpendLensTheme(themeMode = themeMode, typeface = typeface) {
                val state by viewModel.state.collectAsState()
                val dashboard by dashboardViewModel.state.collectAsState()
                // Collected so a currency change redraws every amount on screen.
                val currency by viewModel.currency.collectAsState()
                val exporting by viewModel.exporting.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val context = LocalContext.current

                var tab by remember { mutableStateOf(Tab.STREAM) }
                var showAddSheet by remember { mutableStateOf(false) }
                var showImportSheet by remember { mutableStateOf(false) }
                var showSplitSheet by remember { mutableStateOf(false) }
                var showTagSheet by remember { mutableStateOf(false) }
                var showMoreSheet by remember { mutableStateOf(false) }
                var showPrivacySheet by remember { mutableStateOf(false) }
                var renameTarget by remember { mutableStateOf<String?>(null) }
                var renameSimilarCount by remember { mutableStateOf(0) }
                var openTxnId by remember { mutableStateOf<String?>(null) }
                // Null means "no sheet"; a present holder means "sheet open", and
                // its budget is null when the sheet is creating rather than editing.
                var budgetSheet by remember { mutableStateOf<BudgetSheetTarget?>(null) }

                // The Quick Settings tile lands here: open the newest payment so
                // the note can be typed while the user still remembers what it
                // was for. Keyed on the intent so a second tap re-opens it.
                LaunchedEffect(intentAction) {
                    when (intentAction) {
                        QuickNoteTile.ACTION_NOTE_LATEST ->
                            viewModel.mostRecentId()?.let { openTxnId = it }
                        TransactionCaptureService.ACTION_ADD_PAYMENT,
                        ShareReceiverActivity.ACTION_ADD_FROM_RECEIPT -> showAddSheet = true
                    }
                }
                var openSplit by remember { mutableStateOf<Split?>(null) }
                var openSources by remember { mutableStateOf<List<SourceRecord>>(emptyList()) }
                var splitTarget by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        // Splits and tags change what every total means, so the
                        // dashboard is stale the moment one is applied.
                        if (event is DayStreamEvent.SplitApplied || event is DayStreamEvent.Tagged) {
                            dashboardViewModel.refresh()
                        }
                        snackbarHostState.showSnackbar(context.describe(event))
                    }
                }

                // Reload the open sheet's split whenever the ledger changes under it.
                LaunchedEffect(openTxnId, state.days) {
                    openSplit = openTxnId?.let { viewModel.splitDetail(it) }
                    openSources = openTxnId?.let { viewModel.sourcesFor(it) }.orEmpty()
                }

                // ------------------------------------------------------- back
                //
                // Back used to close the app from anywhere, which made it a trap
                // while reading: one stray press in the middle of checking a
                // month and the whole screen is gone.
                //
                // So back now unwinds what is actually on screen, innermost
                // first, and only leaves once there is nothing left to undo -
                // and even then it asks. Bottom sheets are absent from this list
                // because ModalBottomSheet consumes back itself before this runs.
                var exitArmed by remember { mutableStateOf(false) }

                LaunchedEffect(exitArmed) {
                    if (!exitArmed) return@LaunchedEffect
                    launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.back_again_to_exit),
                            duration = SnackbarDuration.Short
                        )
                    }
                    delay(BACK_TO_EXIT_WINDOW_MILLIS)
                    exitArmed = false
                }

                BackHandler {
                    when {
                        state.selecting -> viewModel.clearSelection()
                        state.filter != null -> viewModel.clearFilter()
                        state.searching -> viewModel.setQuery("")
                        tab != Tab.STREAM -> tab = Tab.STREAM
                        exitArmed -> finish()
                        else -> exitArmed = true
                    }
                }

                CompositionLocalProvider(LocalCurrency provides currency) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = SpendTheme.colors.paper,
                    bottomBar = {
                        Column {
                            if (state.selecting) {
                                SelectionBar(
                                    count = state.selected.size,
                                    totalMinor = state.selectedTotalMinor,
                                    onSplit = { showSplitSheet = true },
                                    onTag = { showTagSheet = true },
                                    onClear = viewModel::clearSelection
                                )
                            } else {
                                TabBar(current = tab, onSelect = { tab = it })
                            }
                        }
                    }
                ) { padding ->
                    when (tab) {
                        Tab.STREAM -> DayStreamScreen(
                            state = state,
                            actions = DayStreamActions(
                                onNameMerchant = { /* naming sheet arrives with the edit flow */ },
                                onOpenTransaction = { openTxnId = it },
                                onToggleSelect = viewModel::toggleSelection,
                                onToggleDaySelect = viewModel::toggleDaySelection,
                                onToggleDayExpanded = viewModel::toggleDay,
                                onAdd = { showAddSheet = true },
                                onImport = { showImportSheet = true },
                                onMore = { showMoreSheet = true },
                                onQuery = viewModel::setQuery,
                                onClearFilter = viewModel::clearFilter
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = padding.calculateBottomPadding()),
                            header = if (listenerEnabled) null else {
                                { ConnectPrompt(onClick = ::openListenerSettings) }
                            }
                        )

                        Tab.DASHBOARD -> DashboardScreen(
                            state = dashboard,
                            actions = DashboardActions(
                                onRange = dashboardViewModel::setRange,
                                onDirection = dashboardViewModel::setDirection,
                                onGroupBy = dashboardViewModel::setGroupBy,
                                onSortBy = dashboardViewModel::setSortBy,
                                // A bar is a way in, not a dead end: it hands the
                                // stream the group *and* the window, so the two
                                // screens cannot disagree about the same figure.
                                onOpenSlice = { slice ->
                                    viewModel.setFilter(
                                        slice.toStreamFilter(
                                            context.getString(
                                                R.string.filter_range_days,
                                                dashboard.range.days
                                            )
                                        )
                                    )
                                    tab = Tab.STREAM
                                },
                                onOpenBudget = { progress ->
                                    viewModel.setFilter(progress.toStreamFilter())
                                    tab = Tab.STREAM
                                },
                                onEditBudget = { budgetSheet = BudgetSheetTarget(it.budget) },
                                onNewBudget = { budgetSheet = BudgetSheetTarget(null) },
                                onOpenDay = { date ->
                                    viewModel.setFilter(
                                        dayFilter(date, context.getString(R.string.filter_kind_day))
                                    )
                                    tab = Tab.STREAM
                                },
                                onOpenMonth = { month ->
                                    viewModel.setFilter(month.toStreamFilter())
                                    tab = Tab.STREAM
                                }
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = padding.calculateBottomPadding())
                        )
                    }
                }

                // ------------------------------------------------------- sheets

                openTxnId?.let { id ->
                    state.transaction(id)?.let { txn ->
                        TransactionDetailSheet(
                            detail = TransactionDetail(
                                id = txn.id,
                                occurredAt = txn.occurredAt,
                                displayName = txn.displayName,
                                counterpartyVpa = txn.counterpartyVpa,
                                amountMinor = txn.amountMinor,
                                isCredit = txn.isCredit,
                                split = openSplit,
                                tags = txn.tags,
                                note = txn.note,
                                sources = openSources
                            ),
                            allTags = state.allTags,
                            onDismiss = { openTxnId = null; openSplit = null; openSources = emptyList() },
                            onSplit = { splitTarget = id; showSplitSheet = true },
                            onRemoveSplit = {
                                viewModel.removeSplit(id)
                                openSplit = null
                                dashboardViewModel.refresh()
                            },
                            onToggleSettled = { index, settled ->
                                viewModel.setSettled(id, index, settled)
                            },
                            onAddTag = { name ->
                                viewModel.tagOne(id, name)
                                dashboardViewModel.refresh()
                            },
                            onRemoveTag = { tagId ->
                                viewModel.untag(id, tagId)
                                dashboardViewModel.refresh()
                            },
                            onDelete = {
                                viewModel.delete(id)
                                openTxnId = null
                            },
                            onRename = { renameTarget = id },
                            onNote = { viewModel.setNote(id, it) }
                        )
                    }
                }

                if (showSplitSheet) {
                    val single = splitTarget?.let { state.transaction(it) }
                    SplitSheet(
                        paymentCount = if (single != null) 1 else state.selected.size,
                        totalMinor = single?.amountMinor ?: state.selectedTotalMinor,
                        onDismiss = { showSplitSheet = false; splitTarget = null },
                        onConfirm = { names ->
                            if (single != null) viewModel.splitOne(single.id, names)
                            else viewModel.splitSelected(names.size, names)
                            showSplitSheet = false
                            splitTarget = null
                            dashboardViewModel.refresh()
                        }
                    )
                }

                budgetSheet?.let { target ->
                    BudgetSheet(
                        existing = target.budget,
                        namesFor = dashboardViewModel::budgetableNames,
                        suggestLimitMinor = dashboardViewModel::suggestedLimitMinor,
                        onDismiss = { budgetSheet = null },
                        onSubmit = { draft ->
                            dashboardViewModel.saveBudget(
                                id = draft.id,
                                name = draft.name,
                                scope = draft.scope,
                                scopeValue = draft.scopeValue,
                                period = draft.period,
                                limitMinor = draft.limitMinor,
                                currency = currency
                            )
                            budgetSheet = null
                            viewModel.report(context.getString(R.string.budget_saved))
                        },
                        onDelete = { id ->
                            dashboardViewModel.deleteBudget(id)
                            budgetSheet = null
                            viewModel.report(context.getString(R.string.budget_removed))
                        }
                    )
                }

                if (showTagSheet) {
                    TagSheet(
                        paymentCount = state.selected.size,
                        existing = state.allTags,
                        onDismiss = { showTagSheet = false },
                        onConfirm = { name, isTrip ->
                            viewModel.tagSelected(name, isTrip)
                            showTagSheet = false
                        }
                    )
                }

                } // CompositionLocalProvider

                LaunchedEffect(renameTarget) {
                    renameSimilarCount = renameTarget?.let { viewModel.similarCount(it) } ?: 0
                }

                renameTarget?.let { id ->
                    state.transaction(id)?.let { txn ->
                        RenameSheet(
                            currentName = txn.displayName,
                            counterpartyVpa = txn.counterpartyVpa,
                            similarCount = renameSimilarCount,
                            onDismiss = { renameTarget = null },
                            onConfirm = { newName, applyToSimilar ->
                                viewModel.rename(id, newName, applyToSimilar)
                                renameTarget = null
                                dashboardViewModel.refresh()
                            }
                        )
                    }
                }

                if (showPrivacySheet) {
                    PrivacySheet(
                        onDismiss = { showPrivacySheet = false },
                        onCopyCommand = {
                            copyToClipboard(it)
                            viewModel.reportCopied()
                        }
                    )
                }

                if (showMoreSheet) {
                    MoreSheet(
                        currency = currency,
                        themeMode = themeMode,
                        typeface = typeface,
                        exporting = exporting,
                        onDismiss = { showMoreSheet = false },
                        onCurrency = {
                            viewModel.setCurrency(it)
                            dashboardViewModel.refresh()
                        },
                        onThemeMode = viewModel::setThemeMode,
                        onTypeface = viewModel::setTypeface,
                        onExport = { includeSources ->
                            exportIncludesSources = includeSources
                            showMoreSheet = false
                            createExportFile.launch(exportFileName())
                        },
                        // Try it and see, rather than asking first. A pre-flight
                        // resolveActivity is only as truthful as the <queries>
                        // declaration behind it, and getting that wrong reports
                        // "no email app" on a phone that plainly has one.
                        onFeedback = {
                            showMoreSheet = false
                            if (!sendFeedback(this)) viewModel.reportNoEmailApp()
                        },
                        onOpenSite = { if (!openSite(this)) viewModel.reportNoBrowser() },
                        onPrivacy = {
                            showMoreSheet = false
                            showPrivacySheet = true
                        },
                        onCopyEmail = {
                            copyToClipboard(DEVELOPER_EMAIL)
                            viewModel.reportCopied()
                        }
                    )
                }

                if (showAddSheet) {
                    AddTransactionSheet(
                        // Present only when this launch came from a share the
                        // parser could not read; otherwise an ordinary entry form.
                        receipt = pendingReceipt,
                        onDismiss = {
                            showAddSheet = false
                            discardReceipt()
                        },
                        onSubmit = { entry ->
                            viewModel.addManual(entry)
                            showAddSheet = false
                            discardReceipt()
                            dashboardViewModel.refresh()
                        }
                    )
                }

                if (showImportSheet) {
                    ImportSheet(
                        smsSupported = viewModel.smsSupported,
                        onDismiss = { showImportSheet = false },
                        onRescanNotifications = {
                            showImportSheet = false
                            UpiNotificationListener.requestRebind(this)
                            viewModel.rescanNotifications()
                        },
                        onImportSms = {
                            showImportSheet = false
                            importSmsHistory()
                        },
                        onImportCsv = {
                            showImportSheet = false
                            pickStatement.launch(STATEMENT_MIME_TYPES)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingAction = intent.action
        // A second share while the app is already open replaces the first, so
        // the staged copy of the first is dropped rather than left in the cache.
        intent.readReceiptPrefill()?.let { fresh ->
            discardReceipt()
            pendingReceipt = fresh
        }
    }

    private fun Intent.readReceiptPrefill(): SharedReceiptPrefill? {
        if (action != ShareReceiverActivity.ACTION_ADD_FROM_RECEIPT) return null
        val path = getStringExtra(ShareReceiverActivity.EXTRA_RECEIPT_PATH)
        val text = getStringExtra(ShareReceiverActivity.EXTRA_RECEIPT_TEXT)
        if (path == null && text.isNullOrBlank()) return null
        return SharedReceiptPrefill(
            imagePath = path,
            takenAt = getLongExtra(ShareReceiverActivity.EXTRA_RECEIPT_TAKEN_AT, 0L)
                .takeIf { it > 0L },
            text = text
        )
    }

    /**
     * Deletes the staged receipt.
     *
     * The app holds a copy of a payment screenshot for exactly as long as the
     * form in front of the user needs it. Called whether the entry was saved or
     * abandoned, because an abandoned one is no less private.
     */
    private fun discardReceipt() {
        SharedReceiptReader.discard(this, pendingReceipt?.imagePath?.let { File(it) })
        pendingReceipt = null
        // The launching intent is re-read on every recreation, so leaving the
        // action on it would reopen the form after a rotation - pointing at a
        // receipt that has just been deleted.
        if (intent?.action == ShareReceiverActivity.ACTION_ADD_FROM_RECEIPT) {
            intent.action = null
            pendingAction = null
        }
    }

    override fun onResume() {
        super.onResume()
        listenerEnabled = isNotificationListenerEnabled()
        if (listenerEnabled) {
            TransactionCaptureService.start(this)
            // A listener that was killed stays unbound until asked to rebind, and
            // an unbound listener silently captures nothing.
            if (!UpiNotificationListener.isConnected) {
                UpiNotificationListener.requestRebind(this)
            }
        }
    }

    private fun importSmsHistory() {
        // Anything still outstanding is asked for, not just what the import
        // itself needs: a user who has granted READ_SMS but never been asked for
        // RECEIVE_SMS has a live rail that cannot fire and no way to discover it.
        val outstanding = viewModel.smsPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (outstanding.isEmpty()) {
            viewModel.importSmsHistory()
        } else {
            requestSmsPermission.launch(outstanding.toTypedArray())
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("SpendLens", text))
    }

    private fun exportFileName(): String {
        val stamp = java.time.LocalDate.now().toString()
        return "spendlens-$stamp.csv"
    }

    private fun isNotificationListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private companion object {
        /**
         * Bank exports are handed out with wildly inconsistent MIME types, so the
         * generic ones are included alongside text/csv.
         */
        val STATEMENT_MIME_TYPES = arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "application/csv",
            "text/plain",
            "application/octet-stream"
        )
    }
}

@Composable
private fun TabBar(current: Tab, onSelect: (Tab) -> Unit) {
    val colors = SpendTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.paper)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabPill(stringResource(R.string.tab_stream), current == Tab.STREAM) { onSelect(Tab.STREAM) }
        TabPill(stringResource(R.string.tab_dashboard), current == Tab.DASHBOARD) { onSelect(Tab.DASHBOARD) }
    }
}

@Composable
private fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SpendTheme.colors
    Box(
        modifier = Modifier
            .background(if (selected) colors.ink else colors.paperSunk, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.paper else colors.ink
        )
    }
}

/**
 * Which budget the sheet is on, if any.
 *
 * A wrapper rather than a bare `Budget?`, because null has to mean two different
 * things - "no sheet" and "a sheet that is creating one".
 */
private data class BudgetSheetTarget(val budget: Budget?)

/** The payments behind one bar, over the window the bar was measured across. */
private fun SliceSelection.toStreamFilter(rangeLabel: String) = StreamFilter(
    kind = when (groupBy) {
        GroupBy.MERCHANT -> StreamFilter.Kind.MERCHANT
        GroupBy.TAG -> StreamFilter.Kind.TAG
        GroupBy.CHANNEL -> StreamFilter.Kind.CHANNEL
    },
    value = key,
    label = label,
    sinceMillis = sinceMillis,
    untilMillis = untilMillis,
    rangeLabel = rangeLabel,
    credits = credits
)

private val BUDGET_WINDOW_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)

/**
 * The payments inside a budget's own period.
 *
 * The window comes from the budget rather than from the range chips, which is the
 * whole point: a monthly limit is measured over its month whatever the report
 * above it happens to be showing.
 */
private fun BudgetProgress.toStreamFilter(
    zone: ZoneId = ZoneId.systemDefault()
) = StreamFilter(
    kind = when (budget.scope) {
        BudgetScope.TOTAL -> StreamFilter.Kind.ALL
        BudgetScope.TAG -> StreamFilter.Kind.TAG
        BudgetScope.MERCHANT -> StreamFilter.Kind.MERCHANT
    },
    value = budget.scopeValue.orEmpty(),
    label = budget.name,
    sinceMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
    untilMillis = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    rangeLabel = start.format(BUDGET_WINDOW_FORMAT) + " – " + end.format(BUDGET_WINDOW_FORMAT)
)

private val DAY_FILTER_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.ENGLISH)

/** One day off the spend-by-day chart, opened in the stream. */
private fun dayFilter(
    date: java.time.LocalDate,
    kindLabel: String,
    zone: ZoneId = ZoneId.systemDefault()
) = StreamFilter(
    kind = StreamFilter.Kind.ALL,
    value = "",
    label = date.format(DAY_FILTER_FORMAT),
    sinceMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
    untilMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    rangeLabel = kindLabel
)

/** A whole month off the comparison, opened in the stream. */
private fun MonthBucket.toStreamFilter(zone: ZoneId = ZoneId.systemDefault()) = StreamFilter(
    kind = StreamFilter.Kind.ALL,
    value = "",
    label = start.format(DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.ENGLISH)),
    sinceMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
    untilMillis = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
    rangeLabel = start.format(BUDGET_WINDOW_FORMAT) + " – " + end.format(BUDGET_WINDOW_FORMAT)
)

/** Renders a one-shot event into user-facing text. */
private fun android.content.Context.describe(event: DayStreamEvent): String = when (event) {
    is DayStreamEvent.Imported -> {
        val summary = event.summary
        when {
            summary.considered == 0 -> getString(R.string.import_nothing_found)
            summary.inserted == 0 && summary.merged == 0 -> getString(R.string.import_all_known)
            else -> getString(R.string.import_result, summary.inserted, summary.duplicates)
        }
    }
    DayStreamEvent.TransactionAdded -> getString(R.string.add_result)
    DayStreamEvent.ListenerNotConnected -> getString(R.string.import_listener_not_connected)
    DayStreamEvent.SmsUnavailable -> getString(R.string.import_sms_unavailable)
    is DayStreamEvent.SplitApplied -> resources.getQuantityString(R.plurals.split_applied, event.count, event.count)
    is DayStreamEvent.Tagged -> resources.getQuantityString(R.plurals.tagged_result, event.count, event.count, event.tagName)
    is DayStreamEvent.Exported -> getString(R.string.export_done, event.rowCount)
    DayStreamEvent.NoEmailApp -> getString(R.string.settings_no_email_app)
    DayStreamEvent.NoBrowser -> getString(R.string.settings_no_browser)
    DayStreamEvent.Copied -> getString(R.string.settings_copied)
    DayStreamEvent.Renamed -> getString(R.string.renamed)
    is DayStreamEvent.Message -> event.text
    DayStreamEvent.NoteSaved -> getString(R.string.note_saved)
    is DayStreamEvent.RenamedMany -> resources.getQuantityString(R.plurals.renamed_many, event.count, event.count)
    is DayStreamEvent.Failed -> getString(R.string.import_failed, event.reason ?: "")
}

/**
 * Shown until notification access is granted. Without it the app captures
 * nothing, so this is the only state worth interrupting the stream for.
 */
@Composable
private fun ConnectPrompt(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 16.dp)
            .background(colors.reviewBg, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.onboarding_notification_permission_title),
            style = typography.bodyMedium,
            color = colors.review
        )
        Text(
            text = stringResource(R.string.onboarding_notification_permission_body),
            style = typography.bodySmall,
            color = colors.graphite,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            text = stringResource(R.string.grant_notification_access),
            style = typography.bodySmall,
            color = colors.review,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
