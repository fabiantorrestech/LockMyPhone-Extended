package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.diag.ForegroundLog
import com.fabiantorrestech.lockmyphone_extended.core.oem.DeviceInfo
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows what the device reports about itself and a live log of the windows the accessibility
 * service sees. Open other apps, the app switcher or the emergency screen, then come back to
 * read their package names. [onCopy] exports everything as text for building a device profile.
 */
@Composable
fun DiagnosticsScreen(
    deviceInfo: DeviceInfo,
    activeProfile: OemProfile,
    accessibilityRunning: Boolean,
    entries: List<ForegroundLog.Entry>,
    onCopy: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    // New entries are added at the top. Follow them, unless the user has scrolled down to read.
    LaunchedEffect(entries.firstOrNull()) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    Column(modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.diag_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                deviceSummary(deviceInfo, activeProfile) + "\nA11y bound: " + if (accessibilityRunning) "yes" else "NO",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp),
            )
        }
        Text(
            stringResource(
                if (accessibilityRunning) R.string.diag_hint else R.string.diag_needs_accessibility
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { onCopy(exportText(deviceInfo, activeProfile, entries)) }) {
                Text(stringResource(R.string.diag_copy))
            }
            OutlinedButton(onClick = onClear) { Text(stringResource(R.string.diag_clear)) }
        }
        Box(Modifier.fillMaxSize().padding(top = 8.dp)) {
            LazyColumn(state = listState) {
                items(entries, key = { "${it.timeMs}|${it.packageName}|${it.className}" }) { entry ->
                    EntryRow(entry)
                }
            }
            FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

@Composable
private fun EntryRow(entry: ForegroundLog.Entry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            "${formatTime(entry.timeMs)}  ${entry.packageName}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "${entry.className ?: "-"}\n${tagsFor(entry)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun tagsFor(entry: ForegroundLog.Entry): String =
    entry.verdict.name.lowercase().replace('_', '-') +
        (if (entry.relocked) "  → RELOCKED" else "") +
        (entry.note?.let { "  ($it)" } ?: "")

private fun formatTime(timeMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))

private fun deviceSummary(info: DeviceInfo, profile: OemProfile): String = buildString {
    appendLine("Device:   ${info.manufacturer} ${info.model} (API ${info.sdkInt})")
    appendLine("Profile:  ${profile.displayName}${if (profile.verified) "" else " (unverified)"}")
    appendLine("Launcher: ${info.defaultLauncher ?: "?"}")
    appendLine("Dialer:   ${info.defaultDialer ?: "?"}")
    append("Keyboard: ${info.keyboards.joinToString().ifEmpty { "?" }}")
}

private fun exportText(
    info: DeviceInfo,
    profile: OemProfile,
    entries: List<ForegroundLog.Entry>,
): String = buildString {
    appendLine(deviceSummary(info, profile))
    appendLine()
    entries.forEach {
        appendLine("${formatTime(it.timeMs)} ${it.packageName} ${it.className ?: "-"} ${tagsFor(it)}")
    }
}
