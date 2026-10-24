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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import android.nfc.Tag
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcRules
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcTagActivity
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.TapResult
import com.fabiantorrestech.lockmyphone_extended.ui.LockChooserDialog
import android.nfc.NfcAdapter
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcTagIo
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.TagWriteResult
import com.fabiantorrestech.lockmyphone_extended.ui.NfcTagEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.TagScanMode
import com.fabiantorrestech.lockmyphone_extended.core.enforce.LockAccessibilityService
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.core.lock.StartResult
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.ui.formatAlreadyLocked
import com.fabiantorrestech.lockmyphone_extended.ui.formatClash
import com.fabiantorrestech.lockmyphone_extended.ui.formatCalendarClash
import com.fabiantorrestech.lockmyphone_extended.ui.ClashWarnings
import com.fabiantorrestech.lockmyphone_extended.trigger.AutomationKey
import com.fabiantorrestech.lockmyphone_extended.trigger.TestLine
import com.fabiantorrestech.lockmyphone_extended.trigger.TestMark
import com.fabiantorrestech.lockmyphone_extended.trigger.TestReport
import com.fabiantorrestech.lockmyphone_extended.ui.TestNowDialog
import com.fabiantorrestech.lockmyphone_extended.trigger.ConflictRules
import com.fabiantorrestech.lockmyphone_extended.trigger.TimedCommitment
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarClash
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles
import com.fabiantorrestech.lockmyphone_extended.data.BypassDefaults
import com.fabiantorrestech.lockmyphone_extended.data.FontChoice
import com.fabiantorrestech.lockmyphone_extended.data.LockDefaults
import com.fabiantorrestech.lockmyphone_extended.data.SystemFont
import com.fabiantorrestech.lockmyphone_extended.data.ThemeMode
import com.fabiantorrestech.lockmyphone_extended.ui.HideStatusBar
import com.fabiantorrestech.lockmyphone_extended.ui.PickerAppearance
import com.fabiantorrestech.lockmyphone_extended.ui.rememberFontFamily
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
import com.fabiantorrestech.lockmyphone_extended.ui.BeaconTools
import com.fabiantorrestech.lockmyphone_extended.ui.PairedDeviceTools
import com.fabiantorrestech.lockmyphone_extended.ui.PermissionsScreen
import com.fabiantorrestech.lockmyphone_extended.ui.PermissionRow
import com.fabiantorrestech.lockmyphone_extended.ui.PermissionActions
import com.fabiantorrestech.lockmyphone_extended.ui.CORE_PERMISSIONS
import com.fabiantorrestech.lockmyphone_extended.ui.grantedPermissions
import com.fabiantorrestech.lockmyphone_extended.core.perm.AppPermission
import com.fabiantorrestech.lockmyphone_extended.core.perm.PermissionNeeds
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PairedDevices
import com.fabiantorrestech.lockmyphone_extended.data.db.BtDetection
import com.fabiantorrestech.lockmyphone_extended.ui.BeaconModeUiState
import com.fabiantorrestech.lockmyphone_extended.core.service.BeaconMode
import com.fabiantorrestech.lockmyphone_extended.ui.CalendarEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.WakeEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.WakeSetupFlow
import com.fabiantorrestech.lockmyphone_extended.ui.sleepPlaceOwners
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetwork
import com.fabiantorrestech.lockmyphone_extended.ui.BackupActions
import com.fabiantorrestech.lockmyphone_extended.ui.BackupUiState
import com.fabiantorrestech.lockmyphone_extended.ui.RestoreConfirmDialog
import com.fabiantorrestech.lockmyphone_extended.data.backup.BackupContents
import com.fabiantorrestech.lockmyphone_extended.data.backup.RestoreWarnings
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarWindow
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.DeviceCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.BeaconName
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconTiming
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationSlot
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal
import com.fabiantorrestech.lockmyphone_extended.trigger.location.ConnectedWifi
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.ui.ReorderActions
import com.fabiantorrestech.lockmyphone_extended.ui.ToggleActions
import com.fabiantorrestech.lockmyphone_extended.ui.AutomationEditorExtras
import com.fabiantorrestech.lockmyphone_extended.ui.WifiTools
import com.fabiantorrestech.lockmyphone_extended.ui.ScheduleEditorScreen
import com.fabiantorrestech.lockmyphone_extended.ui.SetupActions
import com.fabiantorrestech.lockmyphone_extended.ui.SetupChecklist
import com.fabiantorrestech.lockmyphone_extended.ui.SetupStatus
import com.fabiantorrestech.lockmyphone_extended.ui.StartLockActions
import com.fabiantorrestech.lockmyphone_extended.ui.formatDurationLabel
import com.fabiantorrestech.lockmyphone_extended.ui.theme.LockMyPhoneExtendedTheme
import kotlinx.coroutines.launch

/**
 * One Test now dialog: what's tested, the lock a test lock would use (null = Quick lock), the
 * report once it's ready, and a live line (the NFC tag just held to the phone).
 */
private data class TestRun(
    val name: String,
    val profileId: Long?,
    val report: TestReport? = null,
    val tagLine: TestLine? = null,
    val id: Long = System.nanoTime(),
)

/** How long Test now's lock lasts. */
private const val TEST_LOCK_MS = 30_000L

/** The screens reachable while no lock is active. */
private enum class Screen {
    HOME, DEVICE, DIAGNOSTICS, PROFILE, PROFILE_APPS, SCHEDULE, QUICK_APPS, ALARM_TRIGGER, ALARM_APP, DEFAULTS, DEFAULT_APPS,
    LOCATION, CALENDAR, PERMISSIONS, NFC_TAG, WAKE,
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

