package com.fabiantorrestech.lockmyphone_extended.ui

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
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.LockChoice

/**
 * "Start which lock?" after tapping a tag linked to several locks. Picking one starts it.
 *
 * @param tagName the tapped tag's nickname.
 */
@Composable
fun LockChooserDialog(
    tagName: String,
    choices: List<LockChoice>,
    onPick: (LockChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nfc_choose_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.nfc_choose_subtitle, tagName), style = MaterialTheme.typography.bodySmall)
                choices.forEach { choice ->
                    SettingsRow(
                        icon = null,
                        title = choice.name,
                        summary = null,
                        onClick = { onPick(choice) },
                        trailing = { ProfileIcon(choice.icon, choice.name) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
