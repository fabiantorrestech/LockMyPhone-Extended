package com.fabiantorrestech.lockmyphone_extended.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.perm.AppPermission
import com.fabiantorrestech.lockmyphone_extended.core.perm.PauseState
import com.fabiantorrestech.lockmyphone_extended.core.perm.PermissionCheck

/**
 * One permission as shown.
 *
 * @param usedBy the names of what uses it right now (places, triggers, profiles…).
 * @param canRevoke whether the app can take it back itself; otherwise only [PermissionActions.onManage].
 */
data class PermissionRow(
    val permission: AppPermission,
    val granted: Boolean,
    val usedBy: List<String>,
    val canRevoke: Boolean,
)

class PermissionActions(
    val onGrant: (AppPermission) -> Unit,
    /** Opens the exact Android settings page for it. */
    val onManage: (AppPermission) -> Unit,
    val onRevoke: (AppPermission) -> Unit,
)

/** The permissions needed for locks to work at all. */
val CORE_PERMISSIONS = listOf(
    AppPermission.DEVICE_ADMIN,
    AppPermission.ACCESSIBILITY,
    AppPermission.NOTIFICATIONS,
    AppPermission.BATTERY,
    AppPermission.EXACT_ALARMS,
)

/** Runtime permissions: revoking them restarts the app. */
private val RUNTIME_PERMISSIONS = setOf(
    AppPermission.NOTIFICATIONS,
    AppPermission.PRECISE_LOCATION,
    AppPermission.BACKGROUND_LOCATION,
    AppPermission.NEARBY_SCAN,
    AppPermission.NEARBY_CONNECT,
    AppPermission.NEARBY_ADVERTISE,
    AppPermission.CALENDAR,
)

/**
 * Settings → Permissions: every permission, allowed or not, what it's for and what uses it,
 * with Allow, Manage (Android's own page) and, where Android lets an app do it, Revoke.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    rows: List<PermissionRow>,
    actions: PermissionActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var confirming by remember { mutableStateOf<PermissionRow?>(null) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.permissions_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            // The activity's Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.permissions_hint), style = MaterialTheme.typography.bodySmall)
            val (core, features) = rows.partition { it.permission in CORE_PERMISSIONS }
            SettingsSection(stringResource(R.string.permissions_core)) {
                core.forEachIndexed { index, row ->
                    if (index > 0) SectionDivider()
                    PermissionItem(row, actions, onRevoke = { confirming = row })
                }
            }
            SettingsSection(stringResource(R.string.permissions_features)) {
                features.forEachIndexed { index, row ->
                    if (index > 0) SectionDivider()
                    PermissionItem(row, actions, onRevoke = { confirming = row })
                }
            }
        }
    }

    confirming?.let { row ->
        val name = stringResource(permissionLabel(row.permission))
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(stringResource(R.string.permission_revoke_title, name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (row.permission in CORE_PERMISSIONS) {
                        Text(stringResource(R.string.permission_revoke_core), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (row.usedBy.isEmpty()) {
                            stringResource(R.string.permission_revoke_unused)
                        } else {
                            stringResource(R.string.permission_revoke_pauses, row.usedBy.joinToString(", "))
                        }
                    )
                    if (row.permission in RUNTIME_PERMISSIONS) Text(stringResource(R.string.permission_revoke_restart))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    actions.onRevoke(row.permission)
                }) { Text(stringResource(R.string.permission_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PermissionItem(row: PermissionRow, actions: PermissionActions, onRevoke: () -> Unit) {
    Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(permissionLabel(row.permission)), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (row.granted) R.string.permission_allowed else R.string.permission_not_allowed),
            style = MaterialTheme.typography.labelMedium,
            color = if (row.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(stringResource(permissionReason(row.permission)), style = MaterialTheme.typography.bodySmall)
        Text(
            if (row.usedBy.isEmpty()) {
                stringResource(R.string.permission_used_by_nothing)
            } else {
                stringResource(R.string.permission_used_by, row.usedBy.joinToString(", "))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (!row.granted) {
                Button(onClick = { actions.onGrant(row.permission) }) { Text(stringResource(R.string.permission_allow)) }
            }
            OutlinedButton(onClick = { actions.onManage(row.permission) }) { Text(stringResource(R.string.permission_manage)) }
            if (row.granted && row.canRevoke) {
                TextButton(
                    onClick = onRevoke,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.permission_revoke)) }
            }
        }
    }
}

/** What's granted, from the setup status the app already keeps up to date. */
fun SetupStatus.grantedPermissions(): Set<AppPermission> = buildSet {
    if (adminActive) add(AppPermission.DEVICE_ADMIN)
    if (notificationsGranted) add(AppPermission.NOTIFICATIONS)
    if (accessibilityEnabled) add(AppPermission.ACCESSIBILITY)
    if (batteryUnrestricted) add(AppPermission.BATTERY)
    if (exactAlarmsAllowed) add(AppPermission.EXACT_ALARMS)
    if (preciseLocationGranted) add(AppPermission.PRECISE_LOCATION)
    if (backgroundLocationGranted) add(AppPermission.BACKGROUND_LOCATION)
    if (locationServicesOn) add(AppPermission.LOCATION_SERVICES)
    if (nearbyScanGranted) add(AppPermission.NEARBY_SCAN)
    if (bluetoothConnectGranted) add(AppPermission.NEARBY_CONNECT)
    if (bluetoothAdvertiseGranted) add(AppPermission.NEARBY_ADVERTISE)
    if (calendarGranted) add(AppPermission.CALENDAR)
    if (notificationAccessGranted) add(AppPermission.NOTIFICATION_ACCESS)
    if (dndAccessGranted) add(AppPermission.DND_ACCESS)
    if (nfcOn) add(AppPermission.NFC_ON)
}

