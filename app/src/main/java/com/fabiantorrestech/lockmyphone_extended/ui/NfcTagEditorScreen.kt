package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcLinkMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcDuringLock
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTagAction
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcRules

/** What the NFC reader is waiting for while the tag editor is open. */
enum class TagScanMode {
    /** Read the tag's ID and register it. */
    SCAN,

    /** Write this app's record to the tag, so it works while the app is closed. */
    WRITE,
}

/**
 * Creates or edits an NFC tag:
 * - Basics: name, icon, on/off
 * - Tag: scan it (its hardware ID), write to it
 * - What it does: start a lock, end a lock, or both
 * - Linked locks: one (the default), several, or all
 * - During a lock: start the early-unlock wait, end it if it's a linked lock, or end any lock
 * - Length of a lock it starts
 *
 * Save on a tag that isn't written asks to write it first (the caller runs the reader), with
 * "Save without writing" as the way out.
 *
 * @param scanning set while the reader waits for a tag; the caller runs the reader.
 * @param savePending the reader is waiting to write the tag before saving.
 */
@Composable
fun NfcTagEditorScreen(
    draft: NfcTag,
    profiles: List<LockProfile>,
    hasNfc: Boolean,
    nfcOn: Boolean,
    scanning: TagScanMode?,
    savePending: Boolean,
    presetsSec: List<Long>,
    onOpenNfcSettings: () -> Unit,
    onScan: (TagScanMode?) -> Unit,
    onDraftChange: (NfcTag) -> Unit,
    onSave: () -> Unit,
    onSaveWithoutWriting: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The hold note and "Test now"; null for a new automation's editor or none at all. */
    automation: AutomationEditorExtras? = null,
) {
    var editing by remember { mutableStateOf<TagDialog?>(null) }
    val isNew = draft.id == 0L
    // With one linked profile, its icon and length are the fallbacks.
    val onlyProfile = profiles.firstOrNull { draft.linkMode == NfcLinkMode.ONE && draft.links(it.id.toString()) }
    val hasLinkedLock = draft.linkMode == NfcLinkMode.ALL ||
        draft.linkedLockIds.any { id -> id == QUICK_LOCK_ID || profiles.any { it.id.toString() == id } }

    EditorScaffold(
        title = stringResource(if (isNew) R.string.nfc_tag_new else R.string.nfc_tag_edit_title),
        onBack = onBack,
        saveEnabled = draft.name.isNotBlank() && hasLinkedLock,
        onSave = onSave,
        onDelete = if (isNew) null else ({ editing = TagDialog.DELETE }),
        modifier = modifier,
        automation = automation,
    ) {
        when {
            !hasNfc -> Text(stringResource(R.string.nfc_no_hardware), style = MaterialTheme.typography.bodyMedium)
            !nfcOn -> {
                Text(stringResource(R.string.nfc_off_hint), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onOpenNfcSettings, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nfc_turn_on))
                }
            }
        }

        SettingsSection(stringResource(R.string.section_basics)) {
            Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onDraftChange(draft.copy(name = it.take(MAX_NAME_LENGTH))) },
                    label = { Text(stringResource(R.string.nfc_tag_nickname)) },
                    placeholder = { Text(stringResource(R.string.nfc_tag_name_hint)) },
                    singleLine = true,
                    isError = draft.name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                ProfileIconField(
                    icon = draft.icon,
                    name = draft.name,
                    onIconChange = { onDraftChange(draft.copy(icon = it)) },
                    fallbackIcon = automationIcon(null, onlyProfile),
                )
            }
            SectionDivider()
            SwitchSettingsRow(
                icon = Icons.Filled.PowerSettingsNew,
                title = stringResource(R.string.alarm_trigger_enabled),
                summary = null,
                checked = draft.enabled,
                onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
            )
        }

        SettingsSection(stringResource(R.string.nfc_section_tag)) {
            SettingsRow(
                icon = Icons.Filled.Nfc,
                title = stringResource(if (draft.uid.isEmpty()) R.string.nfc_scan else R.string.nfc_rescan),
                summary = if (draft.uid.isEmpty()) {
                    stringResource(R.string.nfc_not_scanned)
                } else {
                    stringResource(R.string.nfc_tag_id, NfcRules.formatUid(draft.uid)) + "\n" +
                        stringResource(if (draft.written) R.string.nfc_written_status else R.string.nfc_not_written_status)
                },
                onClick = { onScan(TagScanMode.SCAN) },
                enabled = hasNfc && nfcOn,
            )
            SectionDivider()
            SettingsRow(
                icon = Icons.Filled.Edit,
                title = stringResource(R.string.nfc_write),
                summary = stringResource(R.string.nfc_write_hint),
                onClick = { onScan(TagScanMode.WRITE) },
                enabled = hasNfc && nfcOn,
            )
        }

        SettingsSection(stringResource(R.string.nfc_section_action)) {
            NfcTagAction.entries.forEach { action ->
                RadioSettingsRow(
                    title = stringResource(actionLabel(action)),
                    summary = stringResource(actionDescription(action)),
                    selected = draft.action == action,
                    onSelect = { onDraftChange(draft.copy(action = action)) },
                )
            }
        }

        if (draft.action == NfcTagAction.START) {
            SettingsSection(stringResource(R.string.nfc_section_during_lock)) {
                SwitchSettingsRow(
                    icon = Icons.Filled.SwapHoriz,
                    title = stringResource(R.string.nfc_take_over),
                    summary = stringResource(R.string.nfc_take_over_hint),
                    checked = draft.takeOver,
                    onCheckedChange = { onDraftChange(draft.copy(takeOver = it)) },
                )
            }
        }

        SettingsSection(stringResource(R.string.nfc_section_links)) {
            NfcLinkMode.entries.forEach { mode ->
                RadioSettingsRow(
                    title = stringResource(linkModeLabel(mode)),
                    summary = stringResource(linkModeDescription(mode)),
                    selected = draft.linkMode == mode,
                    onSelect = {
                        // One lock keeps just the first of several.
                        val ids = if (mode == NfcLinkMode.ONE) draft.linkedLockIds.take(1).toSet() else draft.linkedLockIds
                        onDraftChange(draft.copy(linkMode = mode, linkedLockIds = ids.ifEmpty { setOf(QUICK_LOCK_ID) }))
                    },
                )
            }
            if (draft.linkMode != NfcLinkMode.ALL) {
                SectionDivider()
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = stringResource(if (draft.linkMode == NfcLinkMode.ONE) R.string.lock_target else R.string.nfc_link_locks),
                    summary = linkedLocksSummary(draft, profiles).ifEmpty { stringResource(R.string.nfc_link_none_chosen) },
                    onClick = { editing = if (draft.linkMode == NfcLinkMode.ONE) TagDialog.LOCK_ONE else TagDialog.LOCKS_SOME },
                )
            }
        }

        if (draft.actsDuringLock) {
            SettingsSection(stringResource(R.string.nfc_section_during_lock)) {
                if (draft.endsLocks && !draft.written) {
                    Text(
                        stringResource(R.string.nfc_needs_write_to_end),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = SectionContentPadding,
                    )
                }
                NfcDuringLock.entries.forEach { choice ->
                    RadioSettingsRow(
                        title = stringResource(duringLockLabel(choice)),
                        summary = stringResource(duringLockDescription(choice)),
                        selected = draft.duringLock == choice,
                        onSelect = { onDraftChange(draft.copy(duringLock = choice)) },
                    )
                }
            }
        }

        if (draft.startsLocks) {
            SettingsSection(stringResource(R.string.section_length)) {
                SwitchSettingsRow(
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.nfc_own_length),
                    summary = stringResource(R.string.nfc_own_length_hint),
                    checked = draft.durationSec == null,
                    onCheckedChange = { useOwn ->
                        val fallbackSec = onlyProfile?.defaultDurationSec ?: DEFAULT_TAG_LENGTH_SEC
                        onDraftChange(draft.copy(durationSec = if (useOwn) null else fallbackSec))
                    },
                )
                draft.durationSec?.let { seconds ->
                    SectionDivider()
                    SettingsRow(
                        icon = Icons.Filled.Timer,
                        title = stringResource(R.string.lock_length),
                        summary = formatDurationLabel(seconds * 1_000),
                        onClick = { editing = TagDialog.LENGTH },
                    )
                }
            }
        }
    }

    scanning?.let { mode ->
        val writingToSave = savePending && mode == TagScanMode.WRITE
        AlertDialog(
            onDismissRequest = { onScan(null) },
            icon = { Icon(Icons.Filled.Nfc, contentDescription = null) },
            title = { Text(stringResource(if (mode == TagScanMode.SCAN) R.string.nfc_scan else R.string.nfc_write)) },
            text = {
                Text(
                    stringResource(
                        when {
                            mode == TagScanMode.SCAN -> R.string.nfc_hold_to_scan
                            writingToSave -> R.string.nfc_hold_to_write_save
                            else -> R.string.nfc_hold_to_write
                        }
                    )
                )
            },
            confirmButton = {
                // Saving unwritten needs the tag's ID, so it must have been scanned.
                if (writingToSave) {
                    TextButton(onClick = onSaveWithoutWriting, enabled = draft.uid.isNotEmpty()) {
                        Text(stringResource(R.string.nfc_save_without_writing))
                    }
                }
            },
            dismissButton = { TextButton(onClick = { onScan(null) }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    when (editing) {
        TagDialog.LOCK_ONE -> ChoiceDialog(
            title = stringResource(R.string.lock_target),
            options = lockOptions(profiles),
            selected = draft.linkedLockIds.firstOrNull() ?: QUICK_LOCK_ID,
            label = { id -> lockName(id, profiles) },
            onSelect = {
                onDraftChange(draft.copy(linkedLockIds = setOf(it)))
                editing = null
            },
            onDismiss = { editing = null },
        )
        TagDialog.LOCKS_SOME -> LocksDialog(
            profiles = profiles,
            selected = draft.linkedLockIds,
            onConfirm = {
                onDraftChange(draft.copy(linkedLockIds = it))
                editing = null
            },
            onDismiss = { editing = null },
        )
        TagDialog.LENGTH -> DurationDialog(
            title = stringResource(R.string.lock_length),
            initialMs = (draft.durationSec ?: DEFAULT_TAG_LENGTH_SEC) * 1_000,
            presetsSec = presetsSec,
            onConfirm = {
                onDraftChange(draft.copy(durationSec = it / 1_000))
                editing = null
            },
            onDismiss = { editing = null },
        )
        TagDialog.DELETE -> AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.location_delete_title, draft.name)) },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
        null -> Unit
    }
}

