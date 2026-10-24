package com.fabiantorrestech.lockmyphone_extended.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Timer
import com.fabiantorrestech.lockmyphone_extended.core.diag.RelockEntry
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.InputChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.fabiantorrestech.lockmyphone_extended.data.MAX_DURATION_PRESETS
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcLinkMode
import androidx.compose.ui.draw.alpha
import com.fabiantorrestech.lockmyphone_extended.core.lock.HomeAction
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.EventBusy
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationEngineChoice
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
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
import com.fabiantorrestech.lockmyphone_extended.core.perm.AppPermission
import com.fabiantorrestech.lockmyphone_extended.core.perm.PermissionNeeds
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode
import com.fabiantorrestech.lockmyphone_extended.data.BypassDefaults
import com.fabiantorrestech.lockmyphone_extended.data.ThemeMode
import com.fabiantorrestech.lockmyphone_extended.data.UiMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.trigger.IntentApiReceiver
import com.fabiantorrestech.lockmyphone_extended.trigger.AutomationKey
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Speed
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconPreset
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconTiming
import kotlinx.coroutines.delay

/** The home screen's tabs, in order. */
enum class HomeTab { LOCK, AUTOMATIONS, SETTINGS }

/**
 * The main screen while no lock is active: the app title, then three tabs:
 * - **Lock:** Quick lock and your profiles, each ready to start.
 * - **Automations:** schedules, Morning lock, alarms, places, calendar and NFC tags.
 * - **Settings:** Simple / Pro, setup, lock defaults, appearance; Pro adds every fine-grained control.
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
    cooldowns: Map<AutomationKey, Long>,
    automationCooldown: Boolean,
    onAutomationCooldownChange: (Boolean) -> Unit,
    toggleActions: ToggleActions,
    appLabel: (packageName: String) -> String,
    onEditProfile: (LockProfile?) -> Unit,
    onEditSchedule: (LockSchedule?) -> Unit,
    onEditAlarmTrigger: (AlarmTrigger?) -> Unit,
    locations: List<LocationTrigger>,
    onEditLocation: (LocationTrigger?) -> Unit,
    calendarTriggers: List<CalendarTrigger>,
    onEditCalendar: (CalendarTrigger?) -> Unit,
    clashes: ClashWarnings,
    nfcTags: List<NfcTag>,
    onEditNfcTag: (NfcTag?) -> Unit,
    wakeTriggers: List<WakeTrigger>,
    onEditWake: (WakeTrigger?) -> Unit,
    nfcGateSeconds: Int,
    onNfcGateSecondsChange: (Int) -> Unit,
    calendarSkipDeclined: Boolean,
    calendarSkipFree: Boolean,
    onCalendarDefaultsChange: (skipDeclined: Boolean, skipFree: Boolean) -> Unit,
    backup: BackupUiState,
    backupActions: BackupActions,
    beaconMode: BeaconModeUiState,
    onBeaconModeChange: (enabled: Boolean, startMinutes: Int?, endMinutes: Int?) -> Unit,
    locationEngine: LocationEngineChoice,
    playServicesAvailable: Boolean,
    onLocationEngineChange: (LocationEngineChoice) -> Unit,
    beaconPreset: BeaconPreset,
    beaconCustomTiming: BeaconTiming,
    onBeaconTimingChange: (BeaconPreset, BeaconTiming) -> Unit,
    reorderActions: ReorderActions,
    alarmProblems: List<AlarmProblemUi>,
    alarmProblemActions: AlarmProblemActions,
    intentApi: IntentApiUiState,
    intentApiActions: IntentApiActions,
    onOpenDiagnostics: () -> Unit,
    onOpenLockDefaults: () -> Unit,
    onOpenPermissions: () -> Unit,
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
    pickerAppearance: PickerAppearance,
    assistantPopupAllowed: Boolean,
    onAssistantPopupChange: (Boolean) -> Unit,
    homeAction: HomeAction,
    onHomeActionChange: (HomeAction) -> Unit,
    unlockIntoAllowedApp: Boolean,
    onUnlockIntoAllowedAppChange: (Boolean) -> Unit,
    uiMode: UiMode,
    onUiModeChange: (UiMode) -> Unit,
    onOpenAssistant: () -> Unit,
    popupAppCount: Int,
    onEditPopupApps: () -> Unit,
    relocks: RelockUi,
    timeLeft: TimeLeftUi,
    /** Morning locks that can offer "Going back to sleep" now. */
    backToSleepIds: Set<Long>,
    onGoBackToSleep: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pro = uiMode == UiMode.PRO
    Column(modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge) },
            // The activity's Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        // What's allowed right now, so list rows can say what's paused.
        val granted = setup.grantedPermissions()
        // Each tab keeps its own scroll position while you switch between them.
        val scrollStates = HomeTab.entries.associateWith { rememberScrollState() }
        // A tab stays composed once shown, so switching back to it is only a layout pass. Building a
        // tab from scratch took 2-3 frames on a Pixel 5. The others are built one per frame once the
        // first screen is up, so even the first switch is quick.
        val composedTabs = remember { mutableStateListOf(selectedTab) }
        if (selectedTab !in composedTabs) composedTabs += selectedTab
        LaunchedEffect(Unit) {
            delay(PRECOMPOSE_DELAY_MS)
            HomeTab.entries.forEach { tab ->
                if (tab !in composedTabs) {
                    composedTabs += tab
                    withFrameNanos { }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HomeTab.entries.filter { it in composedTabs }.forEach { tab ->
                key(tab) {
                    Column(
                        modifier = Modifier
                            .shownOnlyIf(tab == selectedTab)
                            // Its own layer, so showing or hiding it doesn't redraw the rest of the screen.
                            .graphicsLayer()
                            .verticalScroll(scrollStates.getValue(tab))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (tab) {
                            HomeTab.LOCK -> LockTab(
                                profiles = profiles,
                                adminActive = setup.adminActive,
                                accessibilityEnabled = setup.accessibilityEnabled,
                                granted = granted,
                                quickLock = quickLock,
                                presetsSec = presetsSec,
                                onOpenSettings = { onTabChange(HomeTab.SETTINGS) },
                                onEditProfile = onEditProfile,
                                onReorderProfiles = reorderActions.onReorderProfiles,
                                actions = startActions,
                            )
                            HomeTab.AUTOMATIONS -> {
                                if (alarmTriggers.isNotEmpty() && !setup.notificationAccessGranted) {
                                    WarningBanner(
                                        text = stringResource(R.string.alarm_access_off_banner),
                                        primary = stringResource(R.string.setup_row_enable, stringResource(R.string.setup_notification_access)) to
                                            setupActions.onGrantNotificationAccess,
                                    )
                                }
                                alarmProblems.forEach { problem -> AlarmProblemBanner(problem, alarmProblemActions) }
                                // "Active in 0:45" on automations that changed less than a minute ago.
                                val waitText = rememberCooldownText(cooldowns)
                                // Morning lock's own sleep places are listed with it, not with the places.
                                val sleepOwners = remember(wakeTriggers) { sleepPlaceOwners(wakeTriggers) }
                                val (sleepPlaces, otherPlaces) = remember(locations, sleepOwners) {
                                    locations.partition { it.isMorningLockSleepPlace(sleepOwners) }
                                }
                                MorningLockCard(
                                    wakeTriggers, profiles, locations, onEditWake, granted,
                                    toggleActions.onToggleWake, { waitText(AutomationKey(LockKind.WAKE, it)) },
                                    sleepPlaces, onEditLocation, backToSleepIds, onGoBackToSleep,
                                )
                                SchedulesCard(
                                    schedules, profiles, onEditSchedule, reorderActions.onReorderSchedules, clashes.schedules,
                                    toggleActions.onToggleSchedule, { waitText(AutomationKey(LockKind.SCHEDULE, it)) },
                                )
                                AlarmTriggersCard(
                                    alarmTriggers, profiles, appLabel, onEditAlarmTrigger, reorderActions.onReorderTriggers, granted,
                                    toggleActions.onToggleAlarmTrigger, { waitText(AutomationKey(LockKind.ALARM, it)) },
                                )
                                LocationsCard(
                                    otherPlaces, profiles, onEditLocation, reorderActions.onReorderLocations, granted, clashes.places,
                                    toggleActions.onToggleLocation, { waitText(AutomationKey(LockKind.PLACE, it)) },
                                )
                                // Simple mode lists calendar triggers and NFC tags only once you have some.
                                if (pro || calendarTriggers.isNotEmpty()) {
                                    CalendarCard(
                                        calendarTriggers, profiles, onEditCalendar, reorderActions.onReorderCalendars, granted, clashes.calendars,
                                        toggleActions.onToggleCalendar, { waitText(AutomationKey(LockKind.CALENDAR, it)) },
                                    )
                                }
                                if (pro || nfcTags.isNotEmpty()) {
                                    NfcTagsCard(
                                        nfcTags, profiles, setup.hasNfc, onEditNfcTag, reorderActions.onReorderNfcTags, granted,
                                        toggleActions.onToggleNfcTag, { waitText(AutomationKey(LockKind.NFC, it)) },
                                    )
                                }
                                if (!pro && (calendarTriggers.isEmpty() || (nfcTags.isEmpty() && setup.hasNfc))) {
                                    MoreKindsCard(
                                        showCalendar = calendarTriggers.isEmpty(),
                                        showNfc = nfcTags.isEmpty() && setup.hasNfc,
                                        onNewCalendar = { onEditCalendar(null) },
                                        onNewNfcTag = { onEditNfcTag(null) },
                                    )
                                }
                            }
                            HomeTab.SETTINGS -> {
                                ModeCard(uiMode, onUiModeChange)

                                SettingsGroupHeader(stringResource(R.string.settings_group_setup))
                                SetupChecklist(setup, setupActions)
                                AssistantCard(onOpenAssistant)
                                PermissionsCard(onOpenPermissions)
                                BackupCard(backup, backupActions)

                                SettingsGroupHeader(stringResource(R.string.settings_group_locks))
                                LockDefaultsCard(onOpenLockDefaults)
                                DuringLockCard(
                                    assistantPopupAllowed,
                                    onAssistantPopupChange,
                                    homeAction,
                                    onHomeActionChange,
                                    unlockIntoAllowedApp,
                                    onUnlockIntoAllowedAppChange,
                                    popupAppCount = popupAppCount.takeIf { pro },
                                    onEditPopupApps = onEditPopupApps,
                                    relocks = relocks,
                                    appLabel = appLabel,
                                    timeLeft = timeLeft,
                                )
                                if (pro) LengthPresetsCard(presetsSec, onPresetsChange)

                                SettingsGroupHeader(stringResource(R.string.settings_group_look))
                                AppearanceCard(themeMode, onThemeModeChange, showPickerIcons, onShowPickerIconsChange, pickerAppearance)

                                if (pro) {
                                    SettingsGroupHeader(stringResource(R.string.tab_automations))
                                    AutomationCooldownCard(automationCooldown, onAutomationCooldownChange)
                                    AlarmDayStartCard(alarmDayStartMinutes, onAlarmDayStartChange)
                                    LocationEngineCard(locationEngine, playServicesAvailable, onLocationEngineChange)
                                    BeaconTimingCard(beaconPreset, beaconCustomTiming, onBeaconTimingChange)
                                    CalendarDefaultsCard(calendarSkipDeclined, calendarSkipFree, onCalendarDefaultsChange)
                                    if (setup.hasNfc) NfcGateCard(nfcGateSeconds, onNfcGateSecondsChange)

                                    SettingsGroupHeader(stringResource(R.string.settings_group_advanced))
                                    BeaconModeCard(beaconMode, onBeaconModeChange, setupActions.onAllowBackground)
                                    IntentApiCard(intentApi, intentApiActions)
                                    DeviceCard(deviceProfileName, onChangeDevice)
                                    OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(R.string.diag_open))
                                    }
                                } else {
                                    Text(
                                        stringResource(R.string.mode_more_in_pro),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                }
                            }
                        }
                    }
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

/** How long after the first screen the other tabs start being built in the background. */
private const val PRECOMPOSE_DELAY_MS = 600L

/**
 * A hidden tab isn't placed, so it isn't drawn, can't be touched and isn't read by TalkBack. It
 * stays composed and measured (both cached), so showing it again costs almost nothing.
 */
private fun Modifier.shownOnlyIf(visible: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    if (visible) {
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    } else {
        layout(0, 0) {}
    }
}

private fun HomeTab.labelRes(): Int = when (this) {
    HomeTab.LOCK -> R.string.tab_lock
    HomeTab.AUTOMATIONS -> R.string.tab_automations
    HomeTab.SETTINGS -> R.string.tab_settings
}

private fun HomeTab.icon(): ImageVector = when (this) {
    HomeTab.LOCK -> Icons.Filled.Lock
    HomeTab.AUTOMATIONS -> Icons.Filled.Schedule
    HomeTab.SETTINGS -> Icons.Filled.Settings
}

/** "Why did it lock?": the recent relocks, and allowing a popup app from one of them. */
class RelockUi(
    val entries: List<RelockEntry>,
    val popupPackages: Set<String>,
    val onAllowPopups: (String) -> Unit,
    val onClear: () -> Unit,
    /** Whether popups from this app can be allowed (this app must be able to see it). */
    val canAllowPopups: (String) -> Boolean,
)

/** The time-left clock's settings (During a lock). */
class TimeLeftUi(
    val show: Boolean,
    val onShowChange: (Boolean) -> Unit,
    val large: Boolean,
    val onLargeChange: (Boolean) -> Unit,
    val onResetPosition: () -> Unit,
)

/** Simple or Pro: the first card in Settings, in both modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(mode: UiMode, onChange: (UiMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                UiMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = { onChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = UiMode.entries.size),
                    ) {
                        Text(stringResource(if (option == UiMode.SIMPLE) R.string.mode_simple else R.string.mode_pro))
                    }
                }
            }
            Text(
                stringResource(if (mode == UiMode.SIMPLE) R.string.mode_simple_hint else R.string.mode_pro_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A group label between Settings cards. */
@Composable
private fun SettingsGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** Settings → Set-up assistant: run it again to add starter profiles. */
@Composable
private fun AssistantCard(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        SettingsRow(
            icon = Icons.Filled.AutoAwesome,
            title = stringResource(R.string.assistant_card_title),
            summary = stringResource(R.string.assistant_card_hint),
            onClick = onOpen,
        )
    }
}

/** Simple mode: the automation kinds not shown yet, one tap to add one. */
@Composable
private fun MoreKindsCard(showCalendar: Boolean, showNfc: Boolean, onNewCalendar: () -> Unit, onNewNfcTag: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.more_kinds_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.more_kinds_hint), style = MaterialTheme.typography.bodySmall)
            if (showCalendar) {
                OutlinedButton(onClick = onNewCalendar, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.calendar_new))
                }
            }
            if (showNfc) {
                OutlinedButton(onClick = onNewNfcTag, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.nfc_tag_new))
                }
            }
        }
    }
}

