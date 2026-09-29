package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode

/** The accepted range for "lock again after N s". */
val UNLOCK_CHOICE_SECONDS_RANGE = 1..60

/**
 * "After the screen turns off in an allowed app": go back to the app, or show the lock screen
 * and lock again after N seconds. Used by the profile editor, Lock defaults and Quick lock.
 *
 * @param secondsText the seconds field's raw text; the caller keeps it so an invalid value can
 *   be shown while typing.
 */
@Composable
fun UnlockResumeOptions(
    mode: UnlockResumeMode,
    onModeChange: (UnlockResumeMode) -> Unit,
    secondsText: String,
    onSecondsTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.unlock_resume_title), style = MaterialTheme.typography.titleSmall)
        ResumeModeRow(stringResource(R.string.unlock_resume_app), mode == UnlockResumeMode.RESUME_APP) {
            onModeChange(UnlockResumeMode.RESUME_APP)
        }
        ResumeModeRow(stringResource(R.string.unlock_resume_lock_screen), mode == UnlockResumeMode.SHOW_LOCK_SCREEN) {
            onModeChange(UnlockResumeMode.SHOW_LOCK_SCREEN)
        }
        if (mode == UnlockResumeMode.SHOW_LOCK_SCREEN) {
            DurationField(
                value = secondsText,
                onValueChange = onSecondsTextChange,
                label = stringResource(R.string.unlock_choice_seconds_label),
                isError = parseUnlockChoiceSeconds(secondsText) == null,
                maxDigits = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(stringResource(R.string.unlock_resume_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** The seconds typed into [UnlockResumeOptions], or null if outside [UNLOCK_CHOICE_SECONDS_RANGE]. */
fun parseUnlockChoiceSeconds(text: String): Int? =
    text.toIntOrNull()?.takeIf { it in UNLOCK_CHOICE_SECONDS_RANGE }

@Composable
private fun ResumeModeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The whole row handles the click, for a bigger touch target.
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}
