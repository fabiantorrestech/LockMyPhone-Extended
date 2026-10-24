package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.diag.RelockEntry
import java.text.DateFormat
import java.util.Date

/**
 * "Why did it lock?": the last relocks caused by a window outside the allowed apps, newest first.
 * A popup or overlay (not a screen) can be allowed from here with one tap; it applies to locks
 * started afterwards, like every "During a lock" setting.
 */
@Composable
fun RelockHistoryDialog(
    entries: List<RelockEntry>,
    appLabel: (String) -> String,
    popupPackages: Set<String>,
    onAllowPopups: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relock_history_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.relock_history_hint), style = MaterialTheme.typography.bodySmall)
                if (entries.isEmpty()) Text(stringResource(R.string.relock_history_none))
                entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    RelockItem(entry, appLabel, allowed = entry.packageName in popupPackages, onAllowPopups = onAllowPopups)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = if (entries.isNotEmpty()) ({ TextButton(onClick = onClear) { Text(stringResource(R.string.relock_history_clear)) } }) else null,
    )
}

@Composable
private fun RelockItem(entry: RelockEntry, appLabel: (String) -> String, allowed: Boolean, onAllowPopups: (String) -> Unit) {
    val label = appLabel(entry.packageName)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timeMs)) + " · " + label, style = MaterialTheme.typography.titleSmall)
        entry.fromPackage?.takeIf { it != entry.packageName }?.let { from ->
            Text(stringResource(R.string.relock_history_over, appLabel(from)), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(if (entry.isActivity) R.string.relock_history_screen else R.string.relock_history_popup),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            entry.packageName + (entry.className?.let { "\n$it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            entry.isActivity -> Unit
            allowed -> Text(stringResource(R.string.relock_history_allowed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            else -> TextButton(onClick = { onAllowPopups(entry.packageName) }) {
                Text(stringResource(R.string.relock_history_allow, label))
            }
        }
    }
}
