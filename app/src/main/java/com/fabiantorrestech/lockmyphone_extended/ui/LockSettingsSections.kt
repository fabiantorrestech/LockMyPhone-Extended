package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.dnd.isGrayscaleSupported
import com.fabiantorrestech.lockmyphone_extended.core.dnd.labelRes
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag

/**
 * The settings every lock has, whether it's a profile, the Lock defaults or Quick lock. The
 * editors map their own storage to and from this.
 */
data class LockSettings(
    val durationSec: Long,
    val allowedAppCount: Int,
    val bypassEnabled: Boolean,
    val bypassWaitMinutes: Int,
    val dndEnabled: Boolean,
    val dndLevel: DndLevel,
    val grayscale: Boolean,
    val resumeMode: UnlockResumeMode,
    val choiceSeconds: Int,
)

/**
 * Length · Allowed apps · Focus · Unlocking, as settings sections. Each change is reported
 * right away through [onChange]; dialogs only report valid values.
 *
 * @param lengthTitle e.g. "Default length" for a profile, "Length" for Quick lock.
 * @param nfcTags the registered tags, to show which are linked to this lock.
 * @param lockId this lock's ID (a profile ID as text, or Quick lock's), or null for the Lock
 *   defaults and unsaved profiles, which no tag can be linked to yet.
 */
