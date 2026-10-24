package com.fabiantorrestech.lockmyphone_extended

import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.fabiantorrestech.lockmyphone_extended.core.dnd.GrayscaleController
import com.fabiantorrestech.lockmyphone_extended.data.backup.BackupManager
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationStateStore
import com.fabiantorrestech.lockmyphone_extended.trigger.location.Presence
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.CalendarTriggerManager
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationTriggerManager
import com.fabiantorrestech.lockmyphone_extended.trigger.location.PlaceRefresher
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.AutomationHolds
import com.fabiantorrestech.lockmyphone_extended.trigger.AutomationKey
import com.fabiantorrestech.lockmyphone_extended.trigger.AutomationTester
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcTagHandler
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmLearning
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmTimeStore
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmHealthChecker
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmTriggerHealth
import com.fabiantorrestech.lockmyphone_extended.core.notify.TriggerWarningNotifier
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmTriggerFirer
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleScheduler
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.SleepPlaceChecker
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmWatchListener
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.WakeSignalStore
import com.fabiantorrestech.lockmyphone_extended.trigger.wake.WakeTriggerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppCatalog
import com.fabiantorrestech.lockmyphone_extended.core.diag.ForegroundLog
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndController
import com.fabiantorrestech.lockmyphone_extended.core.enforce.ForegroundPolicy
import com.fabiantorrestech.lockmyphone_extended.core.enforce.Relocker
import com.fabiantorrestech.lockmyphone_extended.core.lock.GlobalLockOptions
import com.fabiantorrestech.lockmyphone_extended.core.lock.HomeAction
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockKind
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockEndScheduler
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockEngine
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockStateStore
import com.fabiantorrestech.lockmyphone_extended.core.notify.LockNotifications
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemDetector
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfile
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles
import com.fabiantorrestech.lockmyphone_extended.data.FontLibrary
import com.fabiantorrestech.lockmyphone_extended.pass.PassActivity
import com.fabiantorrestech.lockmyphone_extended.data.SettingsStore

