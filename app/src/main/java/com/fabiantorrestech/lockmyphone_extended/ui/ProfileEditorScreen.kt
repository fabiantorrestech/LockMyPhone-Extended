package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag

/**
 * Creates or edits a profile, or the Lock defaults. The draft lives in the caller, so it
 * survives a trip to the allowed-apps list. Every setting is edited through a dialog, so the
 * draft only ever holds valid values.
 *
 * @param canDelete false for a new profile or the last remaining one.
 * @param error a message to show, e.g. a duplicate name.
 * @param isDefaults edits the Lock defaults instead of a profile: no name, icon or delete.
 * @param presetsSec one-tap lock lengths from Settings.
 * @param nfcTags the registered tags, to choose which can end this lock.
 */
@Composable
fun ProfileEditorScreen(
    draft: LockProfile,
    canDelete: Boolean,
    error: String?,
    onDraftChange: (LockProfile) -> Unit,
    onEditAllowedApps: () -> Unit,
    onSave: (LockProfile) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    presetsSec: List<Long>,
    nfcTags: List<NfcTag>,
    modifier: Modifier = Modifier,
    isDefaults: Boolean = false,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val title = stringResource(
        when {
            isDefaults -> R.string.lock_defaults_title
            draft.id == 0L -> R.string.profile_new
            else -> R.string.profile_edit_title
        }
    )

    EditorScaffold(
        title = title,
        onBack = onBack,
        saveEnabled = isDefaults || draft.name.isNotBlank(),
        onSave = { onSave(draft.copy(name = draft.name.trim())) },
        onDelete = if (canDelete && !isDefaults) ({ confirmDelete = true }) else null,
        modifier = modifier,
    ) {
        if (isDefaults) {
            Text(stringResource(R.string.lock_defaults_hint), style = MaterialTheme.typography.bodyMedium)
        } else {
            SettingsSection(stringResource(R.string.section_basics)) {
                Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { onDraftChange(draft.copy(name = it.take(MAX_NAME_LENGTH))) },
                        label = { Text(stringResource(R.string.profile_name)) },
                        singleLine = true,
                        isError = draft.name.isBlank() || error != null,
                        supportingText = error?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ProfileIconField(draft.icon, draft.name, onIconChange = { onDraftChange(draft.copy(icon = it)) })
                }
            }
        }

        LockSettingsSections(
            settings = draft.toLockSettings(),
            presetsSec = presetsSec,
            nfcTags = nfcTags,
            lengthTitle = stringResource(R.string.profile_default_duration),
            onChange = { onDraftChange(draft.withLockSettings(it)) },
            onEditAllowedApps = onEditAllowedApps,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.profile_delete_title, draft.name)) },
            text = { Text(stringResource(R.string.profile_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun LockProfile.toLockSettings() = LockSettings(
    durationSec = defaultDurationSec,
    allowedAppCount = allowedPackages.size,
    bypassEnabled = bypassEnabled,
    bypassWaitMinutes = bypassWaitMinutes,
    dndEnabled = dndEnabled,
    dndLevel = dndLevel,
    grayscale = grayscale,
    resumeMode = unlockResumeMode,
    choiceSeconds = unlockChoiceSeconds,
    nfcUnlock = nfcUnlock,
)

/** The allowed apps themselves are edited on their own screen, so only their count comes back here. */
private fun LockProfile.withLockSettings(settings: LockSettings) = copy(
    defaultDurationSec = settings.durationSec,
    bypassEnabled = settings.bypassEnabled,
    bypassWaitMinutes = settings.bypassWaitMinutes,
    dndEnabled = settings.dndEnabled,
    dndLevel = settings.dndLevel,
    grayscale = settings.grayscale,
    unlockResumeMode = settings.resumeMode,
    unlockChoiceSeconds = settings.choiceSeconds,
    nfcUnlockEnabled = settings.nfcUnlock.anyTag,
    nfcUnlockTagIds = settings.nfcUnlock.tagIds,
)

private const val MAX_NAME_LENGTH = 40