    /** The backup folder: a document tree the app keeps access to. */
    private val backupFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            container.settingsStore.setBackupFolder(uri.toString())
        }

    /** Auto backups' own folder (optional; the backup folder is used otherwise). */
    private val autoBackupFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            container.settingsStore.setAutoBackupFolder(uri.toString())
        }

    /** A backup file to restore; read here, then confirmed in a dialog. */
    private val pendingRestore = mutableStateOf<Pair<BackupContents, RestoreWarnings>?>(null)

    private val restoreFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching { container.backupManager.readBackup(uri) }
                    .onSuccess { contents -> pendingRestore.value = contents to container.backupManager.warningsFor(contents) }
                    .onFailure { Toast.makeText(this@MainActivity, getString(R.string.backup_read_failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }

    private val bluetoothAdvertiseLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { BeaconMode.sync(this) }

    private val bluetoothConnectLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onLocationPermissionChanged() }

    private val bluetoothScanLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onLocationPermissionChanged() }

    private val calendarPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshSetupStatus()
            lifecycleScope.launch { container.calendarTriggerManager.sync() }
        }

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
            onGrantCalendar = { calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
            onGrantBluetoothScan = ::requestBluetoothScan,
            onGrantBluetoothConnect = ::requestBluetoothConnect,
            onTurnOnNfc = ::openNfcSettings,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The UI is only reachable after the user unlocked, so the default profile can be seeded
        // from the older settings now.
        lifecycleScope.launch {
            container.ensureDefaultProfile()
            container.seedCameraOnce()
        }
        readRequestedTab(intent)
        setContent {
            val themeMode by container.settingsStore.themeMode.collectAsState()
            val lockState by container.lockController.state.collectAsState()
            // The lock countdown is always OLED black, like the allowed-apps picker.
            LockMyPhoneExtendedTheme(if (lockState is LockState.Locked) ThemeMode.OLED else themeMode) {
                val onboardingDone by container.settingsStore.onboardingDone.collectAsState()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val contentModifier = Modifier.padding(innerPadding)
                    val locked = lockState
                    when {
                        locked is LockState.Locked -> {
                            val gateOpen by container.lockController.gateActive.collectAsState()
                            if (gateOpen) NfcReader(::onGateTag)
                            val pickerStyle by container.settingsStore.pickerStyle.collectAsState()
                            HideStatusBar(pickerStyle.hideStatusBar)
                            LockedScreen(
                                session = locked.session,
                                pickerStyle = pickerStyle,
                                pickerFont = rememberFontFamily(pickerStyle.font, container.fontLibrary),
                                passButtonsEnabled = LockAccessibilityService.isRunning,
                                onOpenAllowedApps = { startActivity(PassActivity.pickerIntent(this)) },
                                onOpenPhone = { startActivity(PassActivity.dialIntent(this)) },
                                modifier = contentModifier,
                                choiceDeadlineMs = container.lockController.choiceDeadlineMs.collectAsState().value,
                                nfcGate = gateOpen,
                                refresh = if (container.notifications.isRefreshable(locked.session)) {
                                    container.placeRefresher.state.collectAsState().value.forLock(locked.session.startedAtMs)
                                } else {
                                    null
                                },
                                onRefresh = { container.appScope.launch { container.placeRefresher.refresh() } },
                            )
                        }
                        !onboardingDone -> OnboardingScreen(
                            deviceProfilePicker = { DeviceProfileSection() },
                            setupChecklist = { SetupChecklist(setupStatus.value, setupActions) },
                            onFinish = { container.settingsStore.setOnboardingDone(true) },
                            modifier = contentModifier,
                        )
                        else -> IdleContent(contentModifier)
                    }
                }
                tagChooser.value?.let { choose ->
                    LockChooserDialog(
                        tagName = choose.tag.name,
                        choices = choose.choices,
                        onPick = { choice ->
                            tagChooser.value = null
                            container.appScope.launch {
                                val message = container.nfcTagHandler.startLock(choose.tag, choice.lockId)
                                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDismiss = { tagChooser.value = null },
                    )
                }
            }
        }
        // Android opens this screen instead of the tag handler when it doesn't route a written tag
        // there; act on the tag anyway. Once only, not again after a rotation.
        if (savedInstanceState == null) handleTagLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readRequestedTab(intent)
        handleTagLaunch(intent)
    }

    /** "Start which lock?" after tapping a tag linked to several locks. */
    private val tagChooser = mutableStateOf<TapResult.ChooseLock?>(null)

    /** A tag that Android delivered to this screen (see onCreate). Like [NfcTagActivity], it can't end a lock. */
    private fun handleTagLaunch(intent: Intent?) {
        val tap = NfcTagActivity.tapFrom(intent) ?: return
        intent?.removeExtra(NfcAdapter.EXTRA_ID)
        container.appScope.launch { showTapResult(container.nfcTagHandler.onTagTapped(tap.uid, tap.code, readInApp = false)) }
    }

    private fun showTapResult(result: TapResult) {
        when (result) {
            is TapResult.Message -> Toast.makeText(applicationContext, result.text, Toast.LENGTH_SHORT).show()
            is TapResult.ChooseLock -> tagChooser.value = result
        }
    }

    private fun readRequestedTab(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_OPEN_TAB) == TAB_AUTOMATIONS) requestedTab.value = HomeTab.AUTOMATIONS
    }

    /**
     * The idle screens' state: which screen is open and the editors' drafts. Kept outside the
     * composition, so a lock starting while an editor is open doesn't discard unsaved changes.
     */
    private class IdleState {
        val screen = mutableStateOf(Screen.HOME)
        val profileDraft = mutableStateOf<LockProfile?>(null)
        val profileError = mutableStateOf<String?>(null)
        val scheduleDraft = mutableStateOf<LockSchedule?>(null)
        val alarmDraft = mutableStateOf<AlarmTrigger?>(null)
        val locationDraft = mutableStateOf<LocationTrigger?>(null)
        val fixRequest = mutableStateOf<FixRequest>(FixRequest.Idle)
        val currentWifi = mutableStateOf<ConnectedWifi?>(null)
        val slotDrafts = mutableStateOf<List<LocationSlot>>(emptyList())
        val calendarDraft = mutableStateOf<CalendarTrigger?>(null)
        val wakeDraft = mutableStateOf<WakeTrigger?>(null)

        /** The Morning lock setup is open over its editor (a new one starts there). */
        val wakeSetup = mutableStateOf(false)
        val nfcDraft = mutableStateOf<NfcTag?>(null)
        val tagScan = mutableStateOf<TagScanMode?>(null)
        val saveAfterWrite = mutableStateOf(false)
        val deviceCalendars = mutableStateOf<List<DeviceCalendar>>(emptyList())
        val nextCalendarMatch = mutableStateOf<CalendarWindow?>(null)
        val defaultsDraft = mutableStateOf<LockProfile?>(null)
        val homeTab = mutableStateOf(HomeTab.QUICK_LOCK)
    }

    private val idle = IdleState()

    @Composable
    private fun IdleContent(modifier: Modifier) {
        var screen by idle.screen
        // Drafts live in [idle] so they survive moving between an editor and the app list, and a
        // lock (e.g. Test now's 30 s test lock) started while an editor is open.
        var profileDraft by idle.profileDraft
        var profileError by idle.profileError
        var scheduleDraft by idle.scheduleDraft
        var alarmDraft by idle.alarmDraft
        var locationDraft by idle.locationDraft
        var fixRequest by idle.fixRequest
        var currentWifi by idle.currentWifi
        var slotDrafts by idle.slotDrafts
        var calendarDraft by idle.calendarDraft
        var wakeDraft by idle.wakeDraft
        var wakeSetup by idle.wakeSetup
        // "I'm in bed now – use here" is finding the Wi-Fi and location.
        var usingHere by remember { mutableStateOf(false) }
        var nfcDraft by idle.nfcDraft
        var tagScan by idle.tagScan
        // Save on a tag that isn't written yet writes it first, then saves.
        var saveAfterWrite by idle.saveAfterWrite
        var deviceCalendars by idle.deviceCalendars
        var nextCalendarMatch by idle.nextCalendarMatch
        // The Lock defaults page edits a profile-shaped draft (no name, never saved as a profile).
        var defaultsDraft by idle.defaultsDraft
        // Kept here (not in HomeScreen) so the tab survives a trip into an editor.
        var homeTab by idle.homeTab

        val profiles by container.database.profileDao().observeAll().collectAsState(initial = emptyList())
        val schedules by container.database.scheduleDao().observeAll().collectAsState(initial = emptyList())
        val alarmTriggers by container.database.alarmTriggerDao().observeAll().collectAsState(initial = emptyList())
        val locations by container.database.locationTriggerDao().observeAll().collectAsState(initial = emptyList())
        val locationEngine by container.settingsStore.locationEngine.collectAsState()
        val locationManager = container.locationTriggerManager
        val calendarManager = container.calendarTriggerManager
        val calendarTriggers by container.database.calendarTriggerDao().observeAll().collectAsState(initial = emptyList())
        val nfcTags by container.database.nfcTagDao().observeAll().collectAsState(initial = emptyList())
        val wakeTriggers by container.database.wakeTriggerDao().observeAll().collectAsState(initial = emptyList())
        val beaconNameRows by container.database.beaconNameDao().observeAll().collectAsState(initial = emptyList())
        val beaconNames = remember(beaconNameRows) { beaconNameRows.associate { it.key to it.name } }
        val allSlots by container.database.locationSlotDao().observeAll().collectAsState(initial = emptyList())
        // Schedules and time-slot places: the commitments whose clashes can be predicted (ADR 0005).
        val commitments = remember(schedules, locations, allSlots, profiles) { timedCommitments(schedules, locations, allSlots, profiles) }
        var calendarClashes by remember { mutableStateOf<Map<Long, CalendarClash>>(emptyMap()) }
        var calendarDraftClash by remember { mutableStateOf<String?>(null) }
        val clashWarnings = remember(commitments, calendarClashes) {
            ClashWarnings(
                schedules = schedules.mapNotNull { schedule ->
                    clashText(commitments, scheduleKey(schedule.id))?.let { schedule.id to it }
                }.toMap(),
                places = locations.mapNotNull { place -> clashText(commitments, placeKey(place.id))?.let { place.id to it } }.toMap(),
                calendars = calendarClashes.mapValues { formatCalendarClash(this, it.value) },
            )
        }
        val nfcGateSeconds by container.settingsStore.nfcGateSeconds.collectAsState()
        val calendarSkipDeclined by container.settingsStore.calendarSkipDeclined.collectAsState()
        val calendarSkipFree by container.settingsStore.calendarSkipFree.collectAsState()
        val backupFolder by container.settingsStore.backupFolderUri.collectAsState()
        val autoBackup by container.settingsStore.autoBackupEnabled.collectAsState()
        val autoBackupFolder by container.settingsStore.autoBackupFolderUri.collectAsState()
        val beaconModeEnabled by container.settingsStore.beaconModeEnabled.collectAsState()
        val beaconModeStart by container.settingsStore.beaconModeStartMinutes.collectAsState()
        val beaconModeEnd by container.settingsStore.beaconModeEndMinutes.collectAsState()
        val lastBackupAt by container.settingsStore.lastBackupAtMs.collectAsState()
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
        val quickHidden by container.settingsStore.quickHiddenPackages.collectAsState()
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
            nfcDraft = null
            wakeDraft = null
            wakeSetup = false
            tagScan = null
            saveAfterWrite = false
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

        // The automation whose editor is open can't start its lock until you save, discard or leave the app.
        val editingKey: AutomationKey? = when (screen) {
            Screen.SCHEDULE -> scheduleDraft?.id?.takeIf { it != 0L }?.let { AutomationKey(LockKind.SCHEDULE, it) }
            Screen.ALARM_TRIGGER, Screen.ALARM_APP -> alarmDraft?.id?.takeIf { it != 0L }?.let { AutomationKey(LockKind.ALARM, it) }
            Screen.LOCATION -> locationDraft?.id?.takeIf { it != 0L }?.let { AutomationKey(LockKind.PLACE, it) }
            Screen.CALENDAR -> calendarDraft?.id?.takeIf { it != 0L }?.let { AutomationKey(LockKind.CALENDAR, it) }
            Screen.NFC_TAG -> nfcDraft?.id?.takeIf { it != 0L }?.let { AutomationKey(LockKind.NFC, it) }
            Screen.WAKE -> wakeDraft?.id?.takeIf { it != 0L }?.let { AutomationKey(LockKind.WAKE, it) }
            else -> null
        }
        DisposableEffect(editingKey) {
            editingAutomation = editingKey
            if (editingKey != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                container.automationHolds.beginEdit(editingKey)
            }
            onDispose {
                editingAutomation = null
                container.automationHolds.endEdit()
            }
        }
        val cooldowns by container.automationHolds.cooldowns.collectAsState()
        val beaconPreset by container.settingsStore.beaconPreset.collectAsState()
        val beaconCustomTiming by container.settingsStore.beaconCustomTiming.collectAsState()
        val cooldownOn by container.settingsStore.automationCooldown.collectAsState()
        val holdNote = when {
            editingKey != null -> stringResource(if (cooldownOn) R.string.automation_hold_note else R.string.automation_hold_note_no_wait)
            cooldownOn -> stringResource(R.string.automation_new_note)
            else -> null
        }
        // Test now: the open report (null = closed) and how to run one.
        var testRun by remember { mutableStateOf<TestRun?>(null) }
        val tester = container.automationTester
        val runTest: (name: String, profileId: Long?, build: suspend () -> TestReport) -> Unit = { name, profileId, build ->
            val run = TestRun(name, profileId)
            testRun = run
            scope.launch {
                val report = build()
                // Only if this report's dialog is still the open one.
                if (testRun?.id == run.id) testRun = testRun?.copy(report = report)
            }
        }

        testRun?.let { run ->
            TestNowDialog(
                name = run.name,
                report = run.report,
                extraLine = run.tagLine,
                canStartLock = setupStatus.value.adminActive,
                onStartTestLock = {
                    testRun = null
                    startTestLock(run.profileId, run.name)
                },
                onDismiss = { testRun = null },
            )
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
                cooldowns = cooldowns,
                automationCooldown = cooldownOn,
                onAutomationCooldownChange = container.settingsStore::setAutomationCooldown,
                toggleActions = remember {
                    ToggleActions(
                        onToggleSchedule = { schedule, on ->
                            scope.launch {
                                container.database.scheduleDao().update(schedule.copy(enabled = on))
                                if (on) onAutomationChanged(AutomationKey(LockKind.SCHEDULE, schedule.id))
                                container.scheduleScheduler.sync()
                            }
                        },
                        onToggleAlarmTrigger = { trigger, on ->
                            scope.launch {
                                container.database.alarmTriggerDao().update(trigger.copy(enabled = on))
                                if (on) onAutomationChanged(AutomationKey(LockKind.ALARM, trigger.id))
                                container.alarmHealthChecker.check()
                            }
                        },
                        onToggleLocation = { place, on ->
                            scope.launch {
                                container.database.locationTriggerDao().update(place.copy(enabled = on))
                                if (on) {
                                    locationManager.onTriggerSaved(place.id)
                                    onAutomationChanged(AutomationKey(LockKind.PLACE, place.id))
                                } else {
                                    locationManager.sync()
                                }
                            }
                        },
                        onToggleCalendar = { trigger, on ->
                            scope.launch {
                                container.database.calendarTriggerDao().update(trigger.copy(enabled = on))
                                if (on) onAutomationChanged(AutomationKey(LockKind.CALENDAR, trigger.id))
                                calendarManager.sync()
                            }
                        },
                        onToggleNfcTag = { tag, on ->
                            scope.launch {
                                container.database.nfcTagDao().update(tag.copy(enabled = on))
                                if (on) onAutomationChanged(AutomationKey(LockKind.NFC, tag.id))
                            }
                        },
                        onToggleWake = { trigger, on ->
                            scope.launch {
                                container.database.wakeTriggerDao().update(trigger.copy(enabled = on))
                                if (on) onAutomationChanged(AutomationKey(LockKind.WAKE, trigger.id))
                            }
                        },
                    )
                },
                reorderActions = remember {
                    ReorderActions(
                        onReorderProfiles = { list -> scope.launch { container.database.profileDao().reorder(list.map { it.id }) } },
                        onReorderSchedules = { list -> scope.launch { container.database.scheduleDao().reorder(list.map { it.id }) } },
                        onReorderTriggers = { list -> scope.launch { container.database.alarmTriggerDao().reorder(list.map { it.id }) } },
                        onReorderLocations = { list -> scope.launch { container.database.locationTriggerDao().reorder(list.map { it.id }) } },
                        onReorderCalendars = { list -> scope.launch { container.database.calendarTriggerDao().reorder(list.map { it.id }) } },
                        onReorderNfcTags = { list -> scope.launch { container.database.nfcTagDao().reorder(list.map { it.id }) } },
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
                calendarTriggers = calendarTriggers,
                clashes = clashWarnings,
                onEditCalendar = { existing ->
                    val store = container.settingsStore
                    calendarDraft = existing ?: CalendarTrigger(
                        name = "",
                        skipDeclined = store.calendarSkipDeclined.value,
                        skipFree = store.calendarSkipFree.value,
                    )
                    nextCalendarMatch = null
                    scope.launch { deviceCalendars = withContext(Dispatchers.IO) { calendarManager.reader.calendars() } }
                    screen = Screen.CALENDAR
                },
                nfcTags = nfcTags,
                wakeTriggers = wakeTriggers,
                onEditWake = { existing ->
                    wakeDraft = existing ?: WakeTrigger(
                        name = getString(R.string.wake_default_name),
                        alarmPackages = firstAlarmApps.map { it.packageName }.toSet(),
                    )
                    // A new Morning lock starts with the guided setup.
                    wakeSetup = existing == null
                    screen = Screen.WAKE
                },
                onEditNfcTag = { existing ->
                    nfcDraft = existing ?: NfcTag(name = "", uid = "")
                    tagScan = null
                    screen = Screen.NFC_TAG
                },
                nfcGateSeconds = nfcGateSeconds,
                onNfcGateSecondsChange = container.settingsStore::setNfcGateSeconds,
                calendarSkipDeclined = calendarSkipDeclined,
                calendarSkipFree = calendarSkipFree,
                onCalendarDefaultsChange = container.settingsStore::setCalendarDefaults,
                backup = BackupUiState(
                    folderName = backupFolder?.let { remember(it) { container.backupManager.folderName(Uri.parse(it)) } ?: it },
                    autoFolderName = autoBackupFolder?.let { remember(it) { container.backupManager.folderName(Uri.parse(it)) } ?: it },
                    autoBackup = autoBackup,
                    lastBackupAtMs = lastBackupAt,
                ),
                beaconMode = BeaconModeUiState(
                    enabled = beaconModeEnabled,
                    startMinutes = beaconModeStart,
                    endMinutes = beaconModeEnd,
                    key = remember { BeaconMode.key(this) },
                    batteryUnrestricted = setupStatus.value.batteryUnrestricted,
                ),
                onBeaconModeChange = { enabled, start, end ->
                    container.settingsStore.setBeaconMode(enabled, start, end)
                    if (enabled && !BeaconMode.hasPermission(this)) requestBluetoothAdvertise() else BeaconMode.sync(this)
                },
                backupActions = remember {
                    BackupActions(
                        onPickFolder = { backupFolderLauncher.launch(null) },
                        onPickAutoFolder = { autoBackupFolderLauncher.launch(null) },
                        onBackupNow = {
                            scope.launch {
                                container.backupManager.backupNow(auto = false)
                                    .onSuccess { Toast.makeText(this@MainActivity, getString(R.string.backup_done, it), Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(this@MainActivity, getString(R.string.backup_failed, it.message), Toast.LENGTH_LONG).show() }
                            }
                        },
                        onRestore = { restoreFileLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
                        onAutoBackupChange = container.settingsStore::setAutoBackupEnabled,
                    )
                },
                locationEngine = locationEngine,
                playServicesAvailable = remember { locationManager.playServices.isAvailable() },
                onLocationEngineChange = { choice ->
                    container.settingsStore.setLocationEngine(choice)
                    scope.launch { locationManager.sync() }
                },
                beaconPreset = beaconPreset,
                beaconCustomTiming = beaconCustomTiming,
                onBeaconTimingChange = { preset, custom ->
                    container.settingsStore.setBeaconTiming(preset, custom)
                    // The shared background scan may need a different speed.
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
                onOpenPermissions = { screen = Screen.PERMISSIONS },
                onOpenLockDefaults = {
                    defaultsDraft = container.settingsStore.lockDefaults.value.toProfileDraft()
                    screen = Screen.DEFAULTS
                },
                quickLock = LockSettings(
                    durationSec = quickDurationSec,
                    allowedAppCount = (quickApps - quickHidden).size,
                    hiddenAppCount = (quickHidden intersect quickApps).size,
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
                pickerAppearance = PickerAppearance(
                    style = container.settingsStore.pickerStyle.collectAsState().value,
                    userFonts = container.fontLibrary.fonts.collectAsState().value,
                    fontLibrary = container.fontLibrary,
                    onStyleChange = container.settingsStore::setPickerStyle,
                    onAddFont = ::addPickerFont,
                    onRemoveFont = ::removePickerFont,
                ),
                assistantPopupAllowed = container.settingsStore.assistantPopupAllowed.collectAsState().value,
                onAssistantPopupChange = container.settingsStore::setAssistantPopupAllowed,
                homeAction = container.settingsStore.homeAction.collectAsState().value,
                onHomeActionChange = container.settingsStore::setHomeAction,
                unlockIntoAllowedApp = container.settingsStore.unlockIntoAllowedApp.collectAsState().value,
                onUnlockIntoAllowedAppChange = container.settingsStore::setUnlockIntoAllowedApp,
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
            Screen.NFC_TAG -> nfcDraft?.let { draft ->
                val saveTag: (NfcTag) -> Unit = { tag ->
                    scope.launch {
                        val dao = container.database.nfcTagDao()
                        try {
                            val id = if (tag.id == 0L) dao.insertLast(tag) else tag.id.also { dao.update(tag) }
                            onAutomationChanged(AutomationKey(LockKind.NFC, id))
                            goHome()
                        } catch (e: SQLiteConstraintException) {
                            Toast.makeText(this@MainActivity, R.string.nfc_tag_taken_generic, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                // The reader stays on for the whole editor, so a written tag held to the phone
                // while not scanning is ignored instead of starting its lock.
                NfcReader { tag ->
                    testRun?.let { run ->
                        val same = NfcRules.uidHex(tag.id) == draft.uid
                        testRun = run.copy(
                            tagLine = TestLine(
                                if (same) TestMark.PASS else TestMark.FAIL,
                                getString(if (same) R.string.test_nfc_same else R.string.test_nfc_other),
                            ),
                        )
                        return@NfcReader
                    }
                    tagScan?.let { mode ->
                        onEditorTag(tag, mode, draft, nfcTags) { uid, code ->
                            val updated = draft.copy(uid = uid, secret = code)
                            nfcDraft = updated
                            tagScan = null
                            if (saveAfterWrite && mode == TagScanMode.WRITE) {
                                saveAfterWrite = false
                                saveTag(updated)
                            }
                        }
                    }
                }
                NfcTagEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    hasNfc = setupStatus.value.hasNfc,
                    nfcOn = setupStatus.value.nfcOn,
                    scanning = tagScan,
                    savePending = saveAfterWrite,
                    presetsSec = presetsSec,
                    onOpenNfcSettings = ::openNfcSettings,
                    onScan = {
                        tagScan = it
                        if (it == null) saveAfterWrite = false
                    },
                    onDraftChange = { nfcDraft = it },
                    onSave = {
                        if (draft.written || !setupStatus.value.nfcOn) {
                            saveTag(draft)
                        } else {
                            saveAfterWrite = true
                            tagScan = TagScanMode.WRITE
                        }
                    },
                    onSaveWithoutWriting = {
                        tagScan = null
                        saveAfterWrite = false
                        saveTag(draft)
                    },
                    onDelete = {
                        scope.launch {
                            container.database.nfcTagDao().delete(draft)
                            container.automationHolds.forget(AutomationKey(LockKind.NFC, draft.id))
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                    automation = AutomationEditorExtras(holdNote, onTestNow = {
                        val lockIds = draft.linkedLockIds.filter { id -> id == QUICK_LOCK_ID || profiles.any { it.id.toString() == id } }
                        runTest(draft.name, lockIds.firstOrNull()?.toLongOrNull()) {
                            tester.nfc(draft, setupStatus.value.hasNfc, setupStatus.value.nfcOn, lockIds.size)
                        }
                    }),
                )
            }
            Screen.WAKE -> wakeDraft?.let { draft ->
                val saveWake: (Set<Long>) -> Unit = { turnOffAlarmIds ->
                    scope.launch {
                        val dao = container.database.wakeTriggerDao()
                        val id = if (draft.id == 0L) {
                            dao.insertLast(draft)
                        } else {
                            // Keep the day it last locked: it may have changed while the editor was open.
                            draft.id.also { dao.update(draft.copy(lastFiredDay = dao.getById(it)?.lastFiredDay)) }
                        }
                        alarmTriggers.filter { it.id in turnOffAlarmIds }
                            .forEach { container.database.alarmTriggerDao().update(it.copy(enabled = false)) }
                        onAutomationChanged(AutomationKey(LockKind.WAKE, id))
                        goHome()
                    }
                }
                val useHere: () -> Unit = {
                    scope.launch {
                        usingHere = true
                        val wifi = if (locationManager.hasPreciseLocation()) locationManager.connectedWifi() else null
                        val fix = locationManager.currentFix()
                        usingHere = false
                        if (wifi == null && fix == null) {
                            Toast.makeText(this@MainActivity, getString(R.string.wake_use_here_failed), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val name = getString(R.string.wake_use_here_name, wifi?.ssid ?: getString(R.string.wake_use_here_name_fallback))
                        // A sleep place only answers "am I here?"; it never locks by itself.
                        val place = LocationTrigger(
                            name = name,
                            latitude = fix?.latitude ?: 0.0,
                            longitude = fix?.longitude ?: 0.0,
                            mode = LocationMode.CONDITION_ONLY,
                            signals = setOfNotNull(PlaceSignal.GPS.name.takeIf { fix != null }, PlaceSignal.WIFI.name.takeIf { wifi != null }),
                            requireAll = false,
                            wifiNetworks = listOfNotNull(wifi?.let { WifiNetwork(it.ssid, it.bssid, WifiMatch.ROUTER) }),
                        )
                        val id = container.database.locationTriggerDao().insertLast(place)
                        locationManager.onTriggerSaved(id)
                        wakeDraft = wakeDraft?.let { it.copy(sleepPlaceIds = it.sleepPlaceIds + id) }
                        if (locationManager.hasPreciseLocation() && !locationManager.hasBackgroundLocation()) {
                            requestBackgroundLocation()
                        }
                    }
                }
                WakeEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    places = locations,
                    alarmApps = firstAlarmApps,
                    setup = setupStatus.value,
                    setupActions = setupActions,
                    presetsSec = presetsSec,
                    usingHere = usingHere,
                    onUseHere = useHere,
                    alarmTriggers = alarmTriggers,
                    wifiTools = remember {
                        WifiTools(
                            history = { locationManager.wifiHistory.entries() },
                            nearby = { locationManager.nearbyWifi() },
                            startScan = { locationManager.startWifiScan() },
                        )
                    },
                    currentWifi = remember { locationManager.connectedWifi() },
                    onAddWifiSleepPlace = { networks ->
                        scope.launch {
                            val first = networks.first().label
                            val name = getString(R.string.wake_use_here_name, if (networks.size > 1) "$first +${networks.size - 1}" else first)
                            // Wi-Fi only: no coordinates (the column can't hold "none", so 0, 0 unused).
                            val place = LocationTrigger(
                                name = name,
                                latitude = 0.0,
                                longitude = 0.0,
                                mode = LocationMode.CONDITION_ONLY,
                                signals = setOf(PlaceSignal.WIFI.name),
                                requireAll = false,
                                wifiNetworks = networks,
                            )
                            val id = container.database.locationTriggerDao().insertLast(place)
                            locationManager.onTriggerSaved(id)
                            wakeDraft = wakeDraft?.let { it.copy(sleepPlaceIds = it.sleepPlaceIds + id) }
                        }
                    },
                    onDraftChange = { wakeDraft = it },
                    onSave = { saveWake(emptySet()) },
                    onDelete = {
                        scope.launch {
                            container.database.wakeTriggerDao().delete(draft)
                            container.automationHolds.forget(AutomationKey(LockKind.WAKE, draft.id))
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                    automation = AutomationEditorExtras(holdNote, onTestNow = {
                        runTest(draft.name, draft.profileId) {
                            tester.wake(
                                draft,
                                locations,
                                setupStatus.value.accessibilityEnabled,
                                draft.alarmPackages.joinToString { container.appCatalog.label(it) },
                            )
                        }
                    }),
                )
                if (wakeSetup) {
                    WakeSetupFlow(
                        draft = draft,
                        profiles = profiles,
                        places = locations,
                        alarmTriggers = alarmTriggers.filter { it.enabled },
                        setup = setupStatus.value,
                        setupActions = setupActions,
                        presetsSec = presetsSec,
                        usingHere = usingHere,
                        onUseHere = useHere,
                        onDraftChange = { wakeDraft = it },
                        onDone = { turnOff -> saveWake(turnOff) },
                        onCancel = goHome,
                    )
                }
            }
            Screen.CALENDAR -> calendarDraft?.let { draft ->
                CalendarEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    hasPermission = setupStatus.value.calendarGranted,
                    calendars = deviceCalendars,
                    nextMatch = nextCalendarMatch,
                    clash = calendarDraftClash,
                    presetsSec = presetsSec,
                    onRequestPermission = { calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR) },
                    places = locations,
                    onDraftChange = { calendarDraft = it },
                    onSave = {
                        scope.launch {
                            val dao = container.database.calendarTriggerDao()
                            val id = if (draft.id == 0L) dao.insertLast(draft) else draft.id.also { dao.update(draft) }
                            onAutomationChanged(AutomationKey(LockKind.CALENDAR, id))
                            calendarManager.sync()
                            goHome()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.calendarTriggerDao().delete(draft)
                            container.automationHolds.forget(AutomationKey(LockKind.CALENDAR, draft.id))
                            calendarManager.sync()
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                    automation = AutomationEditorExtras(holdNote, onTestNow = {
                        runTest(draft.name, draft.profileId) { tester.calendar(draft, locations) }
                    }),
                )
            }
            Screen.LOCATION -> locationDraft?.let { draft ->
                LocationEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    dayStartMinutes = alarmDayStart,
                    hasLocationPermission = setupStatus.value.preciseLocationGranted,
                    fixRequest = fixRequest,
                    currentWifi = currentWifi,
                    slots = slotDrafts,
                    beaconTools = BeaconTools(
                        hasPermission = setupStatus.value.bluetoothScanGranted,
                        bluetoothOn = locationManager.beaconScanner.isBluetoothOn(),
                        onRequestPermission = ::requestBluetoothScan,
                        discover = { locationManager.beaconScanner.discoverGrouped(BEACON_DISCOVER_MS) },
                        sample = { key -> locationManager.beaconScanner.sample(key, BEACON_CALIBRATE_MS) },
                        names = beaconNames,
                        advertisedPower = { key -> locationManager.beaconScanner.advertisedPower(key, BEACON_POWER_SCAN_MS) },
                        watch = { durationMs, onSightings -> locationManager.beaconScanner.watch(durationMs, onSightings) },
                        defaultPreset = beaconPreset,
                        defaultTiming = BeaconTiming.of(beaconPreset, beaconCustomTiming),
                        status = { place, device -> locationManager.beaconStatus(place, device) },
                        rename = { key, address, name ->
                            scope.launch {
                                // A whole beacon's name lives under its address, so all its formats share it.
                                val nameKey = address?.let(BeaconRules::deviceKey) ?: key
                                val dao = container.database.beaconNameDao()
                                if (name.isBlank()) {
                                    dao.delete(nameKey)
                                    dao.delete(key)
                                } else {
                                    dao.upsert(BeaconName(nameKey, name))
                                    container.database.locationTriggerDao().renameBeacon(key, address, name)
                                }
                            }
                        },
                    ),
                    wifiTools = remember {
                        WifiTools(
                            history = { locationManager.wifiHistory.entries() },
                            nearby = { locationManager.nearbyWifi() },
                            startScan = { locationManager.startWifiScan() },
                        )
                    },
                    pairedDeviceTools = PairedDeviceTools(
                        hasPermission = setupStatus.value.bluetoothConnectGranted,
                        onRequestPermission = ::requestBluetoothConnect,
                        list = { PairedDevices.list(this) },
                        seenNearby = if (setupStatus.value.bluetoothScanGranted) {
                            { locationManager.beaconScanner.seenAddresses(BEACON_DISCOVER_MS) }
                        } else {
                            null
                        },
                    ),
                    onSlotsChange = { slotDrafts = it },
                    slotClash = draft.takeIf { it.enabled && it.mode == LocationMode.TIME_SLOTS }?.let { place ->
                        val self = TimedCommitment(placeKey(place.id), place.name, slotDrafts.map(LocationSlot::asSchedule))
                        ConflictRules.firstClash(self, commitments)?.let { formatClash(this, it) }
                    },
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
                            // A place without GPS keeps no coordinates, but the column can't hold "none".
                            val place = if (draft.latitude.isNaN()) draft.copy(latitude = 0.0, longitude = 0.0) else draft
                            val id = if (place.id == 0L) dao.insertLast(place) else place.id.also { dao.update(place) }
                            val slots = if (draft.mode == LocationMode.TIME_SLOTS) slotDrafts else emptyList()
                            container.database.locationSlotDao().replaceForLocation(id, slots)
                            locationManager.onTriggerSaved(id)
                            onAutomationChanged(AutomationKey(LockKind.PLACE, id))
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
                            container.automationHolds.forget(AutomationKey(LockKind.PLACE, draft.id))
                            locationManager.sync()
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                    automation = AutomationEditorExtras(holdNote, onTestNow = {
                        runTest(draft.name, draft.profileId) { tester.place(draft, slotDrafts) }
                    }),
                    sleepPlaceOf = sleepPlaceOwners(wakeTriggers)[draft.id].orEmpty(),
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
                            onAutomationChanged(AutomationKey(LockKind.ALARM, id))
                            // A saved (or re-learned) trigger starts with a clean bill of health.
                            container.alarmTriggerHealth.onTriggerSaved(id)
                            container.alarmHealthChecker.check()
                            goHome()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.alarmTriggerDao().delete(draft)
                            container.automationHolds.forget(AutomationKey(LockKind.ALARM, draft.id))
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                    automation = AutomationEditorExtras(holdNote, onTestNow = {
                        runTest(draft.name, draft.profileId) { tester.alarm(draft, setupStatus.value.notificationAccessGranted) }
                    }),
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
                hidden = quickHidden,
                onSelectionChange = { apps, hidden ->
                    container.settingsStore.setAllowedPackages(apps)
                    container.settingsStore.setQuickHiddenPackages(hidden)
                },
                onBack = goHome,
                modifier = modifier,
            )
            Screen.PROFILE -> profileDraft?.let { draft ->
                ProfileEditorScreen(
                    draft = draft,
                    presetsSec = presetsSec,
                    nfcTags = nfcTags,
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
                    nfcTags = nfcTags,
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
                    hidden = draft.hiddenPackages,
                    onSelectionChange = { apps, hidden ->
                        defaultsDraft = draft.copy(allowedPackages = apps, hiddenPackages = hidden)
                    },
                    onBack = { screen = Screen.DEFAULTS },
                    modifier = modifier,
                )
            }
            Screen.PROFILE_APPS -> profileDraft?.let { draft ->
                AllowedAppsScreen(
                    loadApps = loadApps,
                    iconProvider = container.appCatalog,
                    selected = draft.allowedPackages,
                    hidden = draft.hiddenPackages,
                    onSelectionChange = { apps, hidden ->
                        profileDraft = draft.copy(allowedPackages = apps, hiddenPackages = hidden)
                    },
                    onBack = { screen = Screen.PROFILE },
                    modifier = modifier,
                )
            }
            Screen.SCHEDULE -> scheduleDraft?.let { draft ->
                ScheduleEditorScreen(
                    draft = draft,
                    profiles = profiles,
                    places = locations,
                    onDraftChange = { scheduleDraft = it },
                    clash = draft.takeIf { it.enabled }?.let { schedule ->
                        ConflictRules.firstClash(TimedCommitment(scheduleKey(schedule.id), "", listOf(schedule)), commitments)
                            ?.let { formatClash(this, it) }
                    },
                    onSave = {
                        scope.launch {
                            val dao = container.database.scheduleDao()
                            val id = if (draft.id == 0L) dao.insertLast(draft) else draft.id.also { dao.update(draft) }
                            onAutomationChanged(AutomationKey(LockKind.SCHEDULE, id))
                            container.scheduleScheduler.sync()
                            goHome()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            container.database.scheduleDao().delete(draft)
                            container.automationHolds.forget(AutomationKey(LockKind.SCHEDULE, draft.id))
                            container.scheduleScheduler.sync()
                            goHome()
                        }
                    },
                    onBack = goHome,
                    modifier = modifier,
                    automation = AutomationEditorExtras(holdNote, onTestNow = {
                        val profileName = profiles.firstOrNull { it.id == draft.profileId }?.name.orEmpty()
                        runTest(profileName, draft.profileId) { tester.schedule(draft, locations) }
                    }),
                )
            }
            Screen.DEVICE -> {
                BackHandler(onBack = goHome)
                Column(modifier.padding(16.dp)) {
                    Text(stringResource(R.string.device_title), style = MaterialTheme.typography.headlineSmall)
                    DeviceProfileSection(Modifier.padding(top = 16.dp))
                }
            }
            Screen.PERMISSIONS -> PermissionsScreen(
                rows = permissionRows(
                    status = setupStatus.value,
                    profiles = profiles,
                    alarmTriggers = alarmTriggers,
                    places = locations,
                    calendarTriggers = calendarTriggers,
                    nfcTags = nfcTags,
                    quickUsesDnd = quickDnd || quickGrayscale,
                    beaconMode = beaconModeEnabled,
                ),
                actions = permissionActions,
                onBack = goHome,
                modifier = modifier,
            )
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

        pendingRestore.value?.let { (contents, warnings) ->
            RestoreConfirmDialog(
                contents = contents,
                warnings = warnings,
                onConfirm = {
                    pendingRestore.value = null
                    scope.launch {
                        runCatching { container.backupManager.restore(contents) }
                            .onSuccess {
                                refreshSetupStatus()
                                Toast.makeText(this@MainActivity, R.string.backup_restored, Toast.LENGTH_LONG).show()
                            }
                            .onFailure { Toast.makeText(this@MainActivity, getString(R.string.backup_restore_failed, it.message), Toast.LENGTH_LONG).show() }
                    }
                },
                onDismiss = { pendingRestore.value = null },
            )
        }

        // Calendar clashes need the phone's events, so they're read in the background.
        LaunchedEffect(calendarTriggers, commitments, setupStatus.value.calendarGranted) {
            calendarClashes = calendarManager.clashes(calendarTriggers.filter { it.enabled }, calendarTriggers, commitments)
        }

        // The calendar row only matters once a calendar trigger exists.
        LaunchedEffect(calendarTriggers) {
            val exist = calendarTriggers.isNotEmpty()
            if (setupStatus.value.calendarTriggersExist != exist) {
                setupStatus.value = setupStatus.value.copy(calendarTriggersExist = exist)
            }
        }

        // The editor's "Next match" preview, refreshed a moment after the draft changes.
        LaunchedEffect(calendarDraft, setupStatus.value.calendarGranted) {
            val draft = calendarDraft ?: return@LaunchedEffect
            delay(CALENDAR_PREVIEW_DELAY_MS)
            nextCalendarMatch = calendarManager.nextMatch(draft)
            calendarDraftClash = if (draft.enabled) {
                calendarManager.clashes(listOf(draft), calendarTriggers, commitments)[draft.id]
                    ?.let { formatCalendarClash(this@MainActivity, it) }
            } else {
                null
            }
            if (deviceCalendars.isEmpty()) deviceCalendars = withContext(Dispatchers.IO) { calendarManager.reader.calendars() }
        }

        // The location rows only matter once a place uses GPS or Wi-Fi; the Bluetooth row once one uses a beacon.
        LaunchedEffect(locations) {
            // Hearing a paired device nearby is a scan, like a beacon; being connected to it isn't.
            val scansNearby = { place: LocationTrigger -> place.uses(PlaceSignal.BT_DEVICE) && place.btDetection == BtDetection.NEARBY }
            val needBeacon = locations.any { it.uses(PlaceSignal.BEACON) || scansNearby(it) }
            val needLocation = needBeacon || locations.any { it.usesGps || it.uses(PlaceSignal.WIFI) }
            val needConnect = locations.any { it.uses(PlaceSignal.BT_DEVICE) }
            val current = setupStatus.value
            if (current.locationTriggersExist != needLocation || current.beaconPlacesExist != needBeacon || current.btDevicePlacesExist != needConnect) {
                setupStatus.value = current.copy(
                    locationTriggersExist = needLocation,
                    beaconPlacesExist = needBeacon,
                    btDevicePlacesExist = needConnect,
                )
            }
        }

        // The "NFC on" row only matters once a tag exists.
        LaunchedEffect(nfcTags) {
            val exist = nfcTags.isNotEmpty()
            if (setupStatus.value.nfcTagsExist != exist) setupStatus.value = setupStatus.value.copy(nfcTagsExist = exist)
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
                Screen.CALENDAR -> calendarDraft == null
                Screen.NFC_TAG -> nfcDraft == null
                Screen.WAKE -> wakeDraft == null
                else -> false
            }
            if (missingDraft) screen = Screen.HOME
        }
    }

    /** Enabled schedules and "during time slots" places, as commitments for clash warnings. */
    private fun timedCommitments(
        schedules: List<LockSchedule>,
        places: List<LocationTrigger>,
        slots: List<LocationSlot>,
        profiles: List<LockProfile>,
    ): List<TimedCommitment> {
        val profileNames = profiles.associate { it.id to it.name }
        val scheduleCommitments = schedules.filter { it.enabled }
            .map { TimedCommitment(scheduleKey(it.id), profileNames[it.profileId].orEmpty(), listOf(it)) }
        val placeCommitments = places.filter { it.enabled && it.mode == LocationMode.TIME_SLOTS }
            .map { place -> TimedCommitment(placeKey(place.id), place.name, slots.filter { it.locationId == place.id }.map(LocationSlot::asSchedule)) }
        return scheduleCommitments + placeCommitments
    }

    /** The warning for the commitment with [key], if it clashes with another. */
    private fun clashText(commitments: List<TimedCommitment>, key: String): String? {
        val self = commitments.firstOrNull { it.key == key } ?: return null
        return ConflictRules.firstClash(self, commitments)?.let { formatClash(this, it) }
    }

    private fun scheduleKey(id: Long) = "schedule:$id"

    private fun placeKey(id: Long) = "place:$id"

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
        applyNfcReader()
    }

    override fun onPause() {
        super.onPause()
        NfcTagIo.setReader(this, null)
    }

    /**
     * [key] was saved or switched on: the editor's hold ends and its one-minute wait starts (or,
     * with the wait off in Settings, it catches up now). Call before re-syncing its manager, so
     * nothing starts in between.
     */
    private suspend fun onAutomationChanged(key: AutomationKey) {
        container.automationHolds.endEdit(catchUp = false)
        container.automationHolds.startCooldown(key)
    }

    /** The automation whose editor is open. It's held while this screen is in front ([AutomationHolds]). */
    private var editingAutomation: AutomationKey? = null

    override fun onStart() {
        super.onStart()
        editingAutomation?.let(container.automationHolds::beginEdit)
    }

    /** Leaving the app releases the editor's hold: an automation only waits while you're looking at it. */
    override fun onStop() {
        container.automationHolds.endEdit()
        super.onStop()
    }

    /** The screen currently reading NFC tags (the gate, or the tag editor), or null. */
    private var nfcReaderCallback: ((Tag) -> Unit)? = null

    /** Reader mode only works while this activity is in front, so it's re-applied in onResume. */
    private fun applyNfcReader() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) NfcTagIo.setReader(this, nfcReaderCallback)
    }

    /** Keeps the NFC reader on while this is shown; each tag goes to [onTag] on the main thread. */
    @Composable
    private fun NfcReader(onTag: (Tag) -> Unit) {
        val currentOnTag by rememberUpdatedState(onTag)
        DisposableEffect(Unit) {
            nfcReaderCallback = { tag -> runOnUiThread { currentOnTag(tag) } }
            applyNfcReader()
            onDispose {
                nfcReaderCallback = null
                applyNfcReader()
            }
        }
    }

    /** A tag tapped on the NFC gate: it may end the lock or start its early-unlock wait. */
    private fun onGateTag(tag: Tag) {
        val uid = NfcRules.uidHex(tag.id)
        val code = NfcTagIo.readCode(tag)
        container.appScope.launch { showTapResult(container.nfcTagHandler.onTagTapped(uid, code, readInApp = true)) }
    }

    /**
     * A tag held to the phone in the tag editor. Scanning registers its ID (and keeps the code of
     * a tag this app wrote before); writing puts this app's record with the tag's code on it. A
     * tag already written keeps its code, so writing again never breaks it. A tag registered
     * elsewhere, or a different tag than the one being edited, is refused. [onRegistered] gets
     * the tag's ID and code (null if it has none) when it's done.
     */
    private fun onEditorTag(
        tag: Tag,
        mode: TagScanMode,
        draft: NfcTag,
        allTags: List<NfcTag>,
        onRegistered: (uid: String, code: String?) -> Unit,
    ) {
        val uid = NfcRules.uidHex(tag.id)
        Log.i(TAG, "Tag $uid in the editor: ${NfcTagIo.describe(tag)}")
        val owner = allTags.firstOrNull { it.uid == uid && it.id != draft.id }
        when {
            owner != null -> Toast.makeText(this, getString(R.string.nfc_tag_taken, owner.name), Toast.LENGTH_LONG).show()
            mode == TagScanMode.SCAN -> onRegistered(uid, NfcTagIo.readCode(tag))
            draft.uid.isNotEmpty() && draft.uid != uid ->
                Toast.makeText(this, R.string.nfc_write_other_tag, Toast.LENGTH_LONG).show()
            else -> lifecycleScope.launch {
                val code = draft.secret?.takeIf { draft.uid == uid } ?: NfcTagIo.readCode(tag) ?: NfcTagIo.newCode()
                val result = withContext(Dispatchers.IO) { NfcTagIo.write(tag, code) }
                Toast.makeText(this@MainActivity, writeResultText(result), Toast.LENGTH_LONG).show()
                if (result == TagWriteResult.WRITTEN) onRegistered(uid, code)
            }
        }
    }

    private fun writeResultText(result: TagWriteResult): Int = when (result) {
        TagWriteResult.WRITTEN -> R.string.nfc_write_done
        TagWriteResult.READ_ONLY -> R.string.nfc_write_read_only
        TagWriteResult.TOO_SMALL -> R.string.nfc_write_too_small
        TagWriteResult.NOT_WRITABLE -> R.string.nfc_write_not_writable
        TagWriteResult.FAILED -> R.string.nfc_write_failed
    }

    private fun openNfcSettings() {
        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
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
            calendarTriggersExist = setupStatus.value.calendarTriggersExist,
            beaconPlacesExist = setupStatus.value.beaconPlacesExist,
            calendarGranted = container.calendarTriggerManager.reader.hasPermission(),
            bluetoothScanGranted = container.locationTriggerManager.beaconScanner.hasPermission(),
            btDevicePlacesExist = setupStatus.value.btDevicePlacesExist,
            bluetoothConnectGranted = PairedDevices.hasPermission(this),
            nearbyScanGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || isGranted(Manifest.permission.BLUETOOTH_SCAN),
            bluetoothAdvertiseGranted = BeaconMode.hasPermission(this),
            nfcTagsExist = setupStatus.value.nfcTagsExist,
            hasNfc = NfcTagIo.hasNfc(this),
            nfcOn = NfcTagIo.isOn(this),
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

    private fun requestBluetoothAdvertise() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothAdvertiseLauncher.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            BeaconMode.sync(this)
        }
    }

    /** Paired devices need "Nearby devices" (connect) on Android 12+; older versions need nothing. */
    private fun requestBluetoothConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothConnectLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            onLocationPermissionChanged()
        }
    }

    /** Beacons need "Nearby devices" (Android 12+); older versions only need location. */
    private fun requestBluetoothScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothScanLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            requestLocationPermission()
        }
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

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private val permissionActions by lazy {
        PermissionActions(
            onGrant = ::grantPermission,
            onManage = ::managePermission,
            onRevoke = ::revokePermission,
        )
    }

    /** Settings → Permissions: each permission, what uses it, and whether the app can revoke it itself. */
    private fun permissionRows(
        status: SetupStatus,
        profiles: List<LockProfile>,
        alarmTriggers: List<AlarmTrigger>,
        places: List<LocationTrigger>,
        calendarTriggers: List<CalendarTrigger>,
        nfcTags: List<NfcTag>,
        quickUsesDnd: Boolean,
        beaconMode: Boolean,
    ): List<PermissionRow> {
        val granted = status.grantedPermissions()
        val everyLock = listOf(getString(R.string.perm_used_by_every_lock))
        return AppPermission.entries.map { permission ->
            val usedBy = when (permission) {
                in CORE_PERMISSIONS -> everyLock
                AppPermission.NEARBY_ADVERTISE -> if (beaconMode) listOf(getString(R.string.beacon_mode_title)) else emptyList()
                AppPermission.CALENDAR -> calendarTriggers.map { it.name }
                AppPermission.NOTIFICATION_ACCESS -> alarmTriggers.map { it.name }
                AppPermission.NFC_ON -> nfcTags.map { it.name }
                AppPermission.DND_ACCESS ->
                    listOfNotNull(getString(R.string.quick_lock).takeIf { quickUsesDnd }) +
                        profiles.filter { PermissionNeeds.forProfile(it).isNotEmpty() }.map { it.name }
                else -> places.filter { permission in PermissionNeeds.forPlace(it) }.map { it.name }
            }
            PermissionRow(permission, permission in granted, usedBy, canRevoke(permission))
        }
    }

    private fun canRevoke(permission: AppPermission): Boolean = when (permission) {
        AppPermission.DEVICE_ADMIN -> true
        AppPermission.ACCESSIBILITY -> LockAccessibilityService.isRunning
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimePermissions(permission).isNotEmpty()
    }

    /** The Android permissions behind a runtime [AppPermission]; empty for special access. */
    private fun runtimePermissions(permission: AppPermission): List<String> = when (permission) {
        AppPermission.NOTIFICATIONS -> listOf(Manifest.permission.POST_NOTIFICATIONS)
        // Location without precise location would leave "all the time" behind, so they go together.
        AppPermission.PRECISE_LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        AppPermission.BACKGROUND_LOCATION -> listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        AppPermission.NEARBY_SCAN -> listOf(Manifest.permission.BLUETOOTH_SCAN)
        AppPermission.NEARBY_CONNECT -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
        AppPermission.NEARBY_ADVERTISE -> listOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        AppPermission.CALENDAR -> listOf(Manifest.permission.READ_CALENDAR)
        else -> emptyList()
    }

    private fun grantPermission(permission: AppPermission) {
        when (permission) {
            AppPermission.DEVICE_ADMIN -> setupActions.onEnableAdmin()
            AppPermission.NOTIFICATIONS -> setupActions.onRequestNotifications()
            AppPermission.ACCESSIBILITY -> setupActions.onEnableAccessibility()
            AppPermission.BATTERY -> setupActions.onAllowBackground()
            AppPermission.EXACT_ALARMS -> setupActions.onAllowExactAlarms()
            AppPermission.PRECISE_LOCATION -> setupActions.onGrantLocation()
            AppPermission.BACKGROUND_LOCATION -> setupActions.onGrantBackgroundLocation()
            AppPermission.LOCATION_SERVICES -> setupActions.onEnableLocationServices()
            AppPermission.NEARBY_SCAN -> setupActions.onGrantBluetoothScan()
            AppPermission.NEARBY_CONNECT -> setupActions.onGrantBluetoothConnect()
            AppPermission.NEARBY_ADVERTISE -> requestBluetoothAdvertise()
            AppPermission.CALENDAR -> setupActions.onGrantCalendar()
            AppPermission.NOTIFICATION_ACCESS -> setupActions.onGrantNotificationAccess()
            AppPermission.DND_ACCESS -> setupActions.onGrantDnd()
            AppPermission.NFC_ON -> openNfcSettings()
        }
    }

    /** Opens Android's own page for [permission], falling back to the app's info page. */
    private fun managePermission(permission: AppPermission) {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        val intent = when (permission) {
            AppPermission.DEVICE_ADMIN -> Intent(Settings.ACTION_SECURITY_SETTINGS)
            AppPermission.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            AppPermission.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            AppPermission.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null))
            } else {
                appDetails
            }
            AppPermission.LOCATION_SERVICES -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            AppPermission.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, AlarmWatchListener.component(this).flattenToString())
            AppPermission.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            AppPermission.NFC_ON -> Intent(Settings.ACTION_NFC_SETTINGS)
            // Battery and every runtime permission live on the app's info page.
            else -> appDetails
        }
        runCatching { startActivity(intent) }.onFailure { startActivity(appDetails) }
    }

    /**
     * Takes a permission back. Device admin and accessibility switch off right away. Runtime
     * permissions are revoked by Android once the app's process ends, so the app closes itself;
     * everything that used them shows "Paused" until they're allowed again.
     */
    private fun revokePermission(permission: AppPermission) {
        when (permission) {
            AppPermission.DEVICE_ADMIN -> {
                container.relocker.deactivateAdmin()
                refreshSetupStatus()
            }
            AppPermission.ACCESSIBILITY -> {
                if (!LockAccessibilityService.turnOff()) managePermission(permission)
                refreshSetupStatus()
            }
            else -> {
                val permissions = runtimePermissions(permission)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || permissions.isEmpty()) {
                    managePermission(permission)
                    return
                }
                revokeSelfPermissionsOnKill(permissions)
                Toast.makeText(this, R.string.permission_revoke_closing, Toast.LENGTH_LONG).show()
                finishAndRemoveTask()
                // The revocation applies once the process ends; the app starts again on its own when needed.
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, REVOKE_EXIT_DELAY_MS)
            }
        }
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
            kind = LockKind.MANUAL,
        )
        showStartResult(result)
    }

    /**
     * Test now's 30 s lock: the automation's lock ([profileId], or Quick lock when null), started
     * like a manual one, so any real automation may replace it. An open editor stays as it was.
     */
    private fun startTestLock(profileId: Long?, name: String) {
        lifecycleScope.launch {
            val label = getString(R.string.test_lock_label, name)
            val result = if (profileId == null) {
                container.profileLockStarter.startQuick(TEST_LOCK_MS, label, LockKind.MANUAL)
            } else {
                val profile = container.database.profileDao().getById(profileId) ?: return@launch
                container.profileLockStarter.start(profile, TEST_LOCK_MS, label, LockKind.MANUAL)
            }
            showStartResult(result)
        }
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

    /** Settings → Add font…: copies the file in and selects it, or says it isn't a font. */
    private fun addPickerFont(uri: Uri) {
        lifecycleScope.launch {
            val fileName = container.fontLibrary.add(uri)
            if (fileName == null) {
                Toast.makeText(this@MainActivity, R.string.picker_font_invalid, Toast.LENGTH_LONG).show()
                return@launch
            }
            val store = container.settingsStore
            store.setPickerStyle(store.pickerStyle.value.copy(font = FontChoice.UserFile(fileName)))
            Toast.makeText(this@MainActivity, R.string.picker_font_added, Toast.LENGTH_SHORT).show()
        }
    }

    /** Removes an added font; if it was in use, the picker goes back to the default font. */
    private fun removePickerFont(fileName: String) {
        container.fontLibrary.remove(fileName)
        val store = container.settingsStore
        if (store.pickerStyle.value.font == FontChoice.UserFile(fileName)) {
            store.setPickerStyle(store.pickerStyle.value.copy(font = FontChoice.System(SystemFont.DEFAULT)))
        }
    }

    /** Starts a Quick lock with its saved settings. */
    private fun startQuickLock() {
        val durationMs = container.settingsStore.quickDurationSec.value * 1_000
        val result = container.profileLockStarter.startQuick(
            durationMs = durationMs,
            label = getString(R.string.manual_lock_label, "${getString(R.string.quick_lock)}, ${formatDurationLabel(durationMs)}"),
            kind = LockKind.MANUAL,
        )
        showStartResult(result)
    }

    private fun showStartResult(result: StartResult) {
        when (result) {
            StartResult.AdminNotActive -> Toast.makeText(this, R.string.start_needs_admin, Toast.LENGTH_LONG).show()
            is StartResult.Refused -> Toast.makeText(this, formatAlreadyLocked(this, result.running), Toast.LENGTH_LONG).show()
            // Manual locks have no owner, so they're never held.
            StartResult.Started, is StartResult.Replaced, is StartResult.Held -> Unit
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        private const val TAG = "MainActivity"
        private const val CALENDAR_PREVIEW_DELAY_MS = 400L
        private const val BEACON_DISCOVER_MS = 15_000L
        /** Long enough to catch a beacon's advertised 1 m signal a few times. */
        private const val BEACON_POWER_SCAN_MS = 8_000L

        /** Lets the "closing" toast show before the app ends to apply a revoked permission. */
        private const val REVOKE_EXIT_DELAY_MS = 800L
        private const val BEACON_CALIBRATE_MS = 20_000L
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