@Composable
fun LockSettingsSections(
    settings: LockSettings,
    presetsSec: List<Long>,
    nfcTags: List<NfcTag>,
    lockId: String?,
    lengthTitle: String,
    onChange: (LockSettings) -> Unit,
    onEditAllowedApps: () -> Unit,
) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<LockDialog?>(null) }

    SettingsSection(stringResource(R.string.section_length)) {
        SettingsRow(
            icon = Icons.Filled.Timer,
            title = lengthTitle,
            summary = formatDurationLabel(settings.durationSec * 1_000),
            onClick = { editing = LockDialog.LENGTH },
        )
        DurationPresetChips(
            presetsSec = presetsSec,
            selectedMs = settings.durationSec * 1_000,
            onPick = { onChange(settings.copy(durationSec = it / 1_000)) },
            modifier = SectionContentPadding,
        )
    }

    SettingsSection(stringResource(R.string.allowed_title)) {
        SettingsRow(
            icon = Icons.Filled.Apps,
            title = stringResource(R.string.allowed_edit),
            summary = context.resources.getQuantityString(R.plurals.apps_count, settings.allowedAppCount, settings.allowedAppCount) +
                " · " + stringResource(R.string.profile_phone_always),
            onClick = onEditAllowedApps,
        )
    }

    SettingsSection(stringResource(R.string.section_focus)) {
        SwitchSettingsRow(
            icon = Icons.Filled.DoNotDisturbOn,
            title = stringResource(R.string.dnd_switch),
            summary = if (settings.dndEnabled) stringResource(settings.dndLevel.labelRes()) else stringResource(R.string.off),
            checked = settings.dndEnabled,
            onCheckedChange = { onChange(settings.copy(dndEnabled = it)) },
        )
        if (settings.dndEnabled) {
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.dnd_level_title),
                summary = stringResource(settings.dndLevel.labelRes()),
                onClick = { editing = LockDialog.DND_LEVEL },
            )
        }
        SectionDivider()
        val grayscaleSupported = isGrayscaleSupported()
        SwitchSettingsRow(
            icon = Icons.Filled.InvertColors,
            title = stringResource(R.string.grayscale_switch),
            summary = stringResource(if (grayscaleSupported) R.string.grayscale_hint else R.string.grayscale_unsupported),
            checked = settings.grayscale && grayscaleSupported,
            onCheckedChange = { onChange(settings.copy(grayscale = it)) },
            enabled = grayscaleSupported,
        )
    }

    SettingsSection(stringResource(R.string.section_unlocking)) {
        SwitchSettingsRow(
            icon = Icons.Filled.LockOpen,
            title = stringResource(R.string.bypass_switch),
            summary = if (settings.bypassEnabled) {
                if (settings.bypassWaitMinutes == 0) {
                    stringResource(R.string.bypass_summary_instant)
                } else {
                    stringResource(R.string.bypass_summary_on, settings.bypassWaitMinutes)
                }
            } else {
                stringResource(R.string.bypass_summary_off)
            },
            checked = settings.bypassEnabled,
            onCheckedChange = { onChange(settings.copy(bypassEnabled = it)) },
        )
        if (settings.bypassEnabled) {
            SectionDivider()
            // A 0-minute wait is allowed, but then the lock is only a speed bump.
            if (settings.bypassWaitMinutes == 0) DangerBanner(stringResource(R.string.bypass_instant_warning))
            SettingsRow(
                icon = Icons.Filled.HourglassEmpty,
                title = stringResource(R.string.bypass_wait_label),
                summary = if (settings.bypassWaitMinutes == 0) {
                    stringResource(R.string.bypass_wait_instant)
                } else {
                    stringResource(R.string.wait_minutes, settings.bypassWaitMinutes)
                },
                onClick = { editing = LockDialog.BYPASS_WAIT },
            )
        }
        SectionDivider()
        SettingsRow(
            icon = Icons.Filled.ScreenLockPortrait,
            title = stringResource(R.string.unlock_resume_title),
            summary = when (settings.resumeMode) {
                UnlockResumeMode.RESUME_APP -> stringResource(R.string.unlock_resume_app)
                UnlockResumeMode.SHOW_LOCK_SCREEN -> stringResource(R.string.unlock_resume_summary_lock_screen, settings.choiceSeconds)
            },
            onClick = { editing = LockDialog.RESUME },
        )
        if (lockId != null) {
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Nfc,
                title = stringResource(R.string.nfc_linked_title),
                summary = linkedTagsSummary(lockId, nfcTags) + "\n" + stringResource(R.string.nfc_linked_hint),
                onClick = null,
            )
        }
    }

    when (editing) {
        LockDialog.LENGTH -> DurationDialog(
            title = lengthTitle,
            initialMs = settings.durationSec * 1_000,
            presetsSec = presetsSec,
            onConfirm = {
                onChange(settings.copy(durationSec = it / 1_000))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LockDialog.DND_LEVEL -> ChoiceDialog(
            title = stringResource(R.string.dnd_level_title),
            options = DndLevel.entries,
            selected = settings.dndLevel,
            label = { stringResource(it.labelRes()) },
            description = { stringResource(dndLevelDescription(it)) },
            footer = stringResource(R.string.dnd_level_hint),
            onSelect = {
                onChange(settings.copy(dndLevel = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LockDialog.BYPASS_WAIT -> NumberDialog(
            title = stringResource(R.string.bypass_wait_label),
            label = stringResource(R.string.minutes_label),
            initial = settings.bypassWaitMinutes,
            // 0 = instant early unlock (warned about above the row).
            range = 0..MAX_BYPASS_WAIT_MINUTES,
            hint = stringResource(R.string.bypass_hint),
            onConfirm = {
                onChange(settings.copy(bypassWaitMinutes = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LockDialog.RESUME -> UnlockResumeDialog(
            mode = settings.resumeMode,
            seconds = settings.choiceSeconds,
            onConfirm = { mode, seconds ->
                onChange(settings.copy(resumeMode = mode, choiceSeconds = seconds))
                editing = null
            },
            onDismiss = { editing = null },
        )
        null -> Unit
    }
}

private enum class LockDialog { LENGTH, DND_LEVEL, BYPASS_WAIT, RESUME }

/** The tags linked to this lock ("Fridge, Desk"), or "None". Links are set on each tag. */
@Composable
private fun linkedTagsSummary(lockId: String, tags: List<NfcTag>): String {
    val names = tags.filter { it.links(lockId) }.map { it.name }
    return if (names.isEmpty()) stringResource(R.string.nfc_linked_none) else names.joinToString(", ")
}

private const val MAX_BYPASS_WAIT_MINUTES = 24 * 60

fun dndLevelDescription(level: DndLevel): Int = when (level) {
    DndLevel.PRIORITY -> R.string.dnd_level_priority_desc
    DndLevel.ALARMS -> R.string.dnd_level_alarms_desc
    DndLevel.TOTAL_SILENCE -> R.string.dnd_level_silence_desc
}

/** "After the screen turns off in an allowed app", with the seconds field for the lock-screen choice. */
@Composable
private fun UnlockResumeDialog(
    mode: UnlockResumeMode,
    seconds: Int,
    onConfirm: (UnlockResumeMode, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosenMode by remember { mutableStateOf(mode) }
    var secondsText by remember { mutableStateOf(seconds.toString()) }
    val parsedSeconds = parseUnlockChoiceSeconds(secondsText)
    val valid = chosenMode == UnlockResumeMode.RESUME_APP || parsedSeconds != null
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                UnlockResumeOptions(
                    mode = chosenMode,
                    onModeChange = { chosenMode = it },
                    secondsText = secondsText,
                    onSecondsTextChange = { secondsText = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosenMode, parsedSeconds ?: seconds) }, enabled = valid) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