/** Location triggers, with icons and drag to reorder. */
@Composable
private fun LocationsCard(
    locations: List<LocationTrigger>,
    profiles: List<LockProfile>,
    onEdit: (LocationTrigger?) -> Unit,
    onReorder: (List<LocationTrigger>) -> Unit,
    granted: Set<AppPermission>,
    clashes: Map<Long, String>,
    onToggle: (LocationTrigger, Boolean) -> Unit,
    waitText: (Long) -> String?,
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
                    subtitle = listOfNotNull(
                        withPauseNote(
                            formatLocationTrigger(context, location, profile?.name),
                            pauseNote(context, PermissionNeeds.checkPlace(location, granted)),
                        ),
                        clashes[location.id],
                        waitText(location.id),
                    ).joinToString("\n"),
                    onClick = { onEdit(location) },
                    leading = { ProfileIcon(automationIcon(location.icon, profile), location.name) },
                    trailing = { Switch(checked = location.enabled, onCheckedChange = { onToggle(location, it) }) },
                    dimmed = !location.enabled,
                )
            }
            OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.location_new))
            }
        }
    }
}

/** NFC tags, with icons and drag to reorder. */
@Composable
private fun NfcTagsCard(
    tags: List<NfcTag>,
    profiles: List<LockProfile>,
    hasNfc: Boolean,
    onEdit: (NfcTag?) -> Unit,
    onReorder: (List<NfcTag>) -> Unit,
    granted: Set<AppPermission>,
    onToggle: (NfcTag, Boolean) -> Unit,
    waitText: (Long) -> String?,
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.nfc_tags_title), style = MaterialTheme.typography.titleMedium)
            when {
                !hasNfc -> Text(stringResource(R.string.nfc_no_hardware), style = MaterialTheme.typography.bodySmall)
                tags.isEmpty() -> Text(stringResource(R.string.nfc_tags_empty), style = MaterialTheme.typography.bodySmall)
            }
            ReorderableColumn(tags, key = { it.id }, onReorder = onReorder) { tag, _ ->
                // With one linked profile, the row shows its icon unless the tag has its own.
                val onlyProfile = profiles.firstOrNull { tag.linkMode == NfcLinkMode.ONE && tag.links(it.id.toString()) }
                ListRow(
                    title = tag.name,
                    subtitle = listOfNotNull(
                        withPauseNote(
                            formatNfcTag(context, tag, profiles),
                            pauseNote(context, PermissionNeeds.check(PermissionNeeds.forNfcTag, granted)),
                        ),
                        waitText(tag.id),
                    ).joinToString("\n"),
                    onClick = { onEdit(tag) },
                    leading = { ProfileIcon(automationIcon(tag.icon, onlyProfile), tag.name) },
                    trailing = { Switch(checked = tag.enabled, onCheckedChange = { onToggle(tag, it) }) },
                    // Dimmed while it's off, or until it's written to the tag.
                    dimmed = !tag.enabled || !tag.written,
                )
            }
            OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth(), enabled = hasNfc) {
                Text(stringResource(R.string.nfc_tag_new))
            }
        }
    }
}