/**
 * Manual dependency injection: the app's single object graph.
 * Everything created eagerly here must work in direct boot, i.e. use only device-protected
 * storage. [settingsStore] is lazy for that reason.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val relocker = Relocker(appContext)
    val appCatalog = AppCatalog(appContext)
    val notifications = LockNotifications(appContext, appCatalog)
    private val lockStateStore = LockStateStore(appContext)
    val lockEndScheduler = LockEndScheduler(appContext)
    val dndController = DndController(appContext)
    val grayscaleController = GrayscaleController(appContext)

    /** Credential-protected; only touch it after the user has unlocked since boot. */
    val settingsStore by lazy { SettingsStore(appContext) }

    /** Font files the user added for the allowed-apps picker. */
    val fontLibrary by lazy { FontLibrary(appContext) }

    val oemDetector = OemDetector(appContext)
    val foregroundLog = ForegroundLog()

    /** Lives as long as the process; for receivers' async work and app-wide jobs. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Device-protected (ADR 0003), so it's safe to create eagerly. Room opens the file lazily. */
    val database = AppDatabase.create(appContext)

    val lockController = LockController(
        context = appContext,
        store = lockStateStore,
        engine = LockEngine(),
        relocker = relocker,
        endScheduler = lockEndScheduler,
        notifications = notifications,
        foregroundPolicy = ForegroundPolicy(appContext, oemDetector, ::activeOemProfile),
        foregroundLog = foregroundLog,
        dndController = dndController,
        grayscaleController = grayscaleController,
    )

    /** Keeps an automation from locking while it's edited, and for a minute after it changes. */
    val automationHolds = AutomationHolds(appContext, appScope, cooldownEnabled = { settingsStore.automationCooldown.value })

    val profileLockStarter = ProfileLockStarter(lockController, appCatalog, automationHolds, { settingsStore }, ::globalLockOptions)

    val nfcTagHandler = NfcTagHandler(appContext, database, lockController, profileLockStarter) { settingsStore }

    /** Morning lock's last unlock and charging time. Device-protected, so it's safe to create eagerly. */
    val wakeSignalStore = WakeSignalStore(appContext)

    /** Device-protected, so schedules can check "Only while at…" before the first unlock too. */
    val locationStateStore = LocationStateStore(appContext)

    val scheduleScheduler = ScheduleScheduler(
        context = appContext,
        database = database,
        lockController = lockController,
        lockStarter = profileLockStarter,
        endScheduler = lockEndScheduler,
        isAtPlace = { placeId -> locationStateStore.presence(placeId) == Presence.INSIDE },
    )

    val alarmLearning = AlarmLearning()
    val alarmTimeStore = AlarmTimeStore(appContext)
    val alarmTriggerHealth = AlarmTriggerHealth(appContext)
    val triggerWarningNotifier = TriggerWarningNotifier(appContext)
    val alarmHealthChecker by lazy {
        AlarmHealthChecker(appContext, database, alarmTriggerHealth, alarmTimeStore, triggerWarningNotifier)
    }

    val locationTriggerManager by lazy {
        LocationTriggerManager(
            context = appContext,
            database = database,
            stateStore = locationStateStore,
            lockController = lockController,
            lockStarter = profileLockStarter,
            engineChoice = { settingsStore.locationEngine.value },
            dayStartMinutes = { settingsStore.alarmDayStartMinutes.value },
            defaultBeaconTiming = { settingsStore.defaultBeaconTiming() },
        ).also { manager ->
            // Arriving at a place can satisfy an "Only while at…" schedule or calendar trigger.
            manager.onPlaceEntered = {
                scheduleScheduler.sync()
                calendarTriggerManager.sync()
            }
        }
    }

    /** Test now in the automation editors. */
    val automationTester by lazy {
        AutomationTester(
            context = appContext,
            locationManager = { locationTriggerManager },
            calendarManager = { calendarTriggerManager },
            wakeSignals = wakeSignalStore,
            sleepPlaces = { sleepPlaceChecker },
        ) { settingsStore.alarmDayStartMinutes.value }
    }

    /** Refresh on the lock screen, allowed apps and the notification: re-checks the place that locked. */
    val placeRefresher by lazy { PlaceRefresher(appContext, { locationTriggerManager }, lockController, notifications) }

    val calendarTriggerManager by lazy {
        CalendarTriggerManager(
            context = appContext,
            database = database,
            lockStarter = profileLockStarter,
            isHeld = { triggerId -> automationHolds.isHeld(AutomationKey(LockKind.CALENDAR, triggerId)) },
        ) { placeId ->
            locationStateStore.presence(placeId) == Presence.INSIDE
        }
    }

    /** Whether you're at one of Morning lock's sleep places (Wi-Fi first, then GPS). */
    val sleepPlaceChecker by lazy { SleepPlaceChecker { locationTriggerManager } }

    /** Morning lock: decides on unlocks and dismissed alarms. */
    val wakeTriggerManager by lazy {
        WakeTriggerManager(appContext, database, profileLockStarter, sleepPlaceChecker, appScope) {
            AlarmWatchListener.instance?.isAlarmBusy() == true
        }
    }

    val backupManager by lazy {
        BackupManager(appContext, database, { settingsStore }) {
            // After a restore, re-arm everything that runs in the background.
            scheduleScheduler.sync()
            locationTriggerManager.sync()
            calendarTriggerManager.sync()
            alarmHealthChecker.check()
        }
    }

    val alarmTriggerFirer = AlarmTriggerFirer(
        context = appContext,
        database = database,
        lockStarter = profileLockStarter,
        endScheduler = lockEndScheduler,
        dayStartMinutes = { settingsStore.alarmDayStartMinutes.value },
        // An alarm lock firing stands Morning lock down for the day.
        onFired = { alarmTriggerId -> wakeTriggerManager.onAlarmLockFired(alarmTriggerId) },
    )

    init {
        lockController.onShowLockScreen = {
            // Allowed from the background because the accessibility service is bound by the system.
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        lockController.onRedirectHome = { action ->
            // Allowed from the background because the accessibility service is bound by the system.
            val intent = when (action) {
                HomeAction.ALLOWED_APPS -> PassActivity.redirectIntent(appContext)
                HomeAction.COUNTDOWN -> Intent(appContext, MainActivity::class.java)
                HomeAction.LOCK -> null
            }
            intent?.let {
                appContext.startActivity(
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
        lockController.onLockEndedEarly = { ended ->
            // Only a schedule's own lock suppresses the rest of its window.
            if (ended.source.kind == LockKind.SCHEDULE) appScope.launch { scheduleScheduler.suppressActiveWindow() }
        }
        lockController.nfcGateSeconds = nfcTagHandler::gateSeconds
        // Read from device-protected storage, so the button shows before the first unlock too.
        notifications.isRefreshable = { session ->
            session.source.kind == LockKind.PLACE && session.source.ownerId?.let(locationStateStore::endsOnLeave) == true
        }
        notifications.refreshIntent = { PlaceRefresher.pendingIntent(appContext) }
        // A changed automation's wait is over: start what's still going on (ADR 0005 still applies).
        automationHolds.onHoldEnded = { key ->
            when (key.kind) {
                LockKind.SCHEDULE -> scheduleScheduler.sync()
                LockKind.CALENDAR -> calendarTriggerManager.sync()
                LockKind.PLACE -> locationTriggerManager.catchUp(key.id)
                LockKind.WAKE -> wakeTriggerManager.catchUp(key.id)
                // One-off events (an alarm, a tap, an intent) that came during the wait are dropped.
                else -> Unit
            }
        }
        nfcTagHandler.watchTags(appScope)
    }

    /** An unlock heard by the accessibility service's watcher, for Morning lock. */
    fun onUnlockHeard(previousUnlockMs: Long?, chargedMs: Long, screenOnsMs: List<Long>, nowMs: Long) {
        appScope.launch { wakeTriggerManager.onUnlock(previousUnlockMs, chargedMs, screenOnsMs, nowMs) }
    }

    /**
     * Creates the first profile ("Default") if there is none, seeded from the settings used
     * before profiles existed. Needs the user to have unlocked since boot.
     */
    suspend fun ensureDefaultProfile() {
        val dao = database.profileDao()
        if (dao.count() > 0) return
        val bypass = settingsStore.bypassDefaults.value
        dao.insert(
            LockProfile(
                name = appContext.getString(R.string.profile_default_name),
                defaultDurationSec = DEFAULT_PROFILE_DURATION_SEC,
                allowedPackages = settingsStore.allowedPackages.value,
                bypassEnabled = bypass.enabled,
                bypassWaitMinutes = bypass.waitMinutes,
            )
        )
    }

    /**
     * Once per install (and once after the M24 update): the camera becomes an allowed, hidden app in
     * every profile, Quick lock and Lock defaults, so taking a photo from an allowed app doesn't
     * relock. A list that already allowed the camera keeps it visible. Needs the user to have
     * unlocked since boot.
     */
    suspend fun seedCameraOnce() {
        if (settingsStore.cameraSeeded) return
        val camera = appCatalog.defaultCameraPackage()
        if (camera != null) {
            val dao = database.profileDao()
            dao.getAll().forEach { profile ->
                dao.update(
                    profile.copy(
                        allowedPackages = profile.allowedPackages + camera,
                        hiddenPackages = hideIfNew(profile.allowedPackages, profile.hiddenPackages, camera),
                    )
                )
            }
            val store = settingsStore
            store.setQuickHiddenPackages(hideIfNew(store.allowedPackages.value, store.quickHiddenPackages.value, camera))
            store.setAllowedPackages(store.allowedPackages.value + camera)
            val defaults = store.lockDefaults.value
            store.setLockDefaults(
                defaults.copy(
                    allowedPackages = defaults.allowedPackages + camera,
                    hiddenPackages = hideIfNew(defaults.allowedPackages, defaults.hiddenPackages, camera),
                )
            )
        }
        settingsStore.setCameraSeeded()
    }

    /** [hidden] plus [app], unless [app] was already allowed (then the user chose to see it). */
    private fun hideIfNew(allowed: Set<String>, hidden: Set<String>, app: String): Set<String> =
        if (app in allowed) hidden else hidden + app

    /** The "During a lock" settings for a lock starting now; the defaults before the first unlock after a boot. */
    fun globalLockOptions(): GlobalLockOptions {
        val userUnlocked = appContext.getSystemService(UserManager::class.java).isUserUnlocked
        if (!userUnlocked) return GlobalLockOptions()
        return GlobalLockOptions(
            assistantPopupAllowed = settingsStore.assistantPopupAllowed.value,
            homeAction = settingsStore.homeAction.value,
            unlockIntoAllowedApp = settingsStore.unlockIntoAllowedApp.value,
        )
    }

    /**
     * The profile the user picked, or the detected one. Before the first unlock after a boot the
     * user's choice can't be read, but no pass can be active then anyway.
     */
    fun activeOemProfile(): OemProfile {
        val userUnlocked = appContext.getSystemService(UserManager::class.java).isUserUnlocked
        val chosen = if (userUnlocked) OemProfiles.byId(settingsStore.oemProfileId.value) else null
        return chosen ?: oemDetector.detectedProfile()
    }

    private companion object {
        const val DEFAULT_PROFILE_DURATION_SEC = 30L * 60
    }
}
