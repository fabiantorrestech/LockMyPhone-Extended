package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.InputChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.fabiantorrestech.lockmyphone_extended.data.MAX_DURATION_PRESETS
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationEngineChoice
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode
import com.fabiantorrestech.lockmyphone_extended.data.BypassDefaults
import com.fabiantorrestech.lockmyphone_extended.data.ThemeMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.trigger.IntentApiReceiver

/** The home screen's tabs, in order. */
enum class HomeTab { QUICK_LOCK, PROFILES, AUTOMATIONS, SETTINGS }

/**
 * The main screen while no lock is active: the app title, then four tabs:
 * - **Quick lock:** start a lock without a profile.
 * - **Profiles:** saved lock profiles.
 * - **Automations:** weekly schedules and alarm triggers.
 * - **Settings:** setup, lock defaults, intent API, device type, appearance and diagnostics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    selectedTab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    setup: SetupStatus,
    setupActions: SetupActions,
    deviceProfileName: String,
    onChangeDevice: () -> Unit,
    profiles: List<LockProfile>,
    schedules: List<LockSchedule>,
    alarmTriggers: List<AlarmTrigger>,
    appLabel: (packageName: String) -> String,
    onEditProfile: (LockProfile?) -> Unit,
    onEditSchedule: (LockSchedule?) -> Unit,
    onEditAlarmTrigger: (AlarmTrigger?) -> Unit,
    locations: List<LocationTrigger>,
    onEditLocation: (LocationTrigger?) -> Unit,
    locationEngine: LocationEngineChoice,
    playServicesAvailable: Boolean,
    onLocationEngineChange: (LocationEngineChoice) -> Unit,
    reorderActions: ReorderActions,
    alarmProblems: List<AlarmProblemUi>,
    alarmProblemActions: AlarmProblemActions,
    intentApi: IntentApiUiState,
    intentApiActions: IntentApiActions,
    onOpenDiagnostics: () -> Unit,
    onOpenLockDefaults: () -> Unit,
    quickLock: LockSettings,
    startActions: StartLockActions,
    presetsSec: List<Long>,
    onPresetsChange: (List<Long>) -> Unit,
    alarmDayStartMinutes: Int,
    onAlarmDayStartChange: (Int) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    showPickerIcons: Boolean,
    onShowPickerIconsChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.home_title)) },
            // The activity's Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        // Each tab keeps its own scroll position while you switch between them.
        val scrollStates = HomeTab.entries.associateWith { rememberScrollState() }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollStates.getValue(selectedTab))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    HomeTab.QUICK_LOCK -> {
                        if (!setup.adminActive || !setup.accessibilityEnabled) {
                            SetupBanner(onOpenSettings = { onTabChange(HomeTab.SETTINGS) })
                        }
                        QuickLockSection(setup.adminActive, quickLock, presetsSec, startActions)
                    }
                    HomeTab.PROFILES -> {
                        if (!setup.adminActive || !setup.accessibilityEnabled) {
                            SetupBanner(onOpenSettings = { onTabChange(HomeTab.SETTINGS) })
                        }
                        ProfilesCard(
                            profiles, setup.accessibilityEnabled, setup.adminActive, onEditProfile,
                            startActions.onStartProfile, reorderActions.onReorderProfiles, presetsSec,
                        )
                        // Room below the last profile, so the + button never covers its Start button.
                        Spacer(Modifier.height(FAB_CLEARANCE))
                    }
                    HomeTab.AUTOMATIONS -> {
                        if (alarmTriggers.isNotEmpty() && !setup.notificationAccessGranted) {
                            WarningBanner(
                                text = stringResource(R.string.alarm_access_off_banner),
                                primary = stringResource(R.string.setup_row_enable, stringResource(R.string.setup_notification_access)) to
                                    setupActions.onGrantNotificationAccess,
                            )
                        }
                        alarmProblems.forEach { problem -> AlarmProblemBanner(problem, alarmProblemActions) }
                        SchedulesCard(schedules, profiles, onEditSchedule, reorderActions.onReorderSchedules)
                        AlarmTriggersCard(alarmTriggers, profiles, appLabel, onEditAlarmTrigger, reorderActions.onReorderTriggers)
                        LocationsCard(locations, profiles, onEditLocation, reorderActions.onReorderLocations)
                    }
                    HomeTab.SETTINGS -> {
                        SetupChecklist(setup, setupActions)
                        LockDefaultsCard(onOpenLockDefaults)
                        LengthPresetsCard(presetsSec, onPresetsChange)
                        AlarmDayStartCard(alarmDayStartMinutes, onAlarmDayStartChange)
                        LocationEngineCard(locationEngine, playServicesAvailable, onLocationEngineChange)
                        IntentApiCard(intentApi, intentApiActions)
                        DeviceCard(deviceProfileName, onChangeDevice)
                        AppearanceCard(themeMode, onThemeModeChange, showPickerIcons, onShowPickerIconsChange)
                        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.diag_open))
                        }
                    }
                }
            }
            if (selectedTab == HomeTab.PROFILES) {
                FloatingActionButton(
                    onClick = { onEditProfile(null) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.profile_new))
                }
            }
        }
        // Tabs at the bottom, within thumb reach.
        NavigationBar(windowInsets = WindowInsets(0)) { // the activity's Scaffold pads the gesture bar
            HomeTab.entries.forEach { tab ->
                val label = stringResource(tab.labelRes())
                NavigationBarItem(
                    selected = tab == selectedTab,
                    onClick = { onTabChange(tab) },
                    icon = { Icon(tab.icon(), contentDescription = null) },
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

private fun HomeTab.labelRes(): Int = when (this) {
    HomeTab.QUICK_LOCK -> R.string.tab_quick_lock
    HomeTab.PROFILES -> R.string.tab_profiles
    HomeTab.AUTOMATIONS -> R.string.tab_automations
    HomeTab.SETTINGS -> R.string.tab_settings
}

private fun HomeTab.icon(): ImageVector = when (this) {
    HomeTab.QUICK_LOCK -> Icons.Filled.Bolt
    HomeTab.PROFILES -> Icons.AutoMirrored.Filled.List
    HomeTab.AUTOMATIONS -> Icons.Filled.Schedule
    HomeTab.SETTINGS -> Icons.Filled.Settings
}

/** Location triggers, with icons and drag to reorder. */
@Composable
private fun LocationsCard(
    locations: List<LocationTrigger>,
    profiles: List<LockProfile>,
    onEdit: (LocationTrigger?) -> Unit,
    onReorder: (List<LocationTrigger>) -> Unit,
) {
    val context = LocalContext.current
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.locations_title), style = MaterialTheme.typography.titleMedium)
            if (locations.isEmpty()) {
                Text(stringResource(R.string.locations_empty), style = MaterialTheme.typography.bodySmall)
            }
            ReorderableColumn(locations, key = { it.id }, onReorder = onReorder) { location, _ ->
                val profile = location.profileId?.let { profilesById[it] }
                ListRow(
                    title = location.name,
                    subtitle = formatLocationTrigger(context, location, profile?.name),
                    onClick = { onEdit(location) },
                    leading = { ProfileIcon(automationIcon(location.icon, profile), location.name) },
                )
            }
            OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.location_new))
            }
        }
    }
}