/** Settings → NFC tags: how long the countdown screen waits for a tag after an unlock. */
@Composable
private fun NfcGateCard(gateSeconds: Int, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.nfc_tags_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Nfc,
                title = stringResource(R.string.nfc_gate_time),
                summary = stringResource(R.string.seconds_value, gateSeconds),
                onClick = { editing = true },
            )
            Text(
                stringResource(R.string.nfc_gate_time_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (editing) {
        NumberDialog(
            title = stringResource(R.string.nfc_gate_time),
            label = stringResource(R.string.start_seconds_label),
            initial = gateSeconds,
            range = NFC_GATE_SECONDS_RANGE,
            onConfirm = {
                onChange(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

private val NFC_GATE_SECONDS_RANGE = 5..30

/** The backup settings as shown. */
data class BackupUiState(
    val folderName: String?,
    /** Null = same as the backup folder. */
    val autoFolderName: String?,
    val autoBackup: Boolean,
    val lastBackupAtMs: Long?,
)

class BackupActions(
    val onPickFolder: () -> Unit,
    val onPickAutoFolder: () -> Unit,
    val onBackupNow: () -> Unit,
    val onRestore: () -> Unit,
    val onAutoBackupChange: (Boolean) -> Unit,
)

/** Settings → Backup: folder, back up now, restore, auto backup. */
@Composable
private fun BackupCard(state: BackupUiState, actions: BackupActions) {
    val hasFolder = state.folderName != null
    val hasAutoFolder = state.autoFolderName != null || hasFolder
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.backup_folder),
                summary = state.folderName ?: stringResource(R.string.backup_folder_none),
                onClick = actions.onPickFolder,
            )
            SettingsRow(
                icon = Icons.Filled.Backup,
                title = stringResource(R.string.backup_now),
                summary = state.lastBackupAtMs?.let {
                    stringResource(R.string.backup_last, formatDateTime(it))
                } ?: stringResource(R.string.backup_never),
                onClick = actions.onBackupNow,
                enabled = hasFolder,
            )
            SettingsRow(
                icon = Icons.Filled.Restore,
                title = stringResource(R.string.backup_restore),
                summary = stringResource(R.string.backup_restore_hint),
                onClick = actions.onRestore,
            )
            SwitchSettingsRow(
                icon = Icons.Filled.Autorenew,
                title = stringResource(R.string.backup_auto),
                summary = stringResource(if (hasAutoFolder) R.string.backup_auto_hint else R.string.backup_auto_needs_folder),
                checked = state.autoBackup && hasAutoFolder,
                onCheckedChange = actions.onAutoBackupChange,
                enabled = hasAutoFolder,
            )
            SettingsRow(
                icon = Icons.Filled.FolderSpecial,
                title = stringResource(R.string.backup_auto_folder),
                summary = state.autoFolderName ?: stringResource(R.string.backup_auto_folder_same),
                onClick = actions.onPickAutoFolder,
            )
        }
    }
}

/** "Sep 29, 2026, 10:30 AM" in the user's locale. */
private fun formatDateTime(timeMs: Long): String =
    java.time.Instant.ofEpochMilli(timeMs).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT))

