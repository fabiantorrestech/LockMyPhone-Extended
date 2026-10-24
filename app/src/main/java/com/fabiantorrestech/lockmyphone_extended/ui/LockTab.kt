package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.perm.AppPermission
import com.fabiantorrestech.lockmyphone_extended.core.perm.PermissionNeeds
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile

/**
 * The Lock tab: Quick lock on top (pick a length, Start), then your profiles as tiles, each with
 * its own ▶. Tapping a tile edits it. In Pro mode the profiles can also be reordered.
 */
@Composable
fun LockTab(
    profiles: List<LockProfile>,
    adminActive: Boolean,
    accessibilityEnabled: Boolean,
    granted: Set<AppPermission>,
    quickLock: LockSettings,
    presetsSec: List<Long>,
    onOpenSettings: () -> Unit,
    onEditProfile: (LockProfile?) -> Unit,
    onReorderProfiles: (List<LockProfile>) -> Unit,
    actions: StartLockActions,
) {
    var starting by remember { mutableStateOf<LockProfile?>(null) }
    var reordering by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!adminActive || !accessibilityEnabled) SetupBanner(onOpenSettings)
        QuickLockCard(quickLock, presetsSec, adminActive, actions)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.lock_tab_profiles),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (isPro() && profiles.size > 1) {
                IconButton(onClick = { reordering = !reordering }) {
                    Icon(
                        if (reordering) Icons.Filled.Check else Icons.Filled.SwapVert,
                        contentDescription = stringResource(if (reordering) R.string.lock_tab_reorder_done else R.string.lock_tab_reorder),
                    )
                }
            }
        }

        if (reordering && isPro()) {
            ReorderList(profiles, onReorderProfiles)
        } else {
            ProfileGrid(
                profiles = profiles,
                adminActive = adminActive,
                granted = granted,
                onEdit = onEditProfile,
                onStart = { starting = it },
            )
        }
        if (!accessibilityEnabled) {
            Text(stringResource(R.string.allowed_needs_accessibility), style = MaterialTheme.typography.bodySmall)
        }
    }

    starting?.let { profile ->
        StartProfileDialog(
            profile = profile,
            onStart = { durationMs ->
                starting = null
                actions.onStartProfile(profile, durationMs)
            },
            onDismiss = { starting = null },
        )
    }
}

/** Quick lock: the length (tap to change, or a preset), what it allows, and a big Start. */
@Composable
private fun QuickLockCard(quickLock: LockSettings, presetsSec: List<Long>, adminActive: Boolean, actions: StartLockActions) {
    val context = LocalContext.current
    var pickingLength by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val durationMs = quickLock.durationSec * 1_000

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.quick_lock), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onOpenQuickOptions) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.quick_options))
                }
            }
            Column(Modifier.clickable { pickingLength = true }) {
                Text(stringResource(R.string.quick_lock_for), style = MaterialTheme.typography.labelLarge)
                Text(formatLengthLabel(durationMs), style = MaterialTheme.typography.displaySmall)
            }
            DurationPresetChips(
                presetsSec = presetsSec,
                selectedMs = durationMs,
                onPick = { actions.onQuickSettingsChange(quickLock.copy(durationSec = it / 1_000)) },
                withNoEnd = true,
            )
            Text(
                quickSummary(context, quickLock),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = { confirming = true },
                enabled = adminActive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_button), style = MaterialTheme.typography.titleMedium)
            }
            if (!adminActive) Text(stringResource(R.string.start_needs_admin), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (pickingLength) {
        DurationDialog(
            title = stringResource(R.string.lock_length),
            initialMs = durationMs,
            presetsSec = presetsSec,
            onConfirm = {
                actions.onQuickSettingsChange(quickLock.copy(durationSec = it / 1_000))
                pickingLength = false
            },
            onDismiss = { pickingLength = false },
        )
    }
    if (confirming) {
        ConfirmLockDialog(
            durationMs = durationMs,
            bypassWaitMinutes = quickLock.bypassWaitMinutes.takeIf { quickLock.bypassEnabled },
            onConfirm = {
                confirming = false
                actions.onStartQuick()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** "3 apps · early unlock after 10 min", like a profile's summary. */
private fun quickSummary(context: android.content.Context, quick: LockSettings): String {
    val parts = mutableListOf(context.resources.getQuantityString(R.plurals.apps_count, quick.allowedAppCount, quick.allowedAppCount))
    parts += when {
        !quick.bypassEnabled -> context.getString(R.string.profile_summary_no_bypass)
        quick.bypassWaitMinutes == 0 -> context.getString(R.string.profile_summary_bypass_instant)
        else -> context.getString(R.string.profile_summary_bypass, quick.bypassWaitMinutes)
    }
    return parts.joinToString(" · ")
}

/** Profiles two per row, then a "New profile" tile. */
@Composable
private fun ProfileGrid(
    profiles: List<LockProfile>,
    adminActive: Boolean,
    granted: Set<AppPermission>,
    onEdit: (LockProfile?) -> Unit,
    onStart: (LockProfile) -> Unit,
) {
    val tiles: List<LockProfile?> = profiles + null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { profile ->
                    if (profile == null) {
                        NewProfileTile(onClick = { onEdit(null) }, modifier = Modifier.weight(1f))
                    } else {
                        ProfileTile(profile, adminActive, granted, onEdit = { onEdit(profile) }, onStart = { onStart(profile) }, modifier = Modifier.weight(1f))
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ProfileTile(
    profile: LockProfile,
    adminActive: Boolean,
    granted: Set<AppPermission>,
    onEdit: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Without DND access the lock still works, just without DND / grayscale.
    val pause = pauseNote(context, PermissionNeeds.check(PermissionNeeds.forProfile(profile), granted, partial = true))
    Card(
        onClick = onEdit,
        modifier = modifier.heightIn(min = TILE_MIN_HEIGHT),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileIcon(profile.icon, profile.name, size = 44.dp)
                Spacer(Modifier.weight(1f))
                FilledIconButton(onClick = onStart, enabled = adminActive, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.profile_start_named, profile.name))
                }
            }
            Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                tileSummary(context, profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            pause?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** "50 min · 4 apps": shorter than the full profile summary, so it fits a tile. */
private fun tileSummary(context: android.content.Context, profile: LockProfile): String {
    val visible = (profile.allowedPackages - profile.hiddenPackages).size
    return formatLengthLabel(profile.defaultDurationSec * 1_000) + " · " +
        context.resources.getQuantityString(R.plurals.apps_count, visible, visible)
}

@Composable
private fun NewProfileTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = TILE_MIN_HEIGHT),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(stringResource(R.string.profile_new), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Pro: hold and drag a row to reorder the profiles. */
@Composable
private fun ReorderList(profiles: List<LockProfile>, onReorder: (List<LockProfile>) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.reorder_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ReorderableColumn(profiles, key = { it.id }, onReorder = onReorder) { profile, _ ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProfileIcon(profile.icon, profile.name, size = 36.dp)
                    Text(profile.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

private val TILE_MIN_HEIGHT = 148.dp
