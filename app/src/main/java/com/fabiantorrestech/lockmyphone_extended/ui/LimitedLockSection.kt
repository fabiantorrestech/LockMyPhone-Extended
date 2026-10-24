package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.core.lock.TimeBank
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockStyle

/**
 * A profile's lock style: Locked (only allowed apps), Limited (any app in short sessions with
 * growing cooldowns) or Timed (only allowed apps, on that timer). Simple: the style, the session
 * length, the first cooldown and Timed's untimed apps. Pro: how much longer each cooldown gets, the
 * longest one, the free peeks, and Timed's time banks.
 */
@Composable
fun LimitedLockSection(draft: LockProfile, onDraftChange: (LockProfile) -> Unit) {
    val catalog = LockApp.container(LocalContext.current).appCatalog
    SettingsSection(stringResource(R.string.lock_style_title)) {
        Column(SectionContentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LockStyle.entries.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = draft.lockStyle == style,
                        onClick = {
                            var next = draft.copy(lockStyle = style)
                            // First time Timed: the clock and the camera start untimed.
                            if (style == LockStyle.TIMED_APPS && next.untimedPackages.isEmpty()) {
                                next = next.copy(
                                    untimedPackages = setOfNotNull(catalog.clockPackage(), catalog.defaultCameraPackage()) intersect next.allowedPackages,
                                )
                            }
                            onDraftChange(next)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = LockStyle.entries.size),
                    ) {
                        Text(stringResource(styleLabel(style)))
                    }
                }
            }
            Text(stringResource(styleHint(draft.lockStyle)), style = MaterialTheme.typography.bodyMedium)
            if (draft.lockStyle != LockStyle.LOCKED && draft.limitPeekCount > 0) {
                Text(
                    stringResource(R.string.lock_style_limited_peeks, draft.limitPeekSeconds, draft.limitPeekCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (draft.lockStyle != LockStyle.LOCKED) {
                val minutes = @Composable { value: Int -> stringResource(R.string.limit_minutes, value) }
                ChoiceChips(stringResource(R.string.limit_session_length), SESSION_MINUTES, draft.limitSessionMinutes, minutes) {
                    onDraftChange(draft.copy(limitSessionMinutes = it))
                }
                ChoiceChips(stringResource(R.string.limit_first_cooldown), COOLDOWN_MINUTES, draft.limitCooldownMinutes, minutes) {
                    onDraftChange(draft.copy(limitCooldownMinutes = it))
                }
                if (draft.lockStyle == LockStyle.TIMED_APPS) {
                    // The phone is always untimed, so it isn't offered.
                    val dialer = catalog.defaultDialerPackage()
                    val apps = remember(draft.allowedPackages) {
                        draft.allowedPackages.filter { it != dialer }
                            .map { LaunchableApp(it, catalog.label(it)) }
                            .sortedBy { it.label.lowercase() }
                    }
                    UntimedApps(apps, draft.untimedPackages) { onDraftChange(draft.copy(untimedPackages = it)) }
                    if (isPro()) {
                        TimeBanks(apps.filter { it.packageName !in draft.untimedPackages }, draft.timeBanks) {
                            onDraftChange(draft.copy(timeBanks = it))
                        }
                    }
                }
                if (isPro()) {
                    ChoiceChips(stringResource(R.string.limit_cooldown_step), STEP_MINUTES, draft.limitCooldownStepMinutes, minutes) {
                        onDraftChange(draft.copy(limitCooldownStepMinutes = it))
                    }
                    ChoiceChips(stringResource(R.string.limit_max_cooldown), MAX_COOLDOWN_MINUTES, draft.limitMaxCooldownMinutes, minutes) {
                        onDraftChange(draft.copy(limitMaxCooldownMinutes = it))
                    }
                    ChoiceChips(stringResource(R.string.limit_peeks), PEEK_COUNTS, draft.limitPeekCount, { it.toString() }) {
                        onDraftChange(draft.copy(limitPeekCount = it))
                    }
                    ChoiceChips(
                        stringResource(R.string.limit_peek_length), PEEK_SECONDS, draft.limitPeekSeconds,
                        { stringResource(R.string.limit_seconds, it) },
                    ) { onDraftChange(draft.copy(limitPeekSeconds = it)) }
                }
            }
        }
    }
}

