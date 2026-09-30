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
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.ProfileLockStarter
import com.fabiantorrestech.lockmyphone_extended.trigger.nfc.NfcTagHandler
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmLearning
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmTimeStore
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmHealthChecker
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmTriggerHealth
import com.fabiantorrestech.lockmyphone_extended.core.notify.TriggerWarningNotifier
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmTriggerFirer
import com.fabiantorrestech.lockmyphone_extended.trigger.schedule.ScheduleScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppCatalog
import com.fabiantorrestech.lockmyphone_extended.core.diag.ForegroundLog
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndController
import com.fabiantorrestech.lockmyphone_extended.core.enforce.ForegroundPolicy
import com.fabiantorrestech.lockmyphone_extended.core.enforce.Relocker
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockController
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockEndScheduler
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockEngine
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockStateStore
import com.fabiantorrestech.lockmyphone_extended.core.notify.LockNotifications
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemDetector
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfile
import com.fabiantorrestech.lockmyphone_extended.core.oem.OemProfiles
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

    val profileLockStarter = ProfileLockStarter(lockController, appCatalog) { settingsStore }

    val nfcTagHandler = NfcTagHandler(appContext, database, lockController, profileLockStarter) { settingsStore }

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
        ).also { manager ->
            // Arriving at a place can satisfy an "Only while at…" schedule or calendar trigger.
            manager.onPlaceEntered = {
                scheduleScheduler.sync()
                calendarTriggerManager.sync()
            }
        }
    }

    val calendarTriggerManager by lazy {
        CalendarTriggerManager(appContext, database, profileLockStarter) { placeId ->
            locationStateStore.presence(placeId) == Presence.INSIDE
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
    )

    init {
        lockController.onShowLockScreen = {
            // Allowed from the background because the accessibility service is bound by the system.
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        lockController.onLockEndedEarly = {
            appScope.launch { scheduleScheduler.suppressActiveWindow() }
        }
        lockController.nfcGateSeconds = nfcTagHandler::gateSeconds
        nfcTagHandler.watchTags(appScope)
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
