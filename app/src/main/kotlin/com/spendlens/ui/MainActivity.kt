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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.spendlens.R
import com.spendlens.SpendLensApp
import com.spendlens.service.TransactionCaptureService
import com.spendlens.service.UpiNotificationListener
import com.spendlens.ui.entry.AddTransactionSheet
import com.spendlens.ui.entry.ImportSheet
import com.spendlens.ui.theme.SpendLensTheme
import com.spendlens.ui.theme.SpendTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DayStreamViewModel by viewModels {
        DayStreamViewModel.factory(SpendLensApp.graphOf(this))
    }

    /**
     * Notification access is granted in system Settings, so there is no callback
     * to observe - it is re-read on every resume and drives recomposition.
     */
    private var listenerEnabled by mutableStateOf(false)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    /**
     * SMS history is only worth asking for in the flavour that declares it. The
     * result feeds straight back into an import so the user does not have to tap
     * twice.
     */
    private val requestSmsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.importSmsHistory()
        }

    /**
     * Statement import goes through the Storage Access Framework, so the app
     * needs no storage permission and only ever sees the file the user picked.
     */
    private val pickStatement =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let(viewModel::importCsv)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        listenerEnabled = isNotificationListenerEnabled()
        requestPostNotificationsIfNeeded()

        setContent {
            SpendLensTheme {
                val state by viewModel.state.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                val context = LocalContext.current

                var showAddSheet by remember { mutableStateOf(false) }
                var showImportSheet by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        snackbarHostState.showSnackbar(context.describe(event))
                    }
                }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = SpendTheme.colors.paper
                ) { padding ->
                    DayStreamScreen(
                        state = state,
                        onNameMerchant = { /* naming sheet arrives with the edit flow */ },
                        onAdd = { showAddSheet = true },
                        onImport = { showImportSheet = true },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = padding.calculateBottomPadding()),
                        header = if (listenerEnabled) null else {
                            { ConnectPrompt(onClick = ::openListenerSettings) }
                        }
                    )
                }

                if (showAddSheet) {
                    AddTransactionSheet(
                        onDismiss = { showAddSheet = false },
                        onSubmit = { entry ->
                            viewModel.addManual(entry)
                            showAddSheet = false
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