private fun styleLabel(style: LockStyle): Int = when (style) {
    LockStyle.LOCKED -> R.string.lock_style_locked
    LockStyle.LIMITED -> R.string.lock_style_limited
    LockStyle.TIMED_APPS -> R.string.lock_style_timed
}

private fun styleHint(style: LockStyle): Int = when (style) {
    LockStyle.LOCKED -> R.string.lock_style_locked_hint
    LockStyle.LIMITED -> R.string.lock_style_limited_hint
    LockStyle.TIMED_APPS -> R.string.lock_style_timed_hint
}

/** Timed: which allowed apps never count, chosen in a checklist. */
@Composable
private fun UntimedApps(apps: List<LaunchableApp>, untimed: Set<String>, onChange: (Set<String>) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val names = apps.filter { it.packageName in untimed }.joinToString { it.label }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.timed_untimed_title), style = MaterialTheme.typography.titleSmall)
            Text(names.ifEmpty { stringResource(R.string.timed_untimed_none) }, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { choosing = true }) { Text(stringResource(R.string.timed_choose)) }
    }
    if (choosing) {
        var picked by remember { mutableStateOf(untimed intersect apps.map { it.packageName }.toSet()) }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.timed_untimed_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    apps.forEach { app ->
                        val checked = app.packageName in picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picked = if (checked) picked - app.packageName else picked + app.packageName },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(app.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(picked)
                    choosing = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/**
 * Pro, Timed: which time bank each timed app counts in. Apps in a named bank share its session and
 * cooldown; the rest share one. "Own timer" puts an app in a bank of its own, named after it.
 */
@Composable
private fun TimeBanks(timedApps: List<LaunchableApp>, banks: List<TimeBank>, onChange: (List<TimeBank>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.timed_banks_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.timed_banks_hint), style = MaterialTheme.typography.bodyMedium)
        if (timedApps.isEmpty()) {
            Text(stringResource(R.string.timed_no_timed_apps), style = MaterialTheme.typography.bodyMedium)
        }
        var naming by remember { mutableStateOf<String?>(null) }
        timedApps.forEach { app ->
            val bank = banks.firstOrNull { app.packageName in it.packages }?.name
            var menuOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text(bank ?: stringResource(R.string.timed_bank_shared)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.timed_bank_shared)) },
                            onClick = {
                                onChange(assign(banks, app.packageName, null))
                                menuOpen = false
                            },
                        )
                        banks.map { it.name }.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onChange(assign(banks, app.packageName, name))
                                    menuOpen = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.timed_bank_own)) },
                            onClick = {
                                onChange(assign(banks, app.packageName, app.label))
                                menuOpen = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.timed_bank_new)) },
                            onClick = {
                                naming = app.packageName
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        naming?.let { packageName ->
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { naming = null },
                title = { Text(stringResource(R.string.timed_bank_new)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_BANK_NAME) },
                        label = { Text(stringResource(R.string.timed_bank_name)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            onChange(assign(banks, packageName, name.trim()))
                            naming = null
                        },
                    ) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }
}

/** [banks] with [packageName] moved to the bank named [bankName] (made if needed), or to the shared one for null. Empty banks go. */
private fun assign(banks: List<TimeBank>, packageName: String, bankName: String?): List<TimeBank> {
    val without = banks.map { it.copy(packages = it.packages - packageName) }
    val moved = when {
        bankName == null -> without
        without.any { it.name == bankName } -> without.map { if (it.name == bankName) it.copy(packages = it.packages + packageName) else it }
        else -> without + TimeBank(bankName, setOf(packageName))
    }
    return moved.filter { it.packages.isNotEmpty() }
}

/** A label and one-tap choices; a saved value that isn't among them still shows as selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceChips(title: String, options: List<Int>, selected: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (options + selected).distinct().sorted().forEach { value ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label(value)) })
            }
        }
    }
}

private const val MAX_BANK_NAME = 30
private val SESSION_MINUTES = listOf(3, 5, 10, 15)
private val COOLDOWN_MINUTES = listOf(1, 2, 3, 5)
private val STEP_MINUTES = listOf(0, 1, 2, 5)
private val MAX_COOLDOWN_MINUTES = listOf(5, 10, 15, 30)
private val PEEK_COUNTS = listOf(0, 3, 5)
private val PEEK_SECONDS = listOf(15, 30, 60)
