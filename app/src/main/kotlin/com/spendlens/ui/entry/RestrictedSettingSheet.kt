package com.spendlens.ui.entry

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.ui.theme.SpendTheme

/**
 * Why the notification-access toggle is greyed out, and how to un-grey it.
 *
 * Android 13 introduced *restricted settings*: an app installed from outside a
 * recognised store cannot be granted notification-listener access at all. The
 * toggle in Settings is visible, disabled, and explains nothing beyond "For your
 * security, this setting is currently unavailable."
 *
 * For SpendLens that is not a degraded mode - notification access is the whole
 * capture rail, so a sideloaded install simply does nothing and looks broken.
 * The user is one obscure menu away from fixing it and has no way to discover
 * which menu. So the app says it, in the order the taps happen.
 *
 * Deliberately not shown to everyone. There is no API that reports "this app is
 * currently restricted", so [isLikelyRestricted] infers it from the two
 * conditions that produce it, and the sheet stays out of the way otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestrictedSettingSheet(
    onDismiss: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenListenerSettings: () -> Unit
) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.restricted_title),
                style = typography.titleLarge,
                color = colors.ink
            )
            Text(
                text = stringResource(R.string.restricted_body),
                style = typography.bodySmall,
                color = colors.graphite
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(1, stringResource(R.string.restricted_step_1))
                Step(2, stringResource(R.string.restricted_step_2))
                Step(3, stringResource(R.string.restricted_step_3))
                Step(4, stringResource(R.string.restricted_step_4))
            }

            Button(
                onClick = onOpenAppInfo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.ink,
                    contentColor = colors.paper
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.restricted_open_app_info))
            }

            Text(
                text = stringResource(R.string.restricted_then_grant),
                style = typography.bodySmall,
                color = colors.split,
                modifier = Modifier
                    .clickable(onClick = onOpenListenerSettings)
                    .padding(vertical = 6.dp)
            )

            Text(
                text = stringResource(R.string.restricted_why),
                style = typography.labelSmall,
                color = colors.mist
            )
        }
    }
}

@Composable
private fun Step(number: Int, text: String) {
    val colors = SpendTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(20.dp).background(colors.paperSunk, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

/**
 * Whether this install is probably sitting behind Android's restricted-settings
 * gate, given that notification access is not granted.
 *
 * There is no API that answers this directly, so it is inferred from the two
 * conditions that cause it: Android 13 or later, and an install that did not
 * come from a recognised store. `getInstallSourceInfo` reports the package that
 * performed the install - null or the bare package installer for a sideload, a
 * store's package otherwise.
 *
 * A false positive costs one extra sheet the user can dismiss. A false negative
 * costs them an app that silently captures nothing, so this errs toward showing.
 */
fun isLikelyRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val installer = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()
    return installer == null || installer in SIDELOAD_INSTALLERS
}

/**
 * Installers that do *not* clear the restriction. The bare package installer is
 * what handles a tapped APK; a store sets its own package here instead.
 */
private val SIDELOAD_INSTALLERS = setOf(
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.shell"
)

/** App info for this app - where the overflow menu holding the toggle lives. */
fun openAppInfo(context: Context): Boolean = runCatching {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    true
}.getOrDefault(false)