/** "Act as a beacon" as shown. */
data class BeaconModeUiState(
    val enabled: Boolean,
    val startMinutes: Int?,
    val endMinutes: Int?,
    val key: String,
    val batteryUnrestricted: Boolean,
)

/** Settings → Act as a beacon: this device broadcasts a beacon (e.g. a tablet as a portable desk). */
@Composable
private fun BeaconModeCard(state: BeaconModeUiState, onChange: (Boolean, Int?, Int?) -> Unit, onAllowBackground: () -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    val hours = state.startMinutes != null && state.endMinutes != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.beacon_mode_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchSettingsRow(
                icon = Icons.Filled.BluetoothAudio,
                title = stringResource(R.string.beacon_mode_switch),
                summary = stringResource(R.string.beacon_mode_id, state.key),
                checked = state.enabled,
                onCheckedChange = { onChange(it, state.startMinutes, state.endMinutes) },
            )
            // It must keep running; Samsung, Nothing and others stop background apps without this.
            if (state.enabled && !state.batteryUnrestricted) {
                SettingsRow(
                    icon = Icons.Filled.BatteryAlert,
                    title = stringResource(R.string.beacon_mode_battery),
                    summary = stringResource(R.string.beacon_mode_battery_desc),
                    onClick = onAllowBackground,
                )
            }
            SwitchSettingsRow(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.beacon_mode_hours),
                summary = if (hours) {
                    "${formatMinuteOfDay(state.startMinutes!!)} → ${formatMinuteOfDay(state.endMinutes!!)}"
                } else {
                    stringResource(R.string.beacon_mode_always)
                },
                checked = hours,
                onCheckedChange = { on -> onChange(state.enabled, if (on) 9 * 60 else null, if (on) 17 * 60 else null) },
            )
            if (hours) {
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.schedule_start_title),
                    summary = formatMinuteOfDay(state.startMinutes!!),
                    onClick = { picking = true },
                )
                SettingsRow(
                    icon = null,
                    title = stringResource(R.string.schedule_end_title),
                    summary = formatMinuteOfDay(state.endMinutes!!),
                    onClick = { picking = false },
                )
            }
            Text(
                stringResource(R.string.beacon_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    picking?.let { start ->
        TimePickerDialog(
            initialMinuteOfDay = (if (start) state.startMinutes else state.endMinutes) ?: 0,
            onConfirm = { minutes ->
                if (start) onChange(state.enabled, minutes, state.endMinutes) else onChange(state.enabled, state.startMinutes, minutes)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** Morning lock (M22): usually one; set it up from here. */
@Composable
private fun MorningLockCard(
    triggers: List<WakeTrigger>,
    profiles: List<LockProfile>,
    places: List<LocationTrigger>,
    onEdit: (WakeTrigger?) -> Unit,
    granted: Set<AppPermission>,
    onToggle: (WakeTrigger, Boolean) -> Unit,
    waitText: (Long) -> String?,
    sleepPlaces: List<LocationTrigger>,
    onEditPlace: (LocationTrigger) -> Unit,
    backToSleepIds: Set<Long>,
    onGoBackToSleep: (Long) -> Unit,
) {
    val context = LocalContext.current
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wake_title), style = MaterialTheme.typography.titleMedium)
            if (triggers.isEmpty()) {
                Text(stringResource(R.string.wake_empty), style = MaterialTheme.typography.bodySmall)
            }
            triggers.forEach { trigger ->
                val profile = trigger.profileId?.let { profilesById[it] }
                ListRow(
                    title = trigger.name,
                    subtitle = listOfNotNull(
                        withPauseNote(
                            formatWakeTrigger(context, trigger, profile?.name, places),
                            pauseNote(context, PermissionNeeds.check(PermissionNeeds.forWakeTrigger, granted)),
                        ),
                        waitText(trigger.id),
                    ).joinToString("\n"),
                    onClick = { onEdit(trigger) },
                    leading = { ProfileIcon(trigger.icon ?: WAKE_ICON, trigger.name) },
                    trailing = { Switch(checked = trigger.enabled, onCheckedChange = { onToggle(trigger, it) }) },
                    dimmed = !trigger.enabled,
                )
                if (trigger.id in backToSleepIds) BackToSleepButton { onGoBackToSleep(trigger.id) }
            }
            if (sleepPlaces.isNotEmpty()) {
                Text(stringResource(R.string.wake_places), style = MaterialTheme.typography.titleSmall)
                sleepPlaces.forEach { place ->
                    ListRow(
                        title = place.name,
                        subtitle = formatLocationTrigger(context, place, null) + "\n" + stringResource(R.string.wake_sleep_place_note),
                        onClick = { onEditPlace(place) },
                        leading = { ProfileIcon(place.icon ?: SLEEP_PLACE_ICON, place.name) },
                    )
                }
            }
            if (triggers.isEmpty()) {
                OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wake_new))
                }
            }
        }
    }
}

