package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fabiantorrestech.lockmyphone_extended.R

/** "🌙 Going back to sleep" for a Morning lock that locked today and can be re-armed. */
@Composable
fun BackToSleepButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.back_to_sleep_button)) }
}

/**
 * Asks before re-arming a Morning lock: it locks again, in full, at the next wake-up, and a lock
 * that's running keeps running.
 */
@Composable
fun BackToSleepDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.back_to_sleep_title)) },
        text = { Text(stringResource(R.string.back_to_sleep_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.back_to_sleep_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
