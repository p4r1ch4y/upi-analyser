package com.spendlens.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
import com.spendlens.core.model.Split
import com.spendlens.service.TransactionCaptureService
import com.spendlens.service.UpiNotificationListener
import com.spendlens.ui.dashboard.DashboardActions
import com.spendlens.ui.dashboard.DashboardScreen
import com.spendlens.ui.dashboard.DashboardViewModel
import com.spendlens.ui.entry.AddTransactionSheet
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

private enum class Tab { STREAM, DASHBOARD }

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

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val requestSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.importSmsHistory()
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

        setContent {
            SpendLensTheme {
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
                var openTxnId by remember { mutableStateOf<String?>(null) }
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
                                onMore = { showMoreSheet = true }
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
                                onSortBy = dashboardViewModel::setSortBy
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
                            onRename = { renameTarget = id }
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

                renameTarget?.let { id ->
                    state.transaction(id)?.let { txn ->
                        RenameSheet(
                            currentName = txn.displayName,
                            counterpartyVpa = txn.counterpartyVpa,
                            onDismiss = { renameTarget = null },
                            onConfirm = { newName ->
                                viewModel.rename(id, newName)
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
                        exporting = exporting,
                        onDismiss = { showMoreSheet = false },
                        onCurrency = {
                            viewModel.setCurrency(it)
                            dashboardViewModel.refresh()
                        },
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
                        onDismiss = { showAddSheet = false },
                        onSubmit = { entry ->
                            viewModel.addManual(entry)
                            showAddSheet = false
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
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.importSmsHistory() else requestSmsPermission.launch(Manifest.permission.READ_SMS)
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
    is DayStreamEvent.SplitApplied -> getString(R.string.split_applied, event.count)
    is DayStreamEvent.Tagged -> getString(R.string.tagged_result, event.count, event.tagName)
    is DayStreamEvent.Exported -> getString(R.string.export_done, event.rowCount)
    DayStreamEvent.NoEmailApp -> getString(R.string.settings_no_email_app)
    DayStreamEvent.NoBrowser -> getString(R.string.settings_no_browser)
    DayStreamEvent.Copied -> getString(R.string.settings_copied)
    DayStreamEvent.Renamed -> getString(R.string.renamed)
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