/** Settings → Location engine: Play Services geofencing (default) or Android's own location. */
@Composable
private fun LocationEngineCard(
    choice: LocationEngineChoice,
    playServicesAvailable: Boolean,
    onChange: (LocationEngineChoice) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SettingsRow(
                icon = Icons.Filled.MyLocation,
                title = stringResource(R.string.location_engine_title),
                summary = stringResource(locationEngineLabel(choice)) +
                    if (choice == LocationEngineChoice.PLAY_SERVICES && !playServicesAvailable) {
                        " · " + stringResource(R.string.location_engine_unavailable)
                    } else {
                        ""
                    },
                onClick = { picking = true },
            )
        }
    }
    if (picking) {
        ChoiceDialog(
            title = stringResource(R.string.location_engine_title),
            options = LocationEngineChoice.entries,
            selected = choice,
            label = { stringResource(locationEngineLabel(it)) },
            description = { stringResource(locationEngineDescription(it)) },
            onSelect = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

private fun locationEngineLabel(choice: LocationEngineChoice): Int = when (choice) {
    LocationEngineChoice.PLAY_SERVICES -> R.string.location_engine_play
    LocationEngineChoice.BUILT_IN -> R.string.location_engine_builtin
}

private fun locationEngineDescription(choice: LocationEngineChoice): Int = when (choice) {
    LocationEngineChoice.PLAY_SERVICES -> R.string.location_engine_play_desc
    LocationEngineChoice.BUILT_IN -> R.string.location_engine_builtin_desc
}

/** Saving a new list order after a drag. Each gets the items top to bottom. */
class ReorderActions(
    val onReorderProfiles: (List<LockProfile>) -> Unit,
    val onReorderSchedules: (List<LockSchedule>) -> Unit,
    val onReorderTriggers: (List<AlarmTrigger>) -> Unit,
    val onReorderLocations: (List<LocationTrigger>) -> Unit,
)

/** Space a FloatingActionButton (56 dp + 16 dp margin) needs at the end of a scrolling list. */
private val FAB_CLEARANCE = 72.dp

/** One alarm trigger that may need re-linking. */
data class AlarmProblemUi(
    val triggerId: Long,
    val triggerName: String,
    val reason: String,
    /** "Re-link to internal alarm #2 (5:00 PM)", if a replacement was spotted. */
    val relinkLabel: String?,
)

class AlarmProblemActions(
    val onRelink: (triggerId: Long) -> Unit,
    val onOpen: (triggerId: Long) -> Unit,
    val onDismiss: (triggerId: Long) -> Unit,
)

@Composable
private fun AlarmProblemBanner(problem: AlarmProblemUi, actions: AlarmProblemActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.trigger_warning_title, problem.triggerName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(problem.reason, color = MaterialTheme.colorScheme.onErrorContainer)
            problem.relinkLabel?.let { label ->
                Button(onClick = { actions.onRelink(problem.triggerId) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onOpen(problem.triggerId) }) { Text(stringResource(R.string.trigger_warning_open)) }
                TextButton(onClick = { actions.onDismiss(problem.triggerId) }) { Text(stringResource(R.string.trigger_warning_fine)) }
            }
        }
    }
}

