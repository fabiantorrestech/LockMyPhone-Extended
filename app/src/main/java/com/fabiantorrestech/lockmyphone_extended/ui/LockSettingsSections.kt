package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Modifier
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
import com.fabiantorrestech.lockmyphone_extended.core.lock.NfcUnlock
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
    val nfcUnlock: NfcUnlock,
)

/**
 * Length · Allowed apps · Focus · Unlocking, as settings sections. Each change is reported
 * right away through [onChange]; dialogs only report valid values.
 *
 * @param lengthTitle e.g. "Default length" for a profile, "Length" for Quick lock.
 * @param nfcTags the registered tags, to choose which can end this lock.
 */
@Composable
fun LockSettingsSections(
    settings: LockSettings,
    presetsSec: List<Long>,
    nfcTags: List<NfcTag>,
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
                stringResource(R.string.bypass_summary_on, settings.bypassWaitMinutes)
            } else {
                stringResource(R.string.bypass_summary_off)
            },
            checked = settings.bypassEnabled,
            onCheckedChange = { onChange(settings.copy(bypassEnabled = it)) },
        )
        if (settings.bypassEnabled) {
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.HourglassEmpty,
                title = stringResource(R.string.bypass_wait_label),
                summary = stringResource(R.string.wait_minutes, settings.bypassWaitMinutes),
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
        SectionDivider()
        SettingsRow(
            icon = Icons.Filled.Nfc,
            title = stringResource(R.string.nfc_unlock_tags_title),
            summary = nfcUnlockSummary(settings.nfcUnlock, nfcTags),
            onClick = { editing = LockDialog.NFC_TAGS },
        )
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
            range = 1..MAX_BYPASS_WAIT_MINUTES,
            hint = stringResource(R.string.bypass_hint),
            onConfirm = {
                onChange(settings.copy(bypassWaitMinutes = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        LockDialog.NFC_TAGS -> NfcUnlockDialog(
            current = settings.nfcUnlock,
            tags = nfcTags,
            onConfirm = {
                onChange(settings.copy(nfcUnlock = it))
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

private enum class LockDialog { LENGTH, DND_LEVEL, BYPASS_WAIT, RESUME, NFC_TAGS }

/** "None", "Fridge, Desk" or "Any of my written tags". Chosen tags that were deleted don't count. */
@Composable
private fun nfcUnlockSummary(nfcUnlock: NfcUnlock, tags: List<NfcTag>): String {
    if (nfcUnlock.anyTag) return stringResource(R.string.nfc_unlock_any)
    val names = tags.filter { nfcUnlock.allows(it.id) }.map { it.name }
    return if (names.isEmpty()) stringResource(R.string.nfc_unlock_none) else names.joinToString(", ")
}

/** Tick the tags that can end this lock, or "Any of my written tags". Tags that aren't written can't be picked. */
@Composable
private fun NfcUnlockDialog(
    current: NfcUnlock,
    tags: List<NfcTag>,
    onConfirm: (NfcUnlock) -> Unit,
    onDismiss: () -> Unit,
) {
    var anyTag by remember { mutableStateOf(current.anyTag) }
    var chosen by remember { mutableStateOf(current.tagIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nfc_unlock_tags_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.nfc_unlock_dialog_hint), style = MaterialTheme.typography.bodySmall)
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.nfc_unlock_any),
                    summary = null,
                    onClick = { anyTag = !anyTag },
                    trailing = { Checkbox(checked = anyTag, onCheckedChange = { anyTag = it }) },
                )
                if (tags.isEmpty()) {
                    Text(stringResource(R.string.nfc_tags_empty_short), style = MaterialTheme.typography.bodySmall)
                }
                tags.forEach { tag ->
                    val id = tag.id.toString()
                    val selectable = tag.written && !anyTag
                    val toggle = { chosen = if (id in chosen) chosen - id else chosen + id }
                    SettingsRow(
                        icon = null,
                        title = tag.name,
                        summary = if (tag.written) null else stringResource(R.string.nfc_not_written_short),
                        onClick = toggle,
                        enabled = selectable,
                        trailing = {
                            Checkbox(
                                checked = anyTag || (tag.written && id in chosen),
                                onCheckedChange = { toggle() },
                                enabled = selectable,
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Only tags that still exist and are written are kept.
                val writtenIds = tags.filter { it.written }.map { it.id.toString() }.toSet()
                onConfirm(NfcUnlock(anyTag = anyTag, tagIds = if (anyTag) emptySet() else chosen intersect writtenIds))
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
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
