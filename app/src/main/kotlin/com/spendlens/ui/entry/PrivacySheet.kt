package com.spendlens.ui.entry

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.spendlens.R
import com.spendlens.ui.theme.SpendTheme
import java.util.Locale

/** One permission as the panel renders it. */
data class PermissionFact(
    val name: String,
    val label: String,
    val granted: Boolean,
    val why: String
)

/**
 * The privacy panel.
 *
 * Every app claims it respects your privacy, so a claim is worth nothing. This
 * panel does not claim — it *reads the app's own permission list back out of the
 * package manager at runtime* and shows what is actually there, including the
 * fact that `INTERNET` is not. The app is reporting on itself using the same API
 * any auditor would use, and it cannot show a permission it does not hold or hide
 * one it does.
 *
 * The verification instructions matter as much as the list. A user who does not
 * trust the app should not have to trust this screen either, so it tells them how
 * to check the APK from outside.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySheet(onDismiss: () -> Unit, onCopyCommand: (String) -> Unit) {
    val colors = SpendTheme.colors
    val typography = MaterialTheme.typography
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permissions = remember { readPermissions(context) }
    val hasInternet = remember(permissions) { permissions.any { it.name == "android.permission.INTERNET" } }

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
            Text(
                text = stringResource(R.string.privacy_title),
                style = typography.titleLarge,
                color = colors.ink
            )

            // The headline claim, stated as a live reading rather than a promise.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .background(
                        if (hasInternet) colors.reviewBg else colors.splitBg,
                        RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            ) {
                Text(
                    text = stringResource(
                        if (hasInternet) R.string.privacy_internet_present
                        else R.string.privacy_internet_absent
                    ),
                    style = typography.bodyMedium,
                    color = if (hasInternet) colors.review else colors.split
                )
                Text(
                    text = stringResource(R.string.privacy_internet_note),
                    style = typography.bodySmall,
                    color = colors.graphite,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Text(
                text = stringResource(R.string.privacy_permissions_held, permissions.size)
                    .uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
            )
            Text(
                text = stringResource(R.string.privacy_permissions_note),
                style = typography.bodySmall,
                color = colors.graphite,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            for (permission in permissions) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = permission.label,
                            style = typography.bodySmall,
                            color = colors.ink,
                            modifier = Modifier.weight(1f).padding(end = 10.dp)
                        )
                        Text(
                            text = stringResource(
                                if (permission.granted) R.string.privacy_granted
                                else R.string.privacy_not_granted
                            ),
                            style = typography.labelSmall,
                            color = if (permission.granted) colors.split else colors.mist
                        )
                    }
                    Text(
                        text = permission.why,
                        style = typography.labelSmall,
                        color = colors.graphite,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            // Someone who does not trust the app should not have to trust this
            // screen either. This is how to check the APK from outside it.
            Text(
                text = stringResource(R.string.privacy_verify).uppercase(Locale.ROOT),
                style = typography.labelSmall,
                color = colors.graphite,
                modifier = Modifier.padding(top = 24.dp, bottom = 6.dp)
            )
            Text(
                text = stringResource(R.string.privacy_verify_note),
                style = typography.bodySmall,
                color = colors.graphite
            )
            Text(
                text = VERIFY_COMMAND,
                style = typography.bodySmall,
                color = colors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(colors.paperSunk, RoundedCornerShape(8.dp))
                    .clickable { onCopyCommand(VERIFY_COMMAND) }
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            )
            Text(
                text = stringResource(R.string.settings_tap_to_copy),
                style = typography.labelSmall,
                color = colors.mist,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private const val VERIFY_COMMAND = "aapt2 dump badging SpendLens.apk | grep uses-permission"

/**
 * Reads the app's own manifest back out of the package manager.
 *
 * Deliberately not a hardcoded list: a hardcoded list is another claim, and could
 * drift from what the build actually ships. This asks Android what this package
 * declares, so the panel is wrong only if the platform lies to it.
 */
private fun readPermissions(context: Context): List<PermissionFact> {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }.getOrNull() ?: return emptyList()

    val declared = info.requestedPermissions.orEmpty()
    val flags = info.requestedPermissionsFlags

    return declared.mapIndexed { index, name ->
        val granted = flags != null && index < flags.size &&
            (flags[index] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        PermissionFact(
            name = name,
            label = friendlyName(name),
            granted = granted,
            why = reasonFor(name)
        )
    }.sortedBy { it.label }
}

private fun friendlyName(permission: String): String = when (permission) {
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" -> "Read notifications"
    "android.permission.POST_NOTIFICATIONS" -> "Show notifications"
    "android.permission.RECEIVE_BOOT_COMPLETED" -> "Start after restart"
    "android.permission.FOREGROUND_SERVICE" -> "Run in the foreground"
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC" -> "Foreground service type"
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" -> "Ask to skip battery optimisation"
    "android.permission.READ_SMS" -> "Read SMS"
    "android.permission.RECEIVE_SMS" -> "Receive SMS"
    else -> permission.substringAfterLast('.').lowercase(Locale.ROOT)
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
}

private fun reasonFor(permission: String): String = when (permission) {
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" ->
        "Reads payment notifications. This is how the app sees your spending at all."
    "android.permission.POST_NOTIFICATIONS" ->
        "Shows the running total and the nudge after a payment."
    "android.permission.RECEIVE_BOOT_COMPLETED" ->
        "Restarts capture after the phone reboots, so payments are not missed."
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC" ->
        "Keeps the notification reader alive so Android does not kill it."
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" ->
        "Lets you exempt the app from battery optimisation. Only ever asked, never taken."
    "android.permission.READ_SMS" ->
        "Reads bank messages already on the phone — the only way to see spending from before you installed this."
    "android.permission.RECEIVE_SMS" ->
        "Reads incoming bank messages as they arrive."
    else -> "Declared by an Android library the app uses."
}