/** An error-coloured message with one action. */
@Composable
private fun WarningBanner(text: String, primary: Pair<String, () -> Unit>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = primary.second) { Text(primary.first) }
        }
    }
}

/** Shown on the Locks tab while something essential is missing. */
@Composable
private fun SetupBanner(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_banner), color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.setup_banner_button)) }
        }
    }
}

/** Alarms that start a lock once dismissed. */
@Composable
private fun AlarmTriggersCard(
    triggers: List<AlarmTrigger>,
    profiles: List<LockProfile>,
    appLabel: (String) -> String,
    onEdit: (AlarmTrigger?) -> Unit,
    onReorder: (List<AlarmTrigger>) -> Unit,
) {
    val context = LocalContext.current
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.alarm_triggers_title), style = MaterialTheme.typography.titleMedium)
            if (triggers.isEmpty()) {
                Text(stringResource(R.string.alarm_triggers_empty), style = MaterialTheme.typography.bodySmall)
            }
            ReorderableColumn(triggers, key = { it.id }, onReorder = onReorder) { trigger, _ ->
                val profile = trigger.profileId?.let { profilesById[it] }
                ListRow(
                    title = trigger.name,
                    subtitle = formatAlarmTrigger(context, trigger, appLabel, profile?.name),
                    onClick = { onEdit(trigger) },
                    leading = { ProfileIcon(automationIcon(trigger.icon, profile), trigger.name) },
                )
            }
            OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alarm_trigger_new))
            }
        }
    }
}

data class SetupStatus(
    val adminActive: Boolean,
    val notificationsGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val exactAlarmsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val dndAccessGranted: Boolean,
    /** True if Quick lock or any profile has DND on, so the access row matters. */
    val dndWanted: Boolean,
    val notificationAccessGranted: Boolean,
    /** True if any alarm trigger exists, so the notification-access row matters. */
    val alarmTriggersExist: Boolean,
    /** True if any location trigger exists, so the location rows matter. */
    val locationTriggersExist: Boolean = false,
    val preciseLocationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val locationServicesOn: Boolean = false,
)

/** What each "Enable" button in the setup checklist does. */
class SetupActions(
    val onEnableAdmin: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onEnableAccessibility: () -> Unit,
    val onAllowExactAlarms: () -> Unit,
    val onAllowBackground: () -> Unit,
    val onGrantDnd: () -> Unit,
    val onGrantNotificationAccess: () -> Unit,
    val onGrantLocation: () -> Unit,
    val onGrantBackgroundLocation: () -> Unit,
    val onEnableLocationServices: () -> Unit,
)

