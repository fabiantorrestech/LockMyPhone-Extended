package com.fabiantorrestech.lockmyphone_extended.data

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockSession
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.trigger.alarm.AlarmDayRules
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationEngineChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** The app's colour theme. OLED is dark with pure-black backgrounds. */
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

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
    )

    /** Unsaved presets: 15 min, 30 min, 1 h, 2 h, 4 h, 8 h. An empty saved list means "none". */
    private fun readDurationPresets(): List<Long> {
        val saved = prefs.getString(KEY_DURATION_PRESETS, null) ?: return DEFAULT_DURATION_PRESETS
        return saved.split(',').mapNotNull { it.trim().toLongOrNull() }
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
        const val KEY_DEFAULT_GRAYSCALE = "default_grayscale"
        const val KEY_DURATION_PRESETS = "duration_presets_sec"
        val DEFAULT_DURATION_PRESETS = listOf(15L, 30L, 60L, 120L, 240L, 480L).map { it * 60 }
        const val KEY_ALARM_DAY_START = "alarm_day_start_minutes"
        const val KEY_LOCATION_ENGINE = "location_engine"
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