/** Calendar triggers, with icons and drag to reorder. */
@Composable
private fun CalendarCard(
    triggers: List<CalendarTrigger>,
    profiles: List<LockProfile>,
    onEdit: (CalendarTrigger?) -> Unit,
    onReorder: (List<CalendarTrigger>) -> Unit,
    granted: Set<AppPermission>,
    clashes: Map<Long, String>,
    onToggle: (CalendarTrigger, Boolean) -> Unit,
    waitText: (Long) -> String?,
) {
    val context = LocalContext.current
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.calendar_title), style = MaterialTheme.typography.titleMedium)
            if (triggers.isEmpty()) {
                Text(stringResource(R.string.calendar_empty), style = MaterialTheme.typography.bodySmall)
            }
            ReorderableColumn(triggers, key = { it.id }, onReorder = onReorder) { trigger, _ ->
                val profile = trigger.profileId?.let { profilesById[it] }
                ListRow(
                    title = trigger.name,
                    subtitle = listOfNotNull(
                        withPauseNote(
                            formatCalendarTrigger(context, trigger, profile?.name),
                            pauseNote(context, PermissionNeeds.check(PermissionNeeds.forCalendarTrigger, granted)),
                        ),
                        clashes[trigger.id],
                        waitText(trigger.id),
                    ).joinToString("\n"),
                    onClick = { onEdit(trigger) },
                    leading = { ProfileIcon(automationIcon(trigger.icon, profile), trigger.name) },
                    trailing = { Switch(checked = trigger.enabled, onCheckedChange = { onToggle(trigger, it) }) },
                    dimmed = !trigger.enabled,
                )
            }
            OutlinedButton(onClick = { onEdit(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.calendar_new))
            }
        }
    }
}