/** The permissions checklist. Shared by the home screen and onboarding. */
@Composable
fun SetupChecklist(setup: SetupStatus, actions: SetupActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            SetupRow(stringResource(R.string.setup_admin), setup.adminActive, actions.onEnableAdmin)
            SetupRow(stringResource(R.string.setup_notifications), setup.notificationsGranted, actions.onRequestNotifications)
            SetupRow(stringResource(R.string.setup_accessibility), setup.accessibilityEnabled, actions.onEnableAccessibility)
            SetupRow(stringResource(R.string.setup_background), setup.batteryUnrestricted, actions.onAllowBackground)
            if (setup.locationTriggersExist) {
                SetupRow(stringResource(R.string.setup_location), setup.preciseLocationGranted, actions.onGrantLocation)
                SetupRow(stringResource(R.string.setup_background_location), setup.backgroundLocationGranted, actions.onGrantBackgroundLocation)
                SetupRow(stringResource(R.string.setup_location_services), setup.locationServicesOn, actions.onEnableLocationServices)
            }
            if (setup.alarmTriggersExist) {
                SetupRow(stringResource(R.string.setup_notification_access), setup.notificationAccessGranted, actions.onGrantNotificationAccess)
            }
            if (setup.dndWanted) {
                SetupRow(stringResource(R.string.setup_dnd), setup.dndAccessGranted, actions.onGrantDnd)
            }
            // Normally granted automatically; shown only if the user revoked it.
            if (!setup.exactAlarmsAllowed) {
                SetupRow(stringResource(R.string.setup_exact_alarms), false, actions.onAllowExactAlarms)
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onFix: () -> Unit) {
    if (done) {
        Text(stringResource(R.string.setup_row_done, label))
    } else {
        Button(onClick = onFix, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_row_enable, label))
        }
    }
}

/** Theme (System / Light / Dark / OLED black) and icons in the lock-screen app picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onChange: (ThemeMode) -> Unit,
    showPickerIcons: Boolean,
    onShowPickerIconsChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleMedium)
            val modes = ThemeMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) {
                        Text(
                            stringResource(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                    ThemeMode.OLED -> R.string.theme_oled
                                }
                            )
                        )
                    }
                }
            }
            SwitchRow(stringResource(R.string.show_app_icons), showPickerIcons, onShowPickerIconsChange)
            Text(stringResource(R.string.show_app_icons_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeviceCard(profileName: String, onChange: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.device_title), style = MaterialTheme.typography.titleMedium)
            Text(profileName)
            OutlinedButton(onClick = onChange, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.device_change))
            }
        }
    }
}

/** Saved profiles: tap a row to edit it, ▶ to start a lock with it. */
@Composable
private fun ProfilesCard(
    profiles: List<LockProfile>,
    accessibilityEnabled: Boolean,
    adminActive: Boolean,
    onEdit: (LockProfile?) -> Unit,
    onStart: (LockProfile, Long) -> Unit,
    onReorder: (List<LockProfile>) -> Unit,
    presetsSec: List<Long>,
) {
    val context = LocalContext.current
    var starting by remember { mutableStateOf<LockProfile?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profiles_title), style = MaterialTheme.typography.titleMedium)
            if (!accessibilityEnabled) {
                Text(stringResource(R.string.allowed_needs_accessibility), style = MaterialTheme.typography.bodySmall)
            }
            if (profiles.size > 1) {
                Text(stringResource(R.string.reorder_hint), style = MaterialTheme.typography.bodySmall)
            }
            ReorderableColumn(profiles, key = { it.id }, onReorder = onReorder) { profile, _ ->
                ListRow(
                    title = profile.name,
                    subtitle = formatProfileSummary(context, profile),
                    onClick = { onEdit(profile) },
                    leading = { ProfileIcon(profile.icon, profile.name) },
                    trailing = {
                        FilledTonalIconButton(
                            onClick = { starting = profile },
                            enabled = adminActive,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(R.string.profile_start_named, profile.name),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    },
                )
            }
        }
    }
    starting?.let { profile ->
        StartProfileDialog(
            profile = profile,
            presetsSec = presetsSec,
            onStart = { durationMs ->
                starting = null
                onStart(profile, durationMs)
            },
            onDismiss = { starting = null },
        )
    }
}

