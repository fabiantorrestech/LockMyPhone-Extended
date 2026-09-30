package com.fabiantorrestech.lockmyphone_extended.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.NfcUnlock
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode
import com.fabiantorrestech.lockmyphone_extended.data.backup.SettingsSnapshot
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmDayRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationEngineChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** The app's colour theme. OLED is dark with pure-black backgrounds. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

/** Settings that aren't in a backup (the backup itself, and this device's beacon mode); changing them doesn't need a new backup. */
val BACKUP_SETTING_KEYS = setOf(
    "backup_folder_uri", "auto_backup_folder_uri", "auto_backup_enabled", "last_backup_at",
    "beacon_mode_enabled", "beacon_mode_start", "beacon_mode_end", "beacon_mode_id",
)

/** How many one-tap lock lengths the user can keep. */
const val MAX_DURATION_PRESETS = 6

/** Early unlock ("wait N minutes, then the lock ends"). */
data class BypassDefaults(val enabled: Boolean, val waitMinutes: Int)

/**
 * The "Lock defaults" page: a baseline copied into new profiles, and into Quick lock on a fresh
 * install or with "Reset to defaults". It's copied once, not inherited: changing it later doesn't
 * change existing profiles or Quick lock.
 */
data class LockDefaults(
    val durationSec: Long,
    val allowedPackages: Set<String>,
    val bypass: BypassDefaults,
    val dndEnabled: Boolean,
    val dndLevel: DndLevel,
    val grayscale: Boolean,
    val resumeMode: UnlockResumeMode,
    val unlockChoiceSeconds: Int,
    val nfcUnlock: NfcUnlock = NfcUnlock.NONE,
) {
    /** A new, unsaved profile pre-filled with these values. The editor uses it for Defaults too. */
    fun toProfileDraft(name: String = ""): LockProfile = LockProfile(
        name = name,
        defaultDurationSec = durationSec,
        allowedPackages = allowedPackages,
        bypassEnabled = bypass.enabled,
        bypassWaitMinutes = bypass.waitMinutes,
        dndEnabled = dndEnabled,
        dndLevel = dndLevel,
        grayscale = grayscale,
        unlockResumeMode = resumeMode,
        unlockChoiceSeconds = unlockChoiceSeconds,
        nfcUnlockEnabled = nfcUnlock.anyTag,
        nfcUnlockTagIds = nfcUnlock.tagIds,
    )

    companion object {
        fun fromProfile(profile: LockProfile) = LockDefaults(
            durationSec = profile.defaultDurationSec,
            allowedPackages = profile.allowedPackages,
            bypass = BypassDefaults(profile.bypassEnabled, profile.bypassWaitMinutes),
            dndEnabled = profile.dndEnabled,
            dndLevel = profile.dndLevel,
            grayscale = profile.grayscale,
            resumeMode = profile.unlockResumeMode,
            unlockChoiceSeconds = profile.unlockChoiceSeconds,
            nfcUnlock = profile.nfcUnlock,
        )
    }
}