/** "⏸ Paused: needs Precise location" under a list row, or null when everything it needs is allowed. */
fun pauseNote(context: Context, check: PermissionCheck): String? {
    if (check.state == PauseState.OK) return null
    val names = check.missing.sortedBy { it.ordinal }.joinToString(", ") { context.getString(permissionLabel(it)) }
    return context.getString(if (check.state == PauseState.PAUSED) R.string.paused_needs else R.string.partly_paused_needs, names)
}

fun permissionLabel(permission: AppPermission): Int = when (permission) {
    AppPermission.DEVICE_ADMIN -> R.string.perm_admin
    AppPermission.NOTIFICATIONS -> R.string.perm_notifications
    AppPermission.ACCESSIBILITY -> R.string.perm_accessibility
    AppPermission.BATTERY -> R.string.perm_battery
    AppPermission.EXACT_ALARMS -> R.string.perm_exact_alarms
    AppPermission.PRECISE_LOCATION -> R.string.perm_location
    AppPermission.BACKGROUND_LOCATION -> R.string.perm_background_location
    AppPermission.LOCATION_SERVICES -> R.string.perm_location_services
    AppPermission.NEARBY_SCAN -> R.string.perm_nearby_scan
    AppPermission.NEARBY_CONNECT -> R.string.perm_nearby_connect
    AppPermission.NEARBY_ADVERTISE -> R.string.perm_nearby_advertise
    AppPermission.CALENDAR -> R.string.perm_calendar
    AppPermission.NOTIFICATION_ACCESS -> R.string.perm_notification_access
    AppPermission.DND_ACCESS -> R.string.perm_dnd
    AppPermission.NFC_ON -> R.string.perm_nfc
}

private fun permissionReason(permission: AppPermission): Int = when (permission) {
    AppPermission.DEVICE_ADMIN -> R.string.perm_admin_why
    AppPermission.NOTIFICATIONS -> R.string.perm_notifications_why
    AppPermission.ACCESSIBILITY -> R.string.perm_accessibility_why
    AppPermission.BATTERY -> R.string.perm_battery_why
    AppPermission.EXACT_ALARMS -> R.string.perm_exact_alarms_why
    AppPermission.PRECISE_LOCATION -> R.string.perm_location_why
    AppPermission.BACKGROUND_LOCATION -> R.string.perm_background_location_why
    AppPermission.LOCATION_SERVICES -> R.string.perm_location_services_why
    AppPermission.NEARBY_SCAN -> R.string.perm_nearby_scan_why
    AppPermission.NEARBY_CONNECT -> R.string.perm_nearby_connect_why
    AppPermission.NEARBY_ADVERTISE -> R.string.perm_nearby_advertise_why
    AppPermission.CALENDAR -> R.string.perm_calendar_why
    AppPermission.NOTIFICATION_ACCESS -> R.string.perm_notification_access_why
    AppPermission.DND_ACCESS -> R.string.perm_dnd_why
    AppPermission.NFC_ON -> R.string.perm_nfc_why
}