@Composable
private fun SchedulesCard(
    schedules: List<LockSchedule>,
    profiles: List<LockProfile>,
    onEdit: (LockSchedule?) -> Unit,
    onReorder: (List<LockSchedule>) -> Unit,
) {
    val context = LocalContext.current
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.schedules_title), style = MaterialTheme.typography.titleMedium)
            if (schedules.isEmpty()) {
                Text(stringResource(R.string.schedules_empty), style = MaterialTheme.typography.bodySmall)
            }
            ReorderableColumn(schedules, key = { it.id }, onReorder = onReorder) { schedule, _ ->
                val profile = profilesById[schedule.profileId]
                ListRow(
                    title = formatSchedule(context, schedule, profile?.name),
                    subtitle = null,
                    onClick = { onEdit(schedule) },
                    leading = { ProfileIcon(automationIcon(schedule.icon, profile), profile?.name.orEmpty()) },
                )
            }
            OutlinedButton(
                onClick = { onEdit(null) },
                enabled = profiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.schedule_new))
            }
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
    HorizontalDivider()
}

/** Settings → Lock defaults: the baseline for new profiles and Quick lock's "Reset to defaults". */
@Composable
private fun LockDefaultsCard(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lock_defaults_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.lock_defaults_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.lock_defaults_open))
            }
        }
    }
}

/** What the Quick lock tab and the profile Start buttons can do. */
class StartLockActions(
    val onEditQuickApps: () -> Unit,
    /** Saves Quick lock's settings as they're changed, so they're prefilled next time. */
    val onQuickSettingsChange: (LockSettings) -> Unit,
    /** Copies the Lock defaults into Quick lock. */
    val onResetQuickToDefaults: () -> Unit,
    /** Starts a Quick lock with the saved settings. */
    val onStartQuick: () -> Unit,
    val onStartProfile: (profile: LockProfile, durationMs: Long) -> Unit,
)

/**
 * Quick lock: set everything right here (it's all remembered) and lock. ↻ copies the Lock
 * defaults back in.
 */