/**
 * User configuration. It lives in normal (credential-protected) storage, so it must not be
 * read in direct boot. Enforcement only uses the snapshot saved in the lock session.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Read first: Quick lock falls back to these for anything it hasn't saved yet.
    private val _lockDefaults = MutableStateFlow(readLockDefaults())
    val lockDefaults: StateFlow<LockDefaults> = _lockDefaults.asStateFlow()

    /** Quick lock's allowed apps (Quick lock = start a lock without picking a profile). */
    private val _allowedPackages = MutableStateFlow(readAllowedPackages())
    val allowedPackages: StateFlow<Set<String>> = _allowedPackages.asStateFlow()

    /** Quick lock's last-used length, prefilled next time. */
    private val _quickDurationSec = MutableStateFlow(prefs.getLong(KEY_QUICK_DURATION_SEC, _lockDefaults.value.durationSec))
    val quickDurationSec: StateFlow<Long> = _quickDurationSec.asStateFlow()

    /** Whether the lock-screen "Allowed apps" picker shows icons. Settings lists always do. */
    private val _showAppIcons = MutableStateFlow(prefs.getBoolean(KEY_SHOW_APP_ICONS, true))
    val showAppIcons: StateFlow<Boolean> = _showAppIcons.asStateFlow()

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, null)
            ?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setShowAppIcons(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_APP_ICONS, show) }
        _showAppIcons.value = show
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
        _themeMode.value = mode
    }

    /** Quick lock's Do Not Disturb switch. */
    private val _quickDndEnabled = MutableStateFlow(prefs.getBoolean(KEY_QUICK_DND, _lockDefaults.value.dndEnabled))
    val quickDndEnabled: StateFlow<Boolean> = _quickDndEnabled.asStateFlow()

    private val _quickDndLevel = MutableStateFlow(readDndLevel(KEY_QUICK_DND_LEVEL) ?: _lockDefaults.value.dndLevel)
    val quickDndLevel: StateFlow<DndLevel> = _quickDndLevel.asStateFlow()

    fun setQuickDndLevel(level: DndLevel) {
        prefs.edit { putString(KEY_QUICK_DND_LEVEL, level.name) }
        _quickDndLevel.value = level
    }

    /** Quick lock's grayscale switch. */
    private val _quickGrayscale = MutableStateFlow(prefs.getBoolean(KEY_QUICK_GRAYSCALE, _lockDefaults.value.grayscale))
    val quickGrayscale: StateFlow<Boolean> = _quickGrayscale.asStateFlow()

    fun setQuickGrayscale(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_QUICK_GRAYSCALE, enabled) }
        _quickGrayscale.value = enabled
    }

    /** Quick lock's "Tags that can end this lock". */
    private val _quickNfcUnlock = MutableStateFlow(readNfcUnlock(KEY_QUICK_NFC_UNLOCK, KEY_QUICK_NFC_TAGS) ?: _lockDefaults.value.nfcUnlock)
    val quickNfcUnlock: StateFlow<NfcUnlock> = _quickNfcUnlock.asStateFlow()

    fun setQuickNfcUnlock(value: NfcUnlock) {
        prefs.edit {
            putBoolean(KEY_QUICK_NFC_UNLOCK, value.anyTag)
            putStringSet(KEY_QUICK_NFC_TAGS, value.tagIds)
        }
        _quickNfcUnlock.value = value
    }

    /** How long the countdown screen waits for an NFC tag after an unlock during a lock. */
    private val _nfcGateSeconds = MutableStateFlow(prefs.getInt(KEY_NFC_GATE_SECONDS, DEFAULT_NFC_GATE_SECONDS))
    val nfcGateSeconds: StateFlow<Int> = _nfcGateSeconds.asStateFlow()

    fun setNfcGateSeconds(seconds: Int) {
        prefs.edit { putInt(KEY_NFC_GATE_SECONDS, seconds) }
        _nfcGateSeconds.value = seconds
    }

    /** One-tap lock lengths (seconds), 0 to [MAX_DURATION_PRESETS], shown above the H:M:S fields. */
    private val _durationPresetsSec = MutableStateFlow(readDurationPresets())
    val durationPresetsSec: StateFlow<List<Long>> = _durationPresetsSec.asStateFlow()

    fun setDurationPresets(presetsSec: List<Long>) {
        val cleaned = presetsSec.filter { it > 0 }.distinct().sorted().take(MAX_DURATION_PRESETS)
        prefs.edit { putString(KEY_DURATION_PRESETS, cleaned.joinToString(",")) }
        _durationPresetsSec.value = cleaned
    }

    /**
     * When a "day" starts for alarm triggers, in minutes past midnight. An alarm before this time
     * counts toward the previous day ("first alarm of the day", weekday and date rules).
     */
    private val _alarmDayStartMinutes = MutableStateFlow(
        prefs.getInt(KEY_ALARM_DAY_START, AlarmDayRules.DEFAULT_DAY_START_MINUTES)
    )
    val alarmDayStartMinutes: StateFlow<Int> = _alarmDayStartMinutes.asStateFlow()

    fun setAlarmDayStartMinutes(minutes: Int) {
        prefs.edit { putInt(KEY_ALARM_DAY_START, minutes) }
        _alarmDayStartMinutes.value = minutes
    }

    /** Calendar triggers: new ones start with these (declined events skipped, "free" ones counted). */
    private val _calendarSkipDeclined = MutableStateFlow(prefs.getBoolean(KEY_CALENDAR_SKIP_DECLINED, true))
    val calendarSkipDeclined: StateFlow<Boolean> = _calendarSkipDeclined.asStateFlow()

    private val _calendarSkipFree = MutableStateFlow(prefs.getBoolean(KEY_CALENDAR_SKIP_FREE, false))
    val calendarSkipFree: StateFlow<Boolean> = _calendarSkipFree.asStateFlow()

    fun setCalendarDefaults(skipDeclined: Boolean, skipFree: Boolean) {
        prefs.edit {
            putBoolean(KEY_CALENDAR_SKIP_DECLINED, skipDeclined)
            putBoolean(KEY_CALENDAR_SKIP_FREE, skipFree)
        }
        _calendarSkipDeclined.value = skipDeclined
        _calendarSkipFree.value = skipFree
    }

    /** Backups: the folder the user picked (a persisted document-tree URI), auto backup, last run. */
    private val _backupFolderUri = MutableStateFlow(prefs.getString(KEY_BACKUP_FOLDER, null))
    val backupFolderUri: StateFlow<String?> = _backupFolderUri.asStateFlow()

    /** Where auto backups go; null = the backup folder. */
    private val _autoBackupFolderUri = MutableStateFlow(prefs.getString(KEY_AUTO_BACKUP_FOLDER, null))
    val autoBackupFolderUri: StateFlow<String?> = _autoBackupFolderUri.asStateFlow()

    fun setAutoBackupFolder(uri: String?) {
        prefs.edit { putString(KEY_AUTO_BACKUP_FOLDER, uri) }
        _autoBackupFolderUri.value = uri
    }

    private val _autoBackupEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BACKUP, false))
    val autoBackupEnabled: StateFlow<Boolean> = _autoBackupEnabled.asStateFlow()

    private val _lastBackupAtMs = MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP_AT, 0L).takeIf { it > 0 })
    val lastBackupAtMs: StateFlow<Long?> = _lastBackupAtMs.asStateFlow()

    fun setBackupFolder(uri: String?) {
        prefs.edit { putString(KEY_BACKUP_FOLDER, uri) }
        _backupFolderUri.value = uri
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_BACKUP, enabled) }
        _autoBackupEnabled.value = enabled
    }

    fun setLastBackupAt(timeMs: Long) {
        prefs.edit { putLong(KEY_LAST_BACKUP_AT, timeMs) }
        _lastBackupAtMs.value = timeMs
    }

    /** Called on every saved setting (for the auto backup). Held weakly by Android: keep a reference. */
    fun addChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** The settings a backup carries (not the stop token, not the backup folder). */
    fun snapshot() = SettingsSnapshot(
        quickDurationSec = quickDurationSec.value,
        quickAllowedPackages = allowedPackages.value,
        quickBypass = bypassDefaults.value,
        quickDndEnabled = quickDndEnabled.value,
        quickDndLevel = quickDndLevel.value,
        quickGrayscale = quickGrayscale.value,
        quickResumeMode = quickResumeMode.value,
        quickChoiceSeconds = quickChoiceSeconds.value,
        quickNfcUnlock = quickNfcUnlock.value,
        lockDefaults = lockDefaults.value,
        durationPresetsSec = durationPresetsSec.value,
        themeMode = themeMode.value,
        showAppIcons = showAppIcons.value,
        alarmDayStartMinutes = alarmDayStartMinutes.value,
        locationEngine = locationEngine.value,
        calendarSkipDeclined = calendarSkipDeclined.value,
        calendarSkipFree = calendarSkipFree.value,
        oemProfileId = oemProfileId.value,
        intentStopEnabled = intentStopEnabled.value,
        nfcGateSeconds = nfcGateSeconds.value,
    )

    fun restore(s: SettingsSnapshot) {
        setQuickDurationSec(s.quickDurationSec)
        setAllowedPackages(s.quickAllowedPackages)
        setBypassDefaults(s.quickBypass)
        setQuickDndEnabled(s.quickDndEnabled)
        setQuickDndLevel(s.quickDndLevel)
        setQuickGrayscale(s.quickGrayscale)
        setQuickResume(s.quickResumeMode, s.quickChoiceSeconds)
        setQuickNfcUnlock(s.quickNfcUnlock)
        setLockDefaults(s.lockDefaults)
        setDurationPresets(s.durationPresetsSec)
        setThemeMode(s.themeMode)
        setShowAppIcons(s.showAppIcons)
        setAlarmDayStartMinutes(s.alarmDayStartMinutes)
        setLocationEngine(s.locationEngine)
        setCalendarDefaults(s.calendarSkipDeclined, s.calendarSkipFree)
        setOemProfileId(s.oemProfileId)
        setIntentStopEnabled(s.intentStopEnabled)
        setNfcGateSeconds(s.nfcGateSeconds)
    }

    /** "Act as a beacon": this device broadcasts a beacon other phones can use as a place. */
    private val _beaconModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_BEACON_MODE, false))
    val beaconModeEnabled: StateFlow<Boolean> = _beaconModeEnabled.asStateFlow()

    /** Broadcast only between these minutes of the day (null = always). */
    private val _beaconModeStartMinutes = MutableStateFlow(prefs.getInt(KEY_BEACON_START, -1).takeIf { it >= 0 })
    val beaconModeStartMinutes: StateFlow<Int?> = _beaconModeStartMinutes.asStateFlow()

    private val _beaconModeEndMinutes = MutableStateFlow(prefs.getInt(KEY_BEACON_END, -1).takeIf { it >= 0 })
    val beaconModeEndMinutes: StateFlow<Int?> = _beaconModeEndMinutes.asStateFlow()

    /** This device's beacon ID, made once at random. */
    val beaconModeId: Int
        get() = prefs.getInt(KEY_BEACON_ID, 0).takeIf { it != 0 }
            ?: SecureRandom().nextInt().let { if (it == 0) 1 else it }.also { id -> prefs.edit { putInt(KEY_BEACON_ID, id) } }

    fun setBeaconMode(enabled: Boolean, startMinutes: Int?, endMinutes: Int?) {
        prefs.edit {
            putBoolean(KEY_BEACON_MODE, enabled)
            putInt(KEY_BEACON_START, startMinutes ?: -1)
            putInt(KEY_BEACON_END, endMinutes ?: -1)
        }
        _beaconModeEnabled.value = enabled
        _beaconModeStartMinutes.value = startMinutes
        _beaconModeEndMinutes.value = endMinutes
    }

    /** Which engine watches location triggers; Play Services by default (ADR 0004). */
    private val _locationEngine = MutableStateFlow(
        prefs.getString(KEY_LOCATION_ENGINE, null)
            ?.let { saved -> LocationEngineChoice.entries.firstOrNull { it.name == saved } }
            ?: LocationEngineChoice.PLAY_SERVICES
    )
    val locationEngine: StateFlow<LocationEngineChoice> = _locationEngine.asStateFlow()

    fun setLocationEngine(choice: LocationEngineChoice) {
        prefs.edit { putString(KEY_LOCATION_ENGINE, choice.name) }
        _locationEngine.value = choice
    }

    /** Quick lock's "after the screen turns off in an allowed app" choice. */
    private val _quickResumeMode = MutableStateFlow(
        readResumeMode(KEY_QUICK_RESUME_MODE) ?: _lockDefaults.value.resumeMode
    )
    val quickResumeMode: StateFlow<UnlockResumeMode> = _quickResumeMode.asStateFlow()

    private val _quickChoiceSeconds = MutableStateFlow(
        prefs.getInt(KEY_QUICK_CHOICE_SECONDS, _lockDefaults.value.unlockChoiceSeconds)
    )
    val quickChoiceSeconds: StateFlow<Int> = _quickChoiceSeconds.asStateFlow()

    fun setQuickResume(mode: UnlockResumeMode, choiceSeconds: Int) {
        prefs.edit {
            putString(KEY_QUICK_RESUME_MODE, mode.name)
            putInt(KEY_QUICK_CHOICE_SECONDS, choiceSeconds)
        }
        _quickResumeMode.value = mode
        _quickChoiceSeconds.value = choiceSeconds
    }

    fun setLockDefaults(defaults: LockDefaults) {
        prefs.edit {
            putLong(KEY_DEFAULT_DURATION_SEC, defaults.durationSec)
            putStringSet(KEY_DEFAULT_ALLOWED_PACKAGES, defaults.allowedPackages)
            putBoolean(KEY_DEFAULT_BYPASS_ENABLED, defaults.bypass.enabled)
            putInt(KEY_DEFAULT_BYPASS_MINUTES, defaults.bypass.waitMinutes)
            putBoolean(KEY_DEFAULT_DND, defaults.dndEnabled)
            putString(KEY_DEFAULT_DND_LEVEL, defaults.dndLevel.name)
            putBoolean(KEY_DEFAULT_GRAYSCALE, defaults.grayscale)
            putString(KEY_DEFAULT_RESUME_MODE, defaults.resumeMode.name)
            putInt(KEY_DEFAULT_CHOICE_SECONDS, defaults.unlockChoiceSeconds)
            putBoolean(KEY_DEFAULT_NFC_UNLOCK, defaults.nfcUnlock.anyTag)
            putStringSet(KEY_DEFAULT_NFC_TAGS, defaults.nfcUnlock.tagIds)
        }
        _lockDefaults.value = defaults
    }

    /** "Reset to defaults" on the Quick lock card: copies every Lock default into Quick lock. */
    fun resetQuickToDefaults() {
        val defaults = _lockDefaults.value
        setQuickDurationSec(defaults.durationSec)
        setAllowedPackages(defaults.allowedPackages)
        setBypassDefaults(defaults.bypass)
        setQuickDndEnabled(defaults.dndEnabled)
        setQuickDndLevel(defaults.dndLevel)
        setQuickGrayscale(defaults.grayscale)
        setQuickResume(defaults.resumeMode, defaults.unlockChoiceSeconds)
        setQuickNfcUnlock(defaults.nfcUnlock)
    }

    fun setQuickDurationSec(seconds: Long) {
        prefs.edit { putLong(KEY_QUICK_DURATION_SEC, seconds) }
        _quickDurationSec.value = seconds
    }

    fun setQuickDndEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_QUICK_DND, enabled) }
        _quickDndEnabled.value = enabled
    }

    /** The device profile the user picked; null means "use the detected one". */
    private val _oemProfileId = MutableStateFlow(prefs.getString(KEY_OEM_PROFILE, null))
    val oemProfileId: StateFlow<String?> = _oemProfileId.asStateFlow()

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    /** Quick lock's early-unlock setting, prefilled next time. */
    private val _bypassDefaults = MutableStateFlow(
        BypassDefaults(
            enabled = prefs.getBoolean(KEY_BYPASS_ENABLED, _lockDefaults.value.bypass.enabled),
            waitMinutes = prefs.getInt(KEY_BYPASS_MINUTES, _lockDefaults.value.bypass.waitMinutes),
        )
    )
    val bypassDefaults: StateFlow<BypassDefaults> = _bypassDefaults.asStateFlow()

    fun setBypassDefaults(defaults: BypassDefaults) {
        prefs.edit {
            putBoolean(KEY_BYPASS_ENABLED, defaults.enabled)
            putInt(KEY_BYPASS_MINUTES, defaults.waitMinutes)
        }
        _bypassDefaults.value = defaults
    }

    /** Whether ACTION_STOP_LOCK is honoured (with the right token). Off by default. */
    private val _intentStopEnabled = MutableStateFlow(prefs.getBoolean(KEY_INTENT_STOP_ENABLED, false))
    val intentStopEnabled: StateFlow<Boolean> = _intentStopEnabled.asStateFlow()

    /** The secret an automation app must send to stop a lock. Generated on first use. */
    private val _intentToken = MutableStateFlow(prefs.getString(KEY_INTENT_TOKEN, null) ?: newToken())
    val intentToken: StateFlow<String> = _intentToken.asStateFlow()

    fun setIntentStopEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INTENT_STOP_ENABLED, enabled) }
        _intentStopEnabled.value = enabled
    }

    /** Replaces the token. Existing automations that stop locks stop working until updated. */
    fun regenerateIntentToken() {
        _intentToken.value = newToken()
    }

    /** 24 random bytes as 32 URL-safe characters; also persists it. */
    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        prefs.edit { putString(KEY_INTENT_TOKEN, token) }
        return token
    }

    fun setAllowedPackages(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_ALLOWED_PACKAGES, packages) }
        _allowedPackages.value = packages
    }

    fun setOemProfileId(id: String?) {
        prefs.edit { putString(KEY_OEM_PROFILE, id) }
        _oemProfileId.value = id
    }

    fun setOnboardingDone(done: Boolean) {
        prefs.edit { putBoolean(KEY_ONBOARDING_DONE, done) }
        _onboardingDone.value = done
    }

    private fun readAllowedPackages(): Set<String> =
        prefs.getStringSet(KEY_ALLOWED_PACKAGES, null)?.toSet() ?: _lockDefaults.value.allowedPackages

    /** Unsaved values start from the constants that new profiles used before this page existed. */
    private fun readLockDefaults() = LockDefaults(
        durationSec = prefs.getLong(KEY_DEFAULT_DURATION_SEC, DEFAULT_DURATION_SEC),
        allowedPackages = prefs.getStringSet(KEY_DEFAULT_ALLOWED_PACKAGES, null)?.toSet() ?: emptySet(),
        bypass = BypassDefaults(
            enabled = prefs.getBoolean(KEY_DEFAULT_BYPASS_ENABLED, true),
            waitMinutes = prefs.getInt(KEY_DEFAULT_BYPASS_MINUTES, DEFAULT_BYPASS_MINUTES),
        ),
        dndEnabled = prefs.getBoolean(KEY_DEFAULT_DND, false),
        dndLevel = readDndLevel(KEY_DEFAULT_DND_LEVEL) ?: DndLevel.PRIORITY,
        grayscale = prefs.getBoolean(KEY_DEFAULT_GRAYSCALE, false),
        resumeMode = readResumeMode(KEY_DEFAULT_RESUME_MODE) ?: UnlockResumeMode.RESUME_APP,
        unlockChoiceSeconds = prefs.getInt(KEY_DEFAULT_CHOICE_SECONDS, LockSession.DEFAULT_UNLOCK_CHOICE_SECONDS),
        nfcUnlock = readNfcUnlock(KEY_DEFAULT_NFC_UNLOCK, KEY_DEFAULT_NFC_TAGS) ?: NfcUnlock.NONE,
    )

    /** Unsaved presets: 15 min, 30 min, 1 h, 2 h, 4 h, 8 h. An empty saved list means "none". */
    private fun readDurationPresets(): List<Long> {
        val saved = prefs.getString(KEY_DURATION_PRESETS, null) ?: return DEFAULT_DURATION_PRESETS
        return saved.split(',').mapNotNull { it.trim().toLongOrNull() }
    }

    /** Null if nothing was saved yet. */
    private fun readNfcUnlock(anyKey: String, tagsKey: String): NfcUnlock? {
        if (!prefs.contains(anyKey) && !prefs.contains(tagsKey)) return null
        return NfcUnlock(
            anyTag = prefs.getBoolean(anyKey, false),
            tagIds = prefs.getStringSet(tagsKey, null)?.toSet() ?: emptySet(),
        )
    }

    private fun readDndLevel(key: String): DndLevel? =
        prefs.getString(key, null)?.let { saved -> DndLevel.entries.firstOrNull { it.name == saved } }

    private fun readResumeMode(key: String): UnlockResumeMode? =
        prefs.getString(key, null)?.let { saved -> UnlockResumeMode.entries.firstOrNull { it.name == saved } }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        const val KEY_OEM_PROFILE = "oem_profile"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_BYPASS_ENABLED = "bypass_enabled"
        const val KEY_BYPASS_MINUTES = "bypass_minutes"
        const val DEFAULT_BYPASS_MINUTES = 10
        const val KEY_INTENT_STOP_ENABLED = "intent_stop_enabled"
        const val KEY_INTENT_TOKEN = "intent_token"
        const val TOKEN_BYTES = 24
        const val KEY_QUICK_DURATION_SEC = "quick_duration_sec"
        const val KEY_QUICK_DND = "quick_dnd_enabled"
        const val KEY_QUICK_DND_LEVEL = "quick_dnd_level"
        const val KEY_QUICK_GRAYSCALE = "quick_grayscale"
        const val KEY_QUICK_NFC_UNLOCK = "quick_nfc_unlock"
        const val KEY_DEFAULT_NFC_UNLOCK = "default_nfc_unlock"
        const val KEY_QUICK_NFC_TAGS = "quick_nfc_unlock_tags"
        const val KEY_DEFAULT_NFC_TAGS = "default_nfc_unlock_tags"
        const val KEY_NFC_GATE_SECONDS = "nfc_gate_seconds"
        const val DEFAULT_NFC_GATE_SECONDS = 10
        const val KEY_DEFAULT_GRAYSCALE = "default_grayscale"
        const val KEY_DURATION_PRESETS = "duration_presets_sec"
        val DEFAULT_DURATION_PRESETS = listOf(15L, 30L, 60L, 120L, 240L, 480L).map { it * 60 }
        const val KEY_ALARM_DAY_START = "alarm_day_start_minutes"
        const val KEY_LOCATION_ENGINE = "location_engine"
        const val KEY_CALENDAR_SKIP_DECLINED = "calendar_skip_declined"
        const val KEY_CALENDAR_SKIP_FREE = "calendar_skip_free"
        const val KEY_BACKUP_FOLDER = "backup_folder_uri"
        const val KEY_AUTO_BACKUP = "auto_backup_enabled"
        const val KEY_AUTO_BACKUP_FOLDER = "auto_backup_folder_uri"
        const val KEY_BEACON_MODE = "beacon_mode_enabled"
        const val KEY_BEACON_START = "beacon_mode_start"
        const val KEY_BEACON_END = "beacon_mode_end"
        const val KEY_BEACON_ID = "beacon_mode_id"
        const val KEY_LAST_BACKUP_AT = "last_backup_at"
        const val KEY_DEFAULT_DND_LEVEL = "default_dnd_level"
        const val KEY_QUICK_RESUME_MODE = "quick_resume_mode"
        const val KEY_QUICK_CHOICE_SECONDS = "quick_choice_seconds"
        const val KEY_DEFAULT_DURATION_SEC = "default_duration_sec"
        const val DEFAULT_DURATION_SEC = 60L * 60
        const val KEY_DEFAULT_ALLOWED_PACKAGES = "default_allowed_packages"
        const val KEY_DEFAULT_BYPASS_ENABLED = "default_bypass_enabled"
        const val KEY_DEFAULT_BYPASS_MINUTES = "default_bypass_minutes"
        const val KEY_DEFAULT_DND = "default_dnd_enabled"
        const val KEY_DEFAULT_RESUME_MODE = "default_resume_mode"
        const val KEY_DEFAULT_CHOICE_SECONDS = "default_choice_seconds"
        const val KEY_SHOW_APP_ICONS = "show_app_icons"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