private enum class TagDialog { LOCK_ONE, LOCKS_SOME, LENGTH, DELETE }

/** Every lock a tag can link to: the profiles in list order, then Quick lock. */
private fun lockOptions(profiles: List<LockProfile>): List<String> = profiles.map { it.id.toString() } + QUICK_LOCK_ID

@Composable
private fun lockName(lockId: String, profiles: List<LockProfile>): String =
    if (lockId == QUICK_LOCK_ID) stringResource(R.string.quick_lock) else profiles.firstOrNull { it.id.toString() == lockId }?.name.orEmpty()

/** "Study, Night" for the linked locks that still exist; empty if none do. */
@Composable
private fun linkedLocksSummary(tag: NfcTag, profiles: List<LockProfile>): String =
    lockOptions(profiles).filter { it in tag.linkedLockIds }.map { lockName(it, profiles) }.joinToString(", ")

/** Tick the locks a tag is linked to (at least one). */
@Composable
private fun LocksDialog(
    profiles: List<LockProfile>,
    selected: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(selected.filter { it in lockOptions(profiles) }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nfc_link_locks)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lockOptions(profiles).forEach { id ->
                    val toggle = { chosen = if (id in chosen) chosen - id else chosen + id }
                    SettingsRow(
                        icon = null,
                        title = lockName(id, profiles),
                        summary = null,
                        onClick = toggle,
                        trailing = { Checkbox(checked = id in chosen, onCheckedChange = { toggle() }) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private fun linkModeLabel(mode: NfcLinkMode): Int = when (mode) {
    NfcLinkMode.ONE -> R.string.nfc_link_one
    NfcLinkMode.SOME -> R.string.nfc_link_some
    NfcLinkMode.ALL -> R.string.nfc_link_all
}

private fun linkModeDescription(mode: NfcLinkMode): Int = when (mode) {
    NfcLinkMode.ONE -> R.string.nfc_link_one_desc
    NfcLinkMode.SOME -> R.string.nfc_link_some_desc
    NfcLinkMode.ALL -> R.string.nfc_link_all_desc
}

private const val MAX_NAME_LENGTH = 40
private const val DEFAULT_TAG_LENGTH_SEC = 60L * 60

private fun actionLabel(action: NfcTagAction): Int = when (action) {
    NfcTagAction.START -> R.string.nfc_action_start
    NfcTagAction.END -> R.string.nfc_action_end
    NfcTagAction.TOGGLE -> R.string.nfc_action_toggle
}

private fun actionDescription(action: NfcTagAction): Int = when (action) {
    NfcTagAction.START -> R.string.nfc_action_start_desc
    NfcTagAction.END -> R.string.nfc_action_end_desc
    NfcTagAction.TOGGLE -> R.string.nfc_action_toggle_desc
}

private fun duringLockLabel(choice: NfcDuringLock): Int = when (choice) {
    NfcDuringLock.START_BYPASS_WAIT -> R.string.nfc_during_bypass
    NfcDuringLock.END_IF_ALLOWED -> R.string.nfc_during_end_allowed
    NfcDuringLock.END_ANY -> R.string.nfc_during_end_any
}

private fun duringLockDescription(choice: NfcDuringLock): Int = when (choice) {
    NfcDuringLock.START_BYPASS_WAIT -> R.string.nfc_during_bypass_desc
    NfcDuringLock.END_IF_ALLOWED -> R.string.nfc_during_end_allowed_desc
    NfcDuringLock.END_ANY -> R.string.nfc_during_end_any_desc
}
