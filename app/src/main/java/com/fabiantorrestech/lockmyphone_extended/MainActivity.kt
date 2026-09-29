package com.fabiantorrestech.lockmyphone_extended

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteConstraintException
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fabiantorrestech.lockmyphone_extended.core.enforce.LockAccessibilityService
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles
import com.fabiantorrestech.lockmyphone_extended.data.BypassDefaults
import com.fabiantorrestech.lockmyphone_extended.data.LockDefaults
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmMatchMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmHealthChecker
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmLearning
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmMatcher
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmWatchListener
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.QrAlarmUpcoming
import com.fabiantorrestech.lockmyphone_extended.ui.AlarmProblemActions
import com.fabiantorrestech.lockmyphone_extended.ui.AlarmProblemUi
import com.fabiantorrestech.lockmyphone_extended.ui.AlarmTriggerEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.AppPickerScreen
import com.fabiantorrestech.lockmyphone_extended.ui.HomeTab
import com.fabiantorrestech.lockmyphone_extended.pass.PassActivity
import com.fabiantorrestech.lockmyphone_extended.ui.AllowedAppsScreen
import com.fabiantorrestech.lockmyphone_extended.ui.DeviceProfilePicker
import com.fabiantorrestech.lockmyphone_extended.ui.DiagnosticsScreen
import com.fabiantorrestech.lockmyphone_extended.ui.HomeScreen
import com.fabiantorrestech.lockmyphone_extended.ui.IntentApiActions
import com.fabiantorrestech.lockmyphone_extended.ui.IntentApiUiState
import com.fabiantorrestech.lockmyphone_extended.ui.LockedScreen
import com.fabiantorrestech.lockmyphone_extended.ui.OnboardingScreen
import com.fabiantorrestech.lockmyphone_extended.ui.ProfileEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.LockSettings
import com.fabiantorrestech.lockmyphone_extended.ui.FixRequest
import com.fabiantorrestech.lockmyphone_extended.ui.LocationEditorScreen
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationSlot
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.ui.ReorderActions
import com.fabiantorrestech.lockmyphone_extended.ui.ScheduleEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.SetupActions
import com.fabiantorrestech.lockmyphone_extended.ui.SetupChecklist
import com.fabiantorrestech.lockmyphone_extended.ui.SetupStatus
import com.fabiantorrestech.lockmyphone_extended.ui.StartLockActions
import com.fabiantorrestech.lockmyphone_extended.ui.formatDurationLabel
import com.fabiantorrestech.lockmyphone_extended.ui.theme.LockMyPhoneExtendedTheme
import kotlinx.coroutines.launch

/** The screens reachable while no lock is active. */
private enum class Screen {
    HOME, DEVICE, DIAGNOSTICS, PROFILE, PROFILE_APPS, SCHEDULE, QUICK_APPS, ALARM_TRIGGER, ALARM_APP, DEFAULTS, DEFAULT_APPS,
    LOCATION,
}

// Play Services pulls in an old androidx.fragment, which trips this lint check. This activity is a
// plain ComponentActivity with no fragments, so the Activity Result API works as intended here.
@SuppressLint("InvalidFragmentVersionForActivityResult")
class MainActivity : ComponentActivity() {

    private val container by lazy { LockApp.container(this) }

    // Permission and admin state can change in system screens, so it's refreshed in onResume.
    private val setupStatus = mutableStateOf(
        SetupStatus(
            adminActive = false,
            notificationsGranted = false,
            accessibilityEnabled = false,
            exactAlarmsAllowed = true,
            batteryUnrestricted = false,
            dndAccessGranted = false,
            dndWanted = false,
            notificationAccessGranted = false,
            alarmTriggersExist = false,
        )
    )