@Composable
private fun QuickLockSection(enabled: Boolean, quickLock: LockSettings, presetsSec: List<Long>, actions: StartLockActions) {
    var showConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.quick_lock), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { showResetConfirm = true }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.quick_reset_defaults))
            }
        }
        LockSettingsSections(
            settings = quickLock,
            presetsSec = presetsSec,
            lengthTitle = stringResource(R.string.lock_length),
            onChange = actions.onQuickSettingsChange,
            onEditAllowedApps = actions.onEditQuickApps,
        )
        Button(onClick = { showConfirm = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start_button))
        }
        if (!enabled) {
            Text(stringResource(R.string.start_needs_admin), style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showConfirm) {
        ConfirmLockDialog(
            durationMs = quickLock.durationSec * 1_000,
            bypassWaitMinutes = quickLock.bypassWaitMinutes.takeIf { quickLock.bypassEnabled },
            onConfirm = {
                showConfirm = false
                actions.onStartQuick()
            },
            onDismiss = { showConfirm = false },
        )
    }
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.quick_reset_confirm_title)) },
            text = { Text(stringResource(R.string.quick_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    actions.onResetQuickToDefaults()
                }) { Text(stringResource(R.string.quick_reset_defaults)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** Settings → Length presets: 0 to [MAX_DURATION_PRESETS] one-tap lock lengths. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LengthPresetsCard(presetsSec: List<Long>, onChange: (List<Long>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.presets_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.presets_hint), style = MaterialTheme.typography.bodySmall)
            if (presetsSec.isEmpty()) {
                Text(stringResource(R.string.presets_none), style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presetsSec.forEach { seconds ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(presetsSec - seconds) },
                        label = { Text(formatPresetLabel(seconds)) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove), modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
            OutlinedButton(
                onClick = { adding = true },
                enabled = presetsSec.size < MAX_DURATION_PRESETS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.presets_add))
            }
        }
    }
    if (adding) {
        DurationDialog(
            title = stringResource(R.string.presets_add),
            initialMs = 30L * 60 * 1_000,
            presetsSec = emptyList(),
            onConfirm = {
                onChange(presetsSec + it / 1_000)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

/** Settings → Alarm triggers: when a "day" starts for first-alarm and day rules. */
@Composable
private fun AlarmDayStartCard(dayStartMinutes: Int, onChange: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.alarm_triggers_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRow(
                icon = Icons.Filled.WbTwilight,
                title = stringResource(R.string.day_start_title),
                summary = formatMinuteOfDay(dayStartMinutes),
                onClick = { picking = true },
            )
            Text(
                stringResource(R.string.day_start_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (picking) {
        TimePickerDialog(
            initialMinuteOfDay = dayStartMinutes,
            onConfirm = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** A label with a switch at the end. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** ▶ Start from a profile: pick the length (prefilled with the profile's default), then lock. */
@Composable
private fun StartProfileDialog(
    profile: LockProfile,
    presetsSec: List<Long>,
    onStart: (durationMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val duration = rememberDurationInputState(profile.defaultDurationSec * 1_000, key = profile.id)
    val durationMs = duration.durationMs
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_start_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatProfileSummary(LocalContext.current, profile), style = MaterialTheme.typography.bodySmall)
                DurationPresetChips(presetsSec, durationMs, onPick = { ms ->
                    val seconds = ms / 1_000
                    duration.hoursText = (seconds / 3600).toString()
                    duration.minutesText = ((seconds % 3600) / 60).toString()
                    duration.secondsText = (seconds % 60).toString()
                })
                DurationInput(duration)
                Text(stringResource(R.string.start_confirm_no_end_early), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { durationMs?.let(onStart) }, enabled = durationMs != null) {
                Text(stringResource(R.string.start_confirm_yes))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** @param bypassWaitMinutes the early-unlock wait, or null if early unlock is off. */
@Composable
private fun ConfirmLockDialog(
    durationMs: Long,
    bypassWaitMinutes: Int?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.start_confirm_title)) },
        text = {
            Text(
                if (bypassWaitMinutes != null) {
                    stringResource(R.string.start_confirm_body_bypass, formatDurationLabel(durationMs), bypassWaitMinutes)
                } else {
                    stringResource(R.string.start_confirm_body, formatDurationLabel(durationMs))
                }
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.start_confirm_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

private const val MAX_BYPASS_MINUTES = 24 * 60

/** A button showing the chosen profile; tapping it opens the list. */
@Composable
fun ProfileDropdown(profiles: List<LockProfile>, selected: LockProfile, onSelect: (LockProfile) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.profile_picker, selected.name))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        expanded = false
                        onSelect(profile)
                    },
                )
            }
        }
    }
}

/** What the intent API card shows. */
data class IntentApiUiState(val stopEnabled: Boolean, val token: String, val packageName: String)

class IntentApiActions(
    val onStopEnabledChange: (Boolean) -> Unit,
    val onCopy: (String) -> Unit,
    val onRegenerateToken: () -> Unit,
)

/** How to trigger locks from MacroDroid / Tasker, and the stop token. */
@Composable
private fun IntentApiCard(state: IntentApiUiState, actions: IntentApiActions) {
    var confirmRegenerate by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.intent_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.intent_body), style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    intentReference(state.packageName),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedButton(onClick = { actions.onCopy(IntentApiReceiver.ACTION_START_LOCK) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.intent_copy_start))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.intent_stop_switch), modifier = Modifier.weight(1f))
                Switch(checked = state.stopEnabled, onCheckedChange = actions.onStopEnabledChange)
            }
            if (state.stopEnabled) {
                Text(stringResource(R.string.intent_token_label), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(state.token, fontFamily = FontFamily.Monospace)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.onCopy(state.token) }) { Text(stringResource(R.string.intent_copy_token)) }
                    OutlinedButton(onClick = { confirmRegenerate = true }) { Text(stringResource(R.string.intent_regenerate)) }
                }
                Text(stringResource(R.string.intent_stop_hint), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.intent_regenerate_title)) },
            text = { Text(stringResource(R.string.intent_regenerate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    actions.onRegenerateToken()
                }) { Text(stringResource(R.string.intent_regenerate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

private fun intentReference(packageName: String): String = buildString {
    appendLine("Package: $packageName")
    appendLine("Target:  Broadcast")
    appendLine("Start:   ${IntentApiReceiver.ACTION_START_LOCK}")
    appendLine("  extras (optional): ${IntentApiReceiver.EXTRA_PROFILE}, ${IntentApiReceiver.EXTRA_DURATION_MIN} / ${IntentApiReceiver.EXTRA_DURATION_SEC}")
    appendLine("Stop:    ${IntentApiReceiver.ACTION_STOP_LOCK}")
    append("  extra: ${IntentApiReceiver.EXTRA_TOKEN}")
}
