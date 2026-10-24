package com.fabiantorrestech.lockmyphone_extended.data

import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppCatalog
import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.data.db.AppDatabase
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule

/**
 * The everyday situations the setup assistant offers as ready-made profiles.
 *
 * @param durationSec the profile's default length.
 * @param bypassMinutes the early-unlock wait, or null for no early unlock.
 * @param dnd whether the lock turns on Do Not Disturb (priority only).
 */
enum class Situation(
    val icon: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val durationSec: Long,
    val bypassMinutes: Int?,
    val dnd: Boolean,
) {
    SLEEP("🌙", R.string.situation_sleep, R.string.situation_sleep_desc, 9 * HOUR, 10, true),
    FOCUS("🎯", R.string.situation_focus, R.string.situation_focus_desc, 50 * MINUTE, 5, false),
    STUDY("📚", R.string.situation_study, R.string.situation_study_desc, 90 * MINUTE, null, false),
    FAMILY("🍽", R.string.situation_family, R.string.situation_family_desc, 2 * HOUR, 5, true),
}

private const val MINUTE = 60L
private const val HOUR = 60 * MINUTE

/** Allowed apps: the ones shown in the lock-screen list, and the ones allowed but hidden (the camera). */
data class ResolvedApps(val visible: Set<String>, val hidden: Set<String>) {
    val all: Set<String> get() = visible + hidden
}

/**
 * The apps almost everyone needs during a lock, found on this phone: Messages and Clock (shown),
 * the camera (allowed but hidden, so "take a photo" in a chat works). The phone app is always
 * allowed by the lock itself. Situations add a few role apps (music, calendar, calculator).
 * Apps that aren't installed are skipped.
 */
class EssentialApps(private val catalog: AppCatalog) {

    fun essentials(): ResolvedApps = ResolvedApps(
        visible = setOfNotNull(catalog.defaultSmsPackage(), catalog.clockPackage()),
        hidden = setOfNotNull(catalog.defaultCameraPackage()),
    )

    /** The phone app, which every lock allows on top of its list. */
    fun dialer(): String? = catalog.defaultDialerPackage()

    fun extrasFor(situation: Situation): Set<String> = when (situation) {
        Situation.FOCUS -> setOfNotNull(music(), calendar())
        Situation.STUDY -> setOfNotNull(catalog.categoryApp(Intent.CATEGORY_APP_CALCULATOR), music(), calendar())
        Situation.SLEEP, Situation.FAMILY -> emptySet()
    }

    private fun music(): String? = catalog.categoryApp(Intent.CATEGORY_APP_MUSIC)

    private fun calendar(): String? = catalog.categoryApp(Intent.CATEGORY_APP_CALENDAR)
}

/** Builds the starter profiles and the bedtime schedule. Pure. */
object StarterProfiles {

    /** 22:30 → 07:00, every day. */
    const val DEFAULT_BEDTIME_START = 22 * 60 + 30
    const val DEFAULT_BEDTIME_END = 7 * 60

    fun profile(situation: Situation, name: String, apps: ResolvedApps, extras: Set<String>): LockProfile = LockProfile(
        name = name,
        icon = situation.icon,
        defaultDurationSec = situation.durationSec,
        allowedPackages = apps.all + extras,
        hiddenPackages = apps.hidden - extras,
        bypassEnabled = situation.bypassMinutes != null,
        bypassWaitMinutes = situation.bypassMinutes ?: DEFAULT_BYPASS_MINUTES,
        dndEnabled = situation.dnd,
        dndLevel = DndLevel.PRIORITY,
    )

    fun bedtimeSchedule(profileId: Long, startMinute: Int, endMinute: Int): LockSchedule = LockSchedule(
        profileId = profileId,
        daysMask = LockSchedule.EVERY_DAY,
        startMinuteOfDay = startMinute,
        endMinuteOfDay = endMinute,
    )

    private const val DEFAULT_BYPASS_MINUTES = 10
}

/**
 * Writes what the setup assistant chose. Safe to run again: a profile whose name already exists is
 * left alone, and the bedtime schedule is only added when the Sleep profile has none.
 */
class StarterProfileInstaller(
    private val database: AppDatabase,
    private val essentials: EssentialApps,
    private val settings: SettingsStore,
    private val nameOf: (Situation) -> String,
) {

    /**
     * @param bedtime start and end minute of day for the Sleep profile's nightly schedule, or null for none.
     * @return true if any profile was created (so no "Default" profile is needed).
     */
    suspend fun install(situations: Set<Situation>, bedtime: Pair<Int, Int>?): Boolean {
        val apps = essentials.essentials()
        val dao = database.profileDao()
        var created = false
        for (situation in Situation.entries.filter { it in situations }) {
            val name = nameOf(situation)
            if (dao.getByName(name) != null) continue
            dao.insertLast(StarterProfiles.profile(situation, name, apps, essentials.extrasFor(situation)))
            created = true
        }
        if (bedtime != null && Situation.SLEEP in situations) addBedtime(bedtime)
        seedEssentials(apps)
        return created
    }

    private suspend fun addBedtime(bedtime: Pair<Int, Int>) {
        val sleep = database.profileDao().getByName(nameOf(Situation.SLEEP)) ?: return
        val schedules = database.scheduleDao()
        if (schedules.getAll().any { it.profileId == sleep.id }) return
        schedules.insertLast(StarterProfiles.bedtimeSchedule(sleep.id, bedtime.first, bedtime.second))
    }

    /**
     * Lock defaults and Quick lock that still show no apps (only the hidden camera, or nothing) get
     * the Essentials, an hour's length and a 10-minute early unlock. Lists you've picked are kept.
     */
    private fun seedEssentials(apps: ResolvedApps) {
        val defaults = settings.lockDefaults.value
        if ((defaults.allowedPackages - defaults.hiddenPackages).isEmpty()) {
            settings.setLockDefaults(
                defaults.copy(
                    durationSec = HOUR,
                    allowedPackages = defaults.allowedPackages + apps.all,
                    hiddenPackages = defaults.hiddenPackages + apps.hidden,
                    bypass = BypassDefaults(enabled = true, waitMinutes = 10),
                )
            )
        }
        val quick = settings.allowedPackages.value
        val quickHidden = settings.quickHiddenPackages.value
        if ((quick - quickHidden).isEmpty()) {
            settings.setAllowedPackages(quick + apps.all)
            settings.setQuickHiddenPackages(quickHidden + apps.hidden)
        }
    }
}
