package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.backup.BackupContents
import com.fabiantorrestech.lockmyphone_extended.data.backup.RestoreWarnings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "Restore this backup?": what's in it, that everything will be replaced, and what may need
 * attention afterwards (calendars, alarm triggers, permissions, the stop token).
 */
@Composable
fun RestoreConfirmDialog(
    contents: BackupContents,
    warnings: RestoreWarnings,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val created = Instant.ofEpochMilli(contents.createdAtMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.restore_summary,
                        created,
                        contents.appVersion,
                        contents.profiles.size,
                        contents.schedules.size,
                        contents.alarmTriggers.size,
                        contents.locations.size,
                        contents.calendarTriggers.size,
                        contents.nfcTags.size,
                        contents.beaconNames.size,
                    )
                )
                Text(stringResource(R.string.restore_replaces), color = MaterialTheme.colorScheme.error)
                if (warnings.calendarTriggerCount > 0) {
                    Text(
                        when {
                            warnings.calendarAccessMissing -> stringResource(R.string.restore_warn_calendar_access)
                            warnings.missingCalendars.isNotEmpty() -> stringResource(
                                R.string.restore_warn_calendars,
                                warnings.missingCalendars.joinToString { "${it.name} (${it.account})" },
                            )
                            else -> stringResource(R.string.restore_warn_calendars_general)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (warnings.nfcTagsWithoutNfc) {
                    Text(stringResource(R.string.restore_warn_no_nfc), style = MaterialTheme.typography.bodySmall)
                }
                if (warnings.alarmTriggerCount > 0) {
                    Text(stringResource(R.string.restore_warn_alarms), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.restore_warn_permissions), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.restore_warn_token), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