    /** A tab requested from outside, e.g. by tapping an alarm-trigger warning notification. */
    private val requestedTab = mutableStateOf<HomeTab?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSetupStatus() }

    /** Precise location first; Android only offers "Allow all the time" as a separate, later step. */
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onLocationPermissionChanged() }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onLocationPermissionChanged() }

    private val setupActions by lazy {
        SetupActions(
            onEnableAdmin = ::requestDeviceAdmin,
            onRequestNotifications = ::requestNotificationPermission,
            onEnableAccessibility = ::openAccessibilitySettings,
            onAllowExactAlarms = ::openExactAlarmSettings,
            onAllowBackground = ::requestUnrestrictedBattery,
            onGrantDnd = ::openDndAccessSettings,
            onGrantNotificationAccess = ::openNotificationAccessSettings,
            onGrantLocation = ::requestLocationPermission,
            onGrantBackgroundLocation = ::requestBackgroundLocation,
            onEnableLocationServices = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The UI is only reachable after the user unlocked, so the default profile can be seeded
        // from the older settings now.
        lifecycleScope.launch { container.ensureDefaultProfile() }
        readRequestedTab(intent)
        setContent {
            val themeMode by container.settingsStore.themeMode.collectAsState()
            LockMyPhoneExtendedTheme(themeMode) {
                val lockState by container.lockController.state.collectAsState()
                val onboardingDone by container.settingsStore.onboardingDone.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier.padding(innerPadding)
                    val locked = lockState
                    when {
                        locked is LockState.Locked -> LockedScreen(
                            session = locked.session,
                            passButtonsEnabled = LockAccessibilityService.isRunning,
                            onOpenAllowedApps = { startActivity(PassActivity.pickerIntent(this)) },
                            onOpenPhone = { startActivity(PassActivity.dialIntent(this)) },
                            modifier = contentModifier,
                            choiceDeadlineMs = container.lockController.choiceDeadlineMs.collectAsState().value,
                        )
                        !onboardingDone -> OnboardingScreen(
                            deviceProfilePicker = { DeviceProfileSection() },
                            setupChecklist = { SetupChecklist(setupStatus.value, setupActions) },
                            onFinish = { container.settingsStore.setOnboardingDone(true) },
                            modifier = contentModifier,
                        )
                        else -> IdleContent(contentModifier)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readRequestedTab(intent)
    }

    private fun readRequestedTab(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_AUTOMATIONS) requestedTab.value = HomeTab.AUTOMATIONS
    }

    @Composable
    private fun IdleContent(modifier: Modifier) {
        var screen by remember { mutableStateOf(Screen.HOME) }
        // Drafts live here so they survive moving between an editor and the app list.
        var profileDraft by remember { mutableStateOf<LockProfile?>(null) }
        var profileError by remember { mutableStateOf<String?>(null) }
        var scheduleDraft by remember { mutableStateOf<LockSchedule?>(null) }
        var alarmDraft by remember { mutableStateOf<AlarmTrigger?>(null) }
        var locationDraft by remember { mutableStateOf<LocationTrigger?>(null) }
        var fixRequest by remember { mutableStateOf<FixRequest>(FixRequest.Idle) }
        var currentWifi by remember { mutableStateOf<ConnectedWifi?>(null) }
        var slotDrafts by remember { mutableStateOf<List<LocationSlot>>(emptyList()) }
        // The Lock defaults page edits a profile-shaped draft (no name, never saved as a profile).
        var defaultsDraft by remember { mutableStateOf<LockProfile?>(null) }
        // Kept here (not in HomeScreen) so the tab survives a trip into an editor.
        var homeTab by remember { mutableStateOf(HomeTab.QUICK_LOCK) }

        val profiles by container.database.profileDao().observeAll().collectAsState(initial = emptyList())
        val schedules by container.database.scheduleDao().observeAll().collectAsState(initial = emptyList())
        val alarmTriggers by container.database.alarmTriggerDao().observeAll().collectAsState(initial = emptyList())
        val locations by container.database.locationTriggerDao().observeAll().collectAsState(initial = emptyList())
        val locationEngine by container.settingsStore.locationEngine.collectAsState()
        val locationManager = container.locationTriggerManager
        val learning by container.alarmLearning.state.collectAsState()
        val alarmProblems by container.alarmTriggerHealth.problems.collectAsState()
        var upcomingQrAlarms by remember { mutableStateOf<List<QrAlarmUpcoming.Upcoming>>(emptyList()) }
        val refreshUpcoming = {
            upcomingQrAlarms = AlarmWatchListener.instance?.activeQrAlarmUpcoming().orEmpty()
        }
        val oemProfileId by container.settingsStore.oemProfileId.collectAsState()
        val activeOemProfile = remember(oemProfileId) { container.activeOemProfile() }
        val stopEnabled by container.settingsStore.intentStopEnabled.collectAsState()
        val quickDnd by container.settingsStore.quickDndEnabled.collectAsState()
        val quickDndLevel by container.settingsStore.quickDndLevel.collectAsState()
        val showIcons by container.settingsStore.showAppIcons.collectAsState()
        val quickApps by container.settingsStore.allowedPackages.collectAsState()
        val quickDurationSec by container.settingsStore.quickDurationSec.collectAsState()
        val quickBypass by container.settingsStore.bypassDefaults.collectAsState()
        val quickResumeMode by container.settingsStore.quickResumeMode.collectAsState()
        val quickChoiceSeconds by container.settingsStore.quickChoiceSeconds.collectAsState()
        val quickGrayscale by container.settingsStore.quickGrayscale.collectAsState()
        val presetsSec by container.settingsStore.durationPresetsSec.collectAsState()
        val alarmDayStart by container.settingsStore.alarmDayStartMinutes.collectAsState()
        val firstAlarmApps = remember {
            KNOWN_ALARM_APPS.filter(container.appCatalog::isInstalled)
                .map { LaunchableApp(it, container.appCatalog.label(it)) }
        }
        val token by container.settingsStore.intentToken.collectAsState()
        // Remembered so the list screen isn't handed a "new" function on every recomposition.
        val loadApps = remember { container.appCatalog::launchableApps }
        val scope = rememberCoroutineScope()
        val goHome = {
            screen = Screen.HOME
            profileDraft = null
            profileError = null
            scheduleDraft = null
            alarmDraft = null
            container.alarmLearning.cancel()
        }

        // Opening from a warning notification jumps to the Schedules tab.
        LaunchedEffect(requestedTab.value) {
            requestedTab.value?.let {
                homeTab = it
                screen = Screen.HOME
                requestedTab.value = null
            }
        }
        // Re-check alarm-trigger health whenever the triggers change or the app opens.
        LaunchedEffect(alarmTriggers) { container.alarmHealthChecker.check() }

        // A learned alarm fills in the trigger being edited.
        LaunchedEffect(learning) {
            val learned = learning as? AlarmLearning.State.Learned ?: return@LaunchedEffect
            val draft = alarmDraft
            if (draft != null && draft.packageName == learned.fingerprint.packageName) {
                val fp = learned.fingerprint
                val time = container.alarmTimeStore.get(fp.packageName, fp.notificationId)
                alarmDraft = draft.copy(
                    name = suggestedTriggerName(draft, time),
                    channelId = fp.channelId,
                    notificationId = fp.notificationId,
                    title = fp.title,
                    text = fp.text,
                    matchMode = AlarmMatcher.chooseMatchMode(fp),
                )
            }
            container.alarmLearning.cancel()
        }

        when (screen) {
            Screen.HOME -> HomeScreen(
                selectedTab = homeTab,
                onTabChange = { homeTab = it },
                setup = setupStatus.value,
                setupActions = setupActions,
                deviceProfileName = activeOemProfile.displayName,
                onChangeDevice = { screen = Screen.DEVICE },
                profiles = profiles,
                schedules = schedules,
                onEditProfile = { existing ->
                    profileDraft = existing ?: newProfileDraft()
                    screen = Screen.PROFILE
                },
                onEditSchedule = { existing ->
                    scheduleDraft = existing ?: newScheduleDraft(profiles.first().id)
                    screen = Screen.SCHEDULE
                },
                alarmTriggers = alarmTriggers,
                reorderActions = remember {
                    ReorderActions(
                        onReorderProfiles = { list -> scope.launch { container.database.profileDao().reorder(list.map { it.id }) } },
                        onReorderSchedules = { list -> scope.launch { container.database.scheduleDao().reorder(list.map { it.id }) } },
                        onReorderTriggers = { list -> scope.launch { container.database.alarmTriggerDao().reorder(list.map { it.id }) } },
                        onReorderLocations = { list -> scope.launch { container.database.locationTriggerDao().reorder(list.map { it.id }) } },
                    )
                },
                appLabel = container.appCatalog::label,
                alarmProblems = alarmProblems.values.mapNotNull { problem ->
                    val trigger = alarmTriggers.firstOrNull { it.id == problem.triggerId } ?: return@mapNotNull null
                    AlarmProblemUi(
                        triggerId = trigger.id,
                        triggerName = trigger.name,
                        reason = AlarmHealthChecker.reasonText(this, problem),
                        relinkLabel = problem.suggestion?.let {
                            getString(R.string.trigger_warning_relink, it.alarmId, it.time ?: getString(R.string.alarm_trigger_unknown_time))
                        },
                    )
                },
                alarmProblemActions = remember {
                    AlarmProblemActions(
                        onRelink = { triggerId -> scope.launch { relinkAlarmTrigger(triggerId) } },
                        onOpen = { triggerId ->
                            scope.launch {
                                val trigger = container.database.alarmTriggerDao().getById(triggerId) ?: return@launch
                                alarmDraft = trigger.copy(matchMode = AlarmMatcher.effectiveMatchMode(trigger))
                                screen = Screen.ALARM_TRIGGER
                            }
                        },
                        onDismiss = { triggerId ->
                            container.alarmTriggerHealth.dismiss(triggerId)
                            scope.launch { container.alarmHealthChecker.check() }
                        },
                    )
                },
                locations = locations,
                onEditLocation = { existing ->
                    locationDraft = existing ?: newLocationDraft()
                    fixRequest = FixRequest.Idle
                    currentWifi = locationManager.connectedWifi()
                    slotDrafts = emptyList()
                    existing?.let { place ->
                        scope.launch { slotDrafts = container.database.locationSlotDao().getForLocation(place.id) }
                    }
                    screen = Screen.LOCATION
                },
                locationEngine = locationEngine,
                playServicesAvailable = remember { locationManager.playServices.isAvailable() },
                onLocationEngineChange = { choice ->
                    container.settingsStore.setLocationEngine(choice)
                    scope.launch { locationManager.sync() }
                },
                onEditAlarmTrigger = { existing ->
                    // A QRAlarm trigger saved by title (before QRAlarm Pro was recognised) is saved by ID from now on.
                    alarmDraft = existing?.copy(matchMode = AlarmMatcher.effectiveMatchMode(existing)) ?: newAlarmTriggerDraft()
                    screen = Screen.ALARM_TRIGGER
                },
                intentApi = IntentApiUiState(stopEnabled, token, packageName),
                intentApiActions = remember {
                    IntentApiActions(
                        onStopEnabledChange = container.settingsStore::setIntentStopEnabled,
                        onCopy = ::copyToClipboard,
                        onRegenerateToken = container.settingsStore::regenerateIntentToken,
                    )
                },
                onOpenDiagnostics = { screen = Screen.DIAGNOSTICS },
                onOpenLockDefaults = {
                    defaultsDraft = container.settingsStore.lockDefaults.value.toProfileDraft()
                    screen = Screen.DEFAULTS
                },
                quickLock = LockSettings(
                    durationSec = quickDurationSec,
                    allowedAppCount = quickApps.size,
                    bypassEnabled = quickBypass.enabled,
                    bypassWaitMinutes = quickBypass.waitMinutes,
                    dndEnabled = quickDnd,
                    dndLevel = quickDndLevel,
                    grayscale = quickGrayscale,
                    resumeMode = quickResumeMode,
                    choiceSeconds = quickChoiceSeconds,
                ),
                presetsSec = presetsSec,
                onPresetsChange = container.settingsStore::setDurationPresets,
                alarmDayStartMinutes = alarmDayStart,
                onAlarmDayStartChange = container.settingsStore::setAlarmDayStartMinutes,
                themeMode = container.settingsStore.themeMode.collectAsState().value,
                onThemeModeChange = container.settingsStore::setThemeMode,
                showPickerIcons = showIcons,
                onShowPickerIconsChange = container.settingsStore::setShowAppIcons,
                startActions = remember {
                    StartLockActions(
                        onEditQuickApps = { screen = Screen.QUICK_APPS },
                        onQuickSettingsChange = ::saveQuickSettings,
                        onResetQuickToDefaults = {
                            container.settingsStore.resetQuickToDefaults()
                            val store = container.settingsStore
                            if (store.quickDndEnabled.value || store.quickGrayscale.value) askForDndAccessIfNeeded()
                        },
                        onStartQuick = ::startQuickLock,
                        onStartProfile = ::startProfileLock,
                    )
                },
                modifier = modifier,
            )
            Screen.LOCATION -> locationDraft?.let { draft ->
                LocationEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    dayStartMinutes = alarmDayStart,
                    hasLocationPermission = setupStatus.value.preciseLocationGranted,
                    fixRequest = fixRequest,
                    currentWifi = currentWifi,
                    slots = slotDrafts,
                    onSlotsChange = { slotDrafts = it },
                    onRequestLocationPermission = ::requestLocationPermission,
                    onUseCurrentLocation = {
                        fixRequest = FixRequest.Locating
                        scope.launch {
                            val fix = locationManager.currentFix()
                            if (fix == null) {
                                fixRequest = FixRequest.Failed
                            } else {
                                fixRequest = FixRequest.Found(fix.accuracyMeters)
                                locationDraft = locationDraft?.copy(latitude = fix.latitude, longitude = fix.longitude)
                            }
                            currentWifi = locationManager.connectedWifi()
                        }
                    },
                    onPreviewInMaps = { openInMaps(draft) },
                    onDraftChange = { locationDraft = it },
                    onSave = {
                        scope.launch {
                            val dao = container.database.locationTriggerDao()
                            val id = if (draft.id == 0L) dao.insertLast(draft) else draft.id.also { dao.update(draft) }
                            val slots = if (draft.mode == LocationMode.TIME_SLOTS) slotDrafts else emptyList()
                            container.database.locationSlotDao().replaceForLocation(id, slots)
                            locationManager.onTriggerSaved(id)
                            // Watching places in the background needs "Allow all the time".
                            if (locationManager.hasPreciseLocation() && !locationManager.hasBackgroundLocation()) {
                                requestBackgroundLocation()
                            }
                            goHome()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.locationTriggerDao().delete(draft)
                            locationManager.sync()
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                )
            }
            Screen.ALARM_TRIGGER -> alarmDraft?.let { draft ->
                AlarmTriggerEditorScreen(
                    draft = draft,
                    firstAlarmApps = firstAlarmApps,
                    dayStartMinutes = alarmDayStart,
                    appName = draft.packageName.takeIf { it.isNotEmpty() }?.let(container.appCatalog::label),
                    profiles = profiles,
                    learningWaiting = learning is AlarmLearning.State.Waiting,
                    learningRejectedTimer = (learning as? AlarmLearning.State.Waiting)?.rejectedTimer == true,
                    notificationAccess = setupStatus.value.notificationAccessGranted,
                    learnedTime = draft.notificationId?.let { container.alarmTimeStore.get(draft.packageName, it) },
                    upcomingQrAlarms = upcomingQrAlarms,
                    onRefreshUpcoming = refreshUpcoming,
                    onPickUpcoming = { upcoming ->
                        // Same fingerprint a ring would give: QRAlarm's ringing channel and the alarm ID.
                        alarmDraft = draft.copy(
                            name = suggestedTriggerName(draft, upcoming.time),
                            channelId = QrAlarmUpcoming.RINGING_CHANNEL,
                            notificationId = upcoming.alarmId,
                            title = null,
                            text = null,
                            matchMode = AlarmMatchMode.NOTIFICATION_ID,
                        )
                    },
                    onDraftChange = { alarmDraft = it },
                    onPickApp = { screen = Screen.ALARM_APP },
                    onLearn = { container.alarmLearning.start(draft.packageName) },
                    onGrantAccess = ::openNotificationAccessSettings,
                    onSave = {
                        scope.launch {
                            val dao = container.database.alarmTriggerDao()
                            val id = if (draft.id == 0L) dao.insertLast(draft) else draft.id.also { dao.update(draft) }
                            // A saved (or re-learned) trigger starts with a clean bill of health.
                            container.alarmTriggerHealth.onTriggerSaved(id)
                            container.alarmHealthChecker.check()
                            goHome()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.alarmTriggerDao().delete(draft)
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                )
            }
            Screen.ALARM_APP -> alarmDraft?.let { draft ->
                AppPickerScreen(
                    title = stringResource(R.string.alarm_trigger_pick_app),
                    loadApps = loadApps,
                    iconProvider = container.appCatalog,
                    suggested = KNOWN_ALARM_APPS,
                    onPick = { app ->
                        // A different app means the learned alarm no longer applies.
                        alarmDraft = if (app.packageName == draft.packageName) {
                            draft
                        } else {
                            draft.copy(
                                packageName = app.packageName,
                                name = draft.name.ifBlank { app.label },
                                channelId = null,
                                notificationId = null,
                                title = null,
                                text = null,
                            )
                        }
                        screen = Screen.ALARM_TRIGGER
                    },
                    onBack = { screen = Screen.ALARM_TRIGGER },
                    modifier = modifier,
                )
            }
            Screen.QUICK_APPS -> AllowedAppsScreen(
                loadApps = loadApps,
                iconProvider = container.appCatalog,
                selected = quickApps,
                onSelectionChange = container.settingsStore::setAllowedPackages,
                onBack = goHome,
                modifier = modifier,
            )
            Screen.PROFILE -> profileDraft?.let { draft ->
                ProfileEditorScreen(
                    draft = draft,
                    presetsSec = presetsSec,
                    canDelete = draft.id != 0L && profiles.size > 1,
                    error = profileError,
                    onDraftChange = {
                        profileDraft = it
                        profileError = null
                    },
                    onEditAllowedApps = { screen = Screen.PROFILE_APPS },
                    onSave = { final ->
                        scope.launch {
                            if (saveProfile(final)) {
                                if (final.dndEnabled || final.grayscale) askForDndAccessIfNeeded()
                                goHome()
                            } else {
                                profileError = getString(R.string.profile_name_taken)
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.profileDao().delete(draft)
                            container.scheduleScheduler.sync() // its schedules were deleted too
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                )
            }
            Screen.DEFAULTS -> defaultsDraft?.let { draft ->
                ProfileEditorScreen(
                    draft = draft,
                    presetsSec = presetsSec,
                    canDelete = false,
                    error = null,
                    onDraftChange = { defaultsDraft = it },
                    onEditAllowedApps = { screen = Screen.DEFAULT_APPS },
                    onSave = { final ->
                        container.settingsStore.setLockDefaults(LockDefaults.fromProfile(final))
                        if (final.dndEnabled || final.grayscale) askForDndAccessIfNeeded()
                        goHome()
                    },
                    onDelete = {},
                    onBack = goHome,
                    modifier = modifier,
                    isDefaults = true,
                )
            }
            Screen.DEFAULT_APPS -> defaultsDraft?.let { draft ->
                AllowedAppsScreen(
                    loadApps = loadApps,
                    iconProvider = container.appCatalog,
                    selected = draft.allowedPackages,
                    onSelectionChange = { defaultsDraft = draft.copy(allowedPackages = it) },
                    onBack = { screen = Screen.DEFAULTS },
                    modifier = modifier,
                )
            }
            Screen.PROFILE_APPS -> profileDraft?.let { draft ->
                AllowedAppsScreen(
                    loadApps = loadApps,
                    iconProvider = container.appCatalog,
                    selected = draft.allowedPackages,
                    onSelectionChange = { profileDraft = draft.copy(allowedPackages = it) },
                    onBack = { screen = Screen.PROFILE },
                    modifier = modifier,
                )
            }
            Screen.SCHEDULE -> scheduleDraft?.let { draft ->
                ScheduleEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    onDraftChange = { scheduleDraft = it },
                    onSave = {
                        scope.launch {
                            val dao = container.database.scheduleDao()
                            if (draft.id == 0L) dao.insertLast(draft) else dao.update(draft)
                            container.scheduleScheduler.sync()
                            goHome()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.scheduleDao().delete(draft)
                            container.scheduleScheduler.sync()
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                )
            }
            Screen.DEVICE -> {
                BackHandler(onBack = goHome)
                Column(modifier.padding(16.dp)) {
                    Text(stringResource(R.string.device_title), style = MaterialTheme.typography.headlineSmall)
                    DeviceProfileSection(Modifier.padding(top = 16.dp))
                }
            }
            Screen.DIAGNOSTICS -> {
                val entries by container.foregroundLog.entries.collectAsState()
                DiagnosticsScreen(
                    deviceInfo = remember { container.oemDetector.deviceInfo() },
                    activeProfile = activeOemProfile,
                    accessibilityRunning = setupStatus.value.accessibilityEnabled,
                    entries = entries,
                    onCopy = ::copyToClipboard,
                    onClear = container.foregroundLog::clear,
                    onBack = goHome,
                    modifier = modifier,
                )
            }
        }

        // Read QRAlarm's upcoming alarms whenever the trigger editor opens or its app changes.
        LaunchedEffect(screen, alarmDraft?.packageName) {
            if (screen == Screen.ALARM_TRIGGER && alarmDraft?.packageName?.let(QrAlarmUpcoming::isQrAlarm) == true) refreshUpcoming()
        }

        // The location rows only matter once a location trigger exists.
        LaunchedEffect(locations) {
            val exist = locations.isNotEmpty()
            if (setupStatus.value.locationTriggersExist != exist) {
                setupStatus.value = setupStatus.value.copy(locationTriggersExist = exist)
            }
        }

        // The notification-access row only matters once an alarm trigger exists.
        LaunchedEffect(alarmTriggers) {
            val exist = alarmTriggers.isNotEmpty()
            if (setupStatus.value.alarmTriggersExist != exist) {
                setupStatus.value = setupStatus.value.copy(alarmTriggersExist = exist)
            }
        }

        // The DND access row only matters when some lock wants DND.
        // Grayscale is a mode too, so it needs the same access.
        LaunchedEffect(quickDnd, quickGrayscale, profiles) {
            val wanted = quickDnd || quickGrayscale || profiles.any { it.dndEnabled || it.grayscale }
            if (setupStatus.value.dndWanted != wanted) setupStatus.value = setupStatus.value.copy(dndWanted = wanted)
        }

        // If a draft disappears (e.g. the process restarted on this screen), go home.
        LaunchedEffect(screen, profileDraft, scheduleDraft) {
            val missingDraft = when (screen) {
                Screen.PROFILE, Screen.PROFILE_APPS -> profileDraft == null
                Screen.SCHEDULE -> scheduleDraft == null
                Screen.ALARM_TRIGGER, Screen.ALARM_APP -> alarmDraft == null
                Screen.DEFAULTS, Screen.DEFAULT_APPS -> defaultsDraft == null
                Screen.LOCATION -> locationDraft == null
                else -> false
            }
            if (missingDraft) screen = Screen.HOME
        }
    }

    /** A new profile starts from the Lock defaults (Settings → Lock defaults). */
    private fun newProfileDraft() = container.settingsStore.lockDefaults.value.toProfileDraft()

    /** Points a trigger at the replacement alarm that was spotted for it. */
    private suspend fun relinkAlarmTrigger(triggerId: Long) {
        val problem = container.alarmTriggerHealth.problems.value[triggerId] ?: return
        val suggestion = problem.suggestion ?: return
        val dao = container.database.alarmTriggerDao()
        val trigger = dao.getById(triggerId) ?: return
        dao.update(
            trigger.copy(
                channelId = QrAlarmUpcoming.RINGING_CHANNEL,
                notificationId = suggestion.alarmId,
                matchMode = AlarmMatchMode.NOTIFICATION_ID,
            )
        )
        suggestion.time?.let { container.alarmTimeStore.put(trigger.packageName, suggestion.alarmId, it) }
        container.alarmTriggerHealth.onTriggerSaved(triggerId)
        container.alarmHealthChecker.check()
        Toast.makeText(this, getString(R.string.trigger_relinked, trigger.name, suggestion.alarmId), Toast.LENGTH_SHORT).show()
    }

    /** Keeps a name the user typed; otherwise suggests e.g. "QRAlarm 6:30 AM". */
    private fun suggestedTriggerName(draft: AlarmTrigger, time: String?): String {
        val appName = container.appCatalog.label(draft.packageName)
        val autoNames = setOf("", appName)
        if (draft.name.trim() !in autoNames || time == null) return draft.name
        return getString(R.string.alarm_trigger_suggested_name, appName, time)
    }

    private fun newAlarmTriggerDraft() = AlarmTrigger(
        name = "",
        packageName = "",
        channelId = null,
        notificationId = null,
        title = null,
        text = null,
        matchMode = AlarmMatchMode.NOTIFICATION_ID,
        profileId = null,
        delayMinutes = 0,
        enabled = true,
    )

    private fun newScheduleDraft(profileId: Long) = LockSchedule(
        profileId = profileId,
        daysMask = LockSchedule.EVERY_DAY,
        startMinuteOfDay = 22 * 60,
        endMinuteOfDay = 7 * 60,
    )

    /** Inserts or updates. Returns false if the name is already used by another profile. */
    private suspend fun saveProfile(profile: LockProfile): Boolean = try {
        val dao = container.database.profileDao()
        if (profile.id == 0L) dao.insertLast(profile) else dao.update(profile)
        true
    } catch (e: SQLiteConstraintException) {
        false
    }

    @Composable
    private fun DeviceProfileSection(modifier: Modifier = Modifier) {
        val selectedId by container.settingsStore.oemProfileId.collectAsState()
        val info = remember { container.oemDetector.deviceInfo() }
        DeviceProfilePicker(
            manufacturer = info.manufacturer,
            model = info.model,
            detected = remember { container.oemDetector.detectedProfile() },
            selectedId = selectedId?.takeIf { OemProfiles.byId(it) != null },
            onSelect = container.settingsStore::setOemProfileId,
            modifier = modifier,
        )
    }

    override fun onResume() {
        super.onResume()
        refreshSetupStatus()
    }

    private fun refreshSetupStatus() {
        setupStatus.value = SetupStatus(
            adminActive = container.relocker.isAdminActive(),
            notificationsGranted = hasNotificationPermission(),
            accessibilityEnabled = LockAccessibilityService.isEnabledInSettings(this),
            exactAlarmsAllowed = container.lockEndScheduler.canScheduleExact(),
            batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            dndAccessGranted = container.dndController.hasAccess(),
            dndWanted = setupStatus.value.dndWanted,
            notificationAccessGranted = AlarmWatchListener.isEnabled(this),
            alarmTriggersExist = setupStatus.value.alarmTriggersExist,
            locationTriggersExist = setupStatus.value.locationTriggersExist,
            preciseLocationGranted = container.locationTriggerManager.hasPreciseLocation(),
            backgroundLocationGranted = container.locationTriggerManager.hasBackgroundLocation(),
            locationServicesOn = container.locationTriggerManager.locationServicesOn(),
        )
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, container.relocker.adminComponent)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.admin_explanation))
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null))
            )
        }
    }

    /**
     * Asks to exempt the app from battery optimization. Android then lets it start the lock
     * service from the background, e.g. when a MacroDroid intent arrives.
     */
    @SuppressLint("BatteryLife") // Sideloaded; this exemption is core to the app.
    private fun requestUnrestrictedBattery() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /** On Android 11+ this opens the app's location page, where the user picks "Allow all the time". */
    private fun requestBackgroundLocation() {
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun onLocationPermissionChanged() {
        refreshSetupStatus()
        lifecycleScope.launch { container.locationTriggerManager.sync() }
    }

    /** Opens the place in Google Maps (or any maps app), with a pin labelled with its name. */
    private fun openInMaps(trigger: LocationTrigger) {
        val coordinates = "${trigger.latitude},${trigger.longitude}"
        val label = Uri.encode(trigger.name.ifBlank { getString(R.string.location_new) })
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates($label)")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.location_no_maps, Toast.LENGTH_SHORT).show()
        }
    }

    /** A new place, with no coordinates yet ("Use my current location" or typed). */
    private fun newLocationDraft() = LocationTrigger(name = "", latitude = Double.NaN, longitude = Double.NaN)

    /** Opens our entry in Settings → Notification access (needed to see alarm notifications). */
    private fun openNotificationAccessSettings() {
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    AlarmWatchListener.component(this).flattenToString(),
                )
        )
    }

    private fun openDndAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    }

    /** DND can't be switched on during a lock without access, so ask when it's first turned on. */
    private fun askForDndAccessIfNeeded() {
        if (!container.dndController.hasAccess()) {
            Toast.makeText(this, R.string.dnd_access_needed, Toast.LENGTH_LONG).show()
            openDndAccessSettings()
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProfileLock(profile: LockProfile, durationMs: Long) {
        val result = container.profileLockStarter.start(
            profile = profile,
            durationMs = durationMs,
            label = getString(R.string.manual_lock_label, "${profile.name}, ${formatDurationLabel(durationMs)}"),
        )
        showStartResult(result)
    }

    /** Saves every Quick lock setting as it's changed, so it's prefilled next time. */
    private fun saveQuickSettings(settings: LockSettings) {
        val store = container.settingsStore
        val turnedOnModes = (settings.dndEnabled && !store.quickDndEnabled.value) ||
            (settings.grayscale && !store.quickGrayscale.value)
        store.setQuickDurationSec(settings.durationSec)
        store.setBypassDefaults(BypassDefaults(settings.bypassEnabled, settings.bypassWaitMinutes))
        store.setQuickDndEnabled(settings.dndEnabled)
        store.setQuickDndLevel(settings.dndLevel)
        store.setQuickGrayscale(settings.grayscale)
        store.setQuickResume(settings.resumeMode, settings.choiceSeconds)
        if (turnedOnModes) askForDndAccessIfNeeded()
    }

    /** Starts a Quick lock with its saved settings. */
    private fun startQuickLock() {
        val durationMs = container.settingsStore.quickDurationSec.value * 1_000
        val result = container.profileLockStarter.startQuick(
            durationMs = durationMs,
            label = getString(R.string.manual_lock_label, "${getString(R.string.quick_lock)}, ${formatDurationLabel(durationMs)}"),
        )
        showStartResult(result)
    }

    private fun showStartResult(result: StartResult) {
        if (result == StartResult.ADMIN_NOT_ACTIVE) {
            Toast.makeText(this, R.string.start_needs_admin, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_AUTOMATIONS = "automations"

        /** Listed first when picking an alarm app. */
        val KNOWN_ALARM_APPS = listOf(
            "com.sweak.qralarm",
            "com.sweak.qralarm.pro",
            "com.google.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.android.deskclock",
        )
    }
}