/** Settings → Calendar: what new calendar triggers start with. */
@Composable
private fun CalendarDefaultsCard(skipDeclined: Boolean, skipFree: Boolean, onChange: (Boolean, Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.calendar_defaults_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchSettingsRow(
                icon = Icons.Filled.EventBusy,
                title = stringResource(R.string.calendar_skip_declined),
                summary = null,
                checked = skipDeclined,
                onCheckedChange = { onChange(it, skipFree) },
            )
            SwitchSettingsRow(
                icon = Icons.Filled.EventAvailable,
                title = stringResource(R.string.calendar_skip_free),
                summary = null,
                checked = skipFree,
                onCheckedChange = { onChange(skipDeclined, it) },
            )
            Text(
                stringResource(R.string.calendar_defaults_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
/**
 * Predicted clashes between automatic commitments, as ready-to-show warnings keyed by the
 * schedule, place or calendar trigger ID. Only one lock runs at a time (ADR 0005).
 */
data class ClashWarnings(
    val schedules: Map<Long, String> = emptyMap(),
    val places: Map<Long, String> = emptyMap(),
    val calendars: Map<Long, String> = emptyMap(),
)

class ReorderActions(
    val onReorderProfiles: (List<LockProfile>) -> Unit,
    val onReorderSchedules: (List<LockSchedule>) -> Unit,
    val onReorderTriggers: (List<AlarmTrigger>) -> Unit,
    val onReorderLocations: (List<LocationTrigger>) -> Unit,
    val onReorderCalendars: (List<CalendarTrigger>) -> Unit,
    val onReorderNfcTags: (List<NfcTag>) -> Unit,
)

/** Switching an automation on or off from its list row. Each gets the item and its new state. */
class ToggleActions(
    val onToggleSchedule: (LockSchedule, Boolean) -> Unit,
    val onToggleAlarmTrigger: (AlarmTrigger, Boolean) -> Unit,
    val onToggleLocation: (LocationTrigger, Boolean) -> Unit,
    val onToggleCalendar: (CalendarTrigger, Boolean) -> Unit,
    val onToggleNfcTag: (NfcTag, Boolean) -> Unit,
    val onToggleWake: (WakeTrigger, Boolean) -> Unit,
)

/**
 * "⏳ Active in 0:45" for an automation in its one-minute wait, or null. Ticks every second while
 * any wait is running.
 */
@Composable
private fun rememberCooldownText(cooldowns: Map<AutomationKey, Long>): (AutomationKey) -> String? {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldowns) {
        now = System.currentTimeMillis()
        while (cooldowns.values.any { it > now }) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return { key ->
        cooldowns[key]?.takeIf { it > now }?.let { untilMs ->
            context.getString(R.string.automation_active_in, formatDurationClock(untilMs - now))
        }
    }
}

/** Settings → Beacon places: how quickly they react by default (each place can choose its own). */
@Composable
private fun BeaconTimingCard(preset: BeaconPreset, custom: BeaconTiming, onChange: (BeaconPreset, BeaconTiming) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.beacon_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Speed,
                title = stringResource(R.string.beacon_timing_title) + ": " + stringResource(beaconPresetLabel(preset)),
                summary = timingSummary(context, BeaconTiming.of(preset, custom)),
                onClick = { editing = true },
            )
            Text(
                stringResource(R.string.beacon_timing_settings_desc),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    if (editing) {
        BeaconTimingDialog(
            preset = preset,
            custom = custom,
            defaultTiming = null,
            onConfirm = { chosen, numbers ->
                onChange(chosen ?: BeaconPreset.BALANCED, numbers)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/** Settings → Automations: the one-minute wait after an automation changes. */
@Composable
private fun AutomationCooldownCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.tab_automations),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchSettingsRow(
                icon = Icons.Filled.HourglassTop,
                title = stringResource(R.string.automation_cooldown),
                summary = stringResource(R.string.automation_cooldown_desc),
                checked = enabled,
                onCheckedChange = onChange,
            )
        }
    }
}

/** How faded a list row is when it isn't ready yet, e.g. an NFC tag that isn't written. */
private const val DIMMED_ALPHA = 0.5f

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
internal fun SetupBanner(onOpenSettings: () -> Unit) {
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
    granted: Set<AppPermission>,
    onToggle: (AlarmTrigger, Boolean) -> Unit,
    waitText: (Long) -> String?,
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
                    subtitle = listOfNotNull(
                        withPauseNote(
                            formatAlarmTrigger(context, trigger, appLabel, profile?.name),
                            pauseNote(context, PermissionNeeds.check(PermissionNeeds.forAlarmTrigger, granted)),
                        ),
                        waitText(trigger.id),
                    ).joinToString("\n"),
                    onClick = { onEdit(trigger) },
                    leading = { ProfileIcon(automationIcon(trigger.icon, profile), trigger.name) },
                    trailing = { Switch(checked = trigger.enabled, onCheckedChange = { onToggle(trigger, it) }) },
                    dimmed = !trigger.enabled,
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
    /** True if any calendar trigger exists, so the calendar row matters. */
    val calendarTriggersExist: Boolean = false,
    val calendarGranted: Boolean = false,
    /** True if any place uses a beacon, so the "Nearby devices" row matters. */
    val beaconPlacesExist: Boolean = false,
    val bluetoothScanGranted: Boolean = false,
    /** The "Nearby devices" scan permission alone (without location), for Settings → Permissions. */
    val nearbyScanGranted: Boolean = false,
    val bluetoothAdvertiseGranted: Boolean = false,
    /** True if any NFC tag exists, so the "NFC on" row matters. */
    val nfcTagsExist: Boolean = false,
    val hasNfc: Boolean = false,
    val nfcOn: Boolean = false,
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
    val onGrantCalendar: () -> Unit,
    val onGrantBluetoothScan: () -> Unit,
    val onTurnOnNfc: () -> Unit,
)

/** The permissions checklist. Shared by the home screen and onboarding. */
@Composable
fun SetupChecklist(setup: SetupStatus, actions: SetupActions) {
    // Each row: its label, whether it's allowed, and what "Enable" does.
    val rows = buildList {
        add(Triple(stringResource(R.string.setup_admin), setup.adminActive, actions.onEnableAdmin))
        add(Triple(stringResource(R.string.setup_notifications), setup.notificationsGranted, actions.onRequestNotifications))
        add(Triple(stringResource(R.string.setup_accessibility), setup.accessibilityEnabled, actions.onEnableAccessibility))
        add(Triple(stringResource(R.string.setup_background), setup.batteryUnrestricted, actions.onAllowBackground))
        if (setup.locationTriggersExist) {
            add(Triple(stringResource(R.string.setup_location), setup.preciseLocationGranted, actions.onGrantLocation))
            add(Triple(stringResource(R.string.setup_background_location), setup.backgroundLocationGranted, actions.onGrantBackgroundLocation))
            add(Triple(stringResource(R.string.setup_location_services), setup.locationServicesOn, actions.onEnableLocationServices))
        }
        if (setup.beaconPlacesExist) add(Triple(stringResource(R.string.setup_nearby_devices), setup.bluetoothScanGranted, actions.onGrantBluetoothScan))
        if (setup.nfcTagsExist && setup.hasNfc) add(Triple(stringResource(R.string.setup_nfc), setup.nfcOn, actions.onTurnOnNfc))
        if (setup.calendarTriggersExist) add(Triple(stringResource(R.string.setup_calendar), setup.calendarGranted, actions.onGrantCalendar))
        if (setup.alarmTriggersExist) {
            add(Triple(stringResource(R.string.setup_notification_access), setup.notificationAccessGranted, actions.onGrantNotificationAccess))
        }
        if (setup.dndWanted) add(Triple(stringResource(R.string.setup_dnd), setup.dndAccessGranted, actions.onGrantDnd))
        // Normally granted automatically; shown only if the user revoked it.
        if (!setup.exactAlarmsAllowed) add(Triple(stringResource(R.string.setup_exact_alarms), false, actions.onAllowExactAlarms))
    }
    val (allowed, missing) = rows.partition { it.second }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (missing.isEmpty()) R.string.setup_all_set else R.string.setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            missing.forEach { (label, _, onFix) -> SetupRow(label, false, onFix) }
            if (allowed.isNotEmpty()) {
                Text(
                    stringResource(R.string.setup_allowed_list, allowed.joinToString(" · ") { it.first }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Global rules for every lock: what Home does during a pass, the assistant popup, and unlocking into an allowed app. */
@Composable
private fun DuringLockCard(
    assistantPopupAllowed: Boolean,
    onAssistantPopupChange: (Boolean) -> Unit,
    homeAction: HomeAction,
    onHomeActionChange: (HomeAction) -> Unit,
    unlockIntoAllowedApp: Boolean,
    onUnlockIntoAllowedAppChange: (Boolean) -> Unit,
    /** How many apps may show popups during a lock; null hides the row (Simple mode). */
    popupAppCount: Int?,
    onEditPopupApps: () -> Unit,
    relocks: RelockUi,
    appLabel: (String) -> String,
    timeLeft: TimeLeftUi,
) {
    var choosingHome by remember { mutableStateOf(false) }
    var showingRelocks by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.during_lock_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsRow(
                icon = Icons.Filled.Home,
                title = stringResource(R.string.home_action_title),
                summary = stringResource(homeActionLabel(homeAction)),
                onClick = { choosingHome = true },
            )
            SwitchSettingsRow(
                icon = Icons.Filled.Assistant,
                title = stringResource(R.string.assistant_popup_title),
                summary = stringResource(R.string.assistant_popup_hint),
                checked = assistantPopupAllowed,
                onCheckedChange = onAssistantPopupChange,
            )
            SwitchSettingsRow(
                icon = Icons.Filled.LockOpen,
                title = stringResource(R.string.unlock_into_allowed_title),
                summary = stringResource(R.string.unlock_into_allowed_hint),
                checked = unlockIntoAllowedApp,
                onCheckedChange = onUnlockIntoAllowedAppChange,
            )
            if (popupAppCount != null) {
                SettingsRow(
                    icon = Icons.Filled.Layers,
                    title = stringResource(R.string.popup_apps_title),
                    summary = if (popupAppCount == 0) {
                        stringResource(R.string.popup_apps_none)
                    } else {
                        LocalContext.current.resources.getQuantityString(R.plurals.apps_count, popupAppCount, popupAppCount)
                    } + "\n" + stringResource(R.string.popup_apps_hint),
                    onClick = onEditPopupApps,
                )
            }
            SwitchSettingsRow(
                icon = Icons.Filled.Timer,
                title = stringResource(R.string.time_left_title),
                summary = stringResource(R.string.time_left_hint),
                checked = timeLeft.show,
                onCheckedChange = timeLeft.onShowChange,
            )
            if (timeLeft.show && isPro()) {
                SwitchSettingsRow(
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.time_left_large),
                    summary = null,
                    checked = timeLeft.large,
                    onCheckedChange = timeLeft.onLargeChange,
                )
                SettingsRow(
                    icon = Icons.Filled.Timer,
                    title = stringResource(R.string.time_left_reset),
                    summary = null,
                    onClick = timeLeft.onResetPosition,
                )
            }
            SettingsRow(
                icon = Icons.Filled.ManageSearch,
                title = stringResource(R.string.relock_history_title),
                summary = relocks.entries.firstOrNull()?.let { last ->
                    stringResource(R.string.relock_history_last, appLabel(last.packageName))
                } ?: stringResource(R.string.relock_history_none_short),
                onClick = { showingRelocks = true },
            )
        }
    }
    if (showingRelocks) {
        RelockHistoryDialog(
            entries = relocks.entries,
            appLabel = appLabel,
            popupPackages = relocks.popupPackages,
            onAllowPopups = relocks.onAllowPopups,
            canAllowPopups = relocks.canAllowPopups,
            onClear = relocks.onClear,
            onDismiss = { showingRelocks = false },
        )
    }
    if (choosingHome) {
        ChoiceDialog(
            title = stringResource(R.string.home_action_title),
            options = HomeAction.entries,
            selected = homeAction,
            label = { stringResource(homeActionLabel(it)) },
            onSelect = {
                onHomeActionChange(it)
                choosingHome = false
            },
            onDismiss = { choosingHome = false },
        )
    }
}

private fun homeActionLabel(action: HomeAction): Int = when (action) {
    HomeAction.ALLOWED_APPS -> R.string.home_action_allowed_apps
    HomeAction.COUNTDOWN -> R.string.home_action_countdown
    HomeAction.LOCK -> R.string.home_action_lock
}

/** Theme (System / Light / Dark / OLED black), and icons, clock, sizes and font of the lock-screen app picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    onChange: (ThemeMode) -> Unit,
    showPickerIcons: Boolean,
    onShowPickerIconsChange: (Boolean) -> Unit,
    pickerAppearance: PickerAppearance,
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
            HorizontalDivider()
            PickerAppearanceSection(pickerAppearance)
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

@Composable
private fun SchedulesCard(
    schedules: List<LockSchedule>,
    profiles: List<LockProfile>,
    onEdit: (LockSchedule?) -> Unit,
    onReorder: (List<LockSchedule>) -> Unit,
    clashes: Map<Long, String>,
    onToggle: (LockSchedule, Boolean) -> Unit,
    waitText: (Long) -> String?,
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
                    subtitle = listOfNotNull(clashes[schedule.id], waitText(schedule.id)).joinToString("\n").ifEmpty { null },
                    onClick = { onEdit(schedule) },
                    leading = { ProfileIcon(automationIcon(schedule.icon, profile), profile?.name.orEmpty()) },
                    trailing = { Switch(checked = schedule.enabled, onCheckedChange = { onToggle(schedule, it) }) },
                    dimmed = !schedule.enabled,
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
    dimmed: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) DIMMED_ALPHA else 1f)
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

/** A list row's summary with its "Paused: needs …" line, if any. */
private fun withPauseNote(summary: String, note: String?): String = listOfNotNull(summary, note).joinToString("\n")

/** Settings → Permissions: see, allow, manage or revoke each one. */
@Composable
private fun PermissionsCard(onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permissions_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.permissions_card_hint), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.permissions_open))
            }
        }
    }
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
    /** Opens Quick lock's settings screen. */
    val onOpenQuickOptions: () -> Unit,
    /** Saves Quick lock's settings as they're changed, so they're prefilled next time. */
    val onQuickSettingsChange: (LockSettings) -> Unit,
    /** Copies the Lock defaults into Quick lock. */
    val onResetQuickToDefaults: () -> Unit,
    /** Starts a Quick lock with the saved settings. */
    val onStartQuick: () -> Unit,
    val onStartProfile: (profile: LockProfile, durationMs: Long) -> Unit,
)

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

/** ▶ Start from a profile: confirm, then lock for the profile's own length. */
@Composable
internal fun StartProfileDialog(
    profile: LockProfile,
    onStart: (durationMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val durationMs = profile.defaultDurationSec * 1_000
    val length = formatLengthLabel(durationMs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_start_title, profile.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        !profile.bypassEnabled -> stringResource(R.string.start_confirm_body, length)
                        profile.bypassWaitMinutes == 0 -> stringResource(R.string.start_confirm_body_instant, length)
                        else -> stringResource(R.string.start_confirm_body_bypass, length, profile.bypassWaitMinutes)
                    }
                )
                Text(formatProfileSummary(LocalContext.current, profile), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onStart(durationMs) }) { Text(stringResource(R.string.start_confirm_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** @param bypassWaitMinutes the early-unlock wait, or null if early unlock is off. */
@Composable
internal fun ConfirmLockDialog(
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
                if (bypassWaitMinutes == 0) {
                    stringResource(R.string.start_confirm_body_instant, formatLengthLabel(durationMs))
                } else if (bypassWaitMinutes != null) {
                    stringResource(R.string.start_confirm_body_bypass, formatLengthLabel(durationMs), bypassWaitMinutes)
                } else {
                    stringResource(R.string.start_confirm_body, formatLengthLabel(durationMs))
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
