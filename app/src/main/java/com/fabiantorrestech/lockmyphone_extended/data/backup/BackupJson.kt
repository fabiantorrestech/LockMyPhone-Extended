package com.fabiantorrestech.lockmyphone_extended.data.backup

import com.fabiantorrestech.lockmyphone_extended.core.dnd.DndLevel
import com.fabiantorrestech.lockmyphone_extended.core.lock.QUICK_LOCK_ID
import com.fabiantorrestech.lockmyphone_extended.core.lock.UnlockResumeMode
import com.fabiantorrestech.lockmyphone_extended.core.lock.HomeAction
import com.fabiantorrestech.lockmyphone_extended.data.BypassDefaults
import com.fabiantorrestech.lockmyphone_extended.data.FontChoice
import com.fabiantorrestech.lockmyphone_extended.data.LockDefaults
import com.fabiantorrestech.lockmyphone_extended.data.PickerStyle
import com.fabiantorrestech.lockmyphone_extended.data.ThemeMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmActiveRule
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmMatchMode
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.AlarmTriggerKind
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarLengthMode
import com.fabiantorrestech.lockmyphone_extended.data.db.CalendarTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.WakeTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.BeaconName
import com.fabiantorrestech.lockmyphone_extended.data.db.BtDetection
import com.fabiantorrestech.lockmyphone_extended.data.db.ChargerMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.PlaceSignal
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationMode
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationSlot
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import com.fabiantorrestech.lockmyphone_extended.data.db.LockProfile
import com.fabiantorrestech.lockmyphone_extended.data.db.LockSchedule
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcDuringLock
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcLinkMode
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTag
import com.fabiantorrestech.lockmyphone_extended.data.db.NfcTagAction
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetworksJson
import com.fabiantorrestech.lockmyphone_extended.trigger.calendar.DeviceCalendar
import com.fabiantorrestech.lockmyphone_extended.trigger.location.LocationEngineChoice
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconPreset
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconTiming
import org.json.JSONArray
import org.json.JSONObject

/** The app settings a backup carries. Not the stop token, and nothing device-bound (the backup folder). */
data class SettingsSnapshot(
    val quickDurationSec: Long,
    val quickAllowedPackages: Set<String>,
    val quickHiddenPackages: Set<String>,
    val assistantPopupAllowed: Boolean,
    val homeAction: HomeAction,
    val unlockIntoAllowedApp: Boolean,
    val quickBypass: BypassDefaults,
    val quickDndEnabled: Boolean,
    val quickDndLevel: DndLevel,
    val quickGrayscale: Boolean,
    val quickResumeMode: UnlockResumeMode,
    val quickChoiceSeconds: Int,
    val lockDefaults: LockDefaults,
    val durationPresetsSec: List<Long>,
    val themeMode: ThemeMode,
    val showAppIcons: Boolean,
    /** An added font's file isn't in the backup; without it the font falls back to the default. */
    val pickerStyle: PickerStyle,
    val alarmDayStartMinutes: Int,
    val locationEngine: LocationEngineChoice,
    val calendarSkipDeclined: Boolean,
    val calendarSkipFree: Boolean,
    val oemProfileId: String?,
    val intentStopEnabled: Boolean,
    val nfcGateSeconds: Int,
    val automationCooldown: Boolean,
    val beaconPreset: BeaconPreset,
    val beaconCustomTiming: BeaconTiming,
)

/** Everything a backup file holds. */
data class BackupContents(
    val formatVersion: Int,
    val appVersion: String,
    val createdAtMs: Long,
    val settings: SettingsSnapshot,
    val profiles: List<LockProfile>,
    val schedules: List<LockSchedule>,
    val alarmTriggers: List<AlarmTrigger>,
    val locations: List<LocationTrigger>,
    val locationSlots: List<LocationSlot>,
    val calendarTriggers: List<CalendarTrigger>,
    /** The calendars the calendar triggers point at, so they can be found again by account + name. */
    val calendars: List<DeviceCalendar>,
    val nfcTags: List<NfcTag>,
    /** Added with DB version 19; older backups have none. */
    val beaconNames: List<BeaconName>,
    /** Morning locks; added with DB version 24, older backups have none. */
    val wakeTriggers: List<WakeTrigger> = emptyList(),
)

/**
 * Turns [BackupContents] into JSON and back, field by field. Reading is lenient: a missing field
 * takes its default, so older backups still load after new settings are added.
 */
object BackupJson {

    const val FORMAT_VERSION = 1
    private const val FORMAT = "lockmyphone-extended-backup"

    fun write(contents: BackupContents): String = JSONObject().apply {
        put("format", FORMAT)
        put("formatVersion", contents.formatVersion)
        put("appVersion", contents.appVersion)
        put("createdAtMs", contents.createdAtMs)
        put("settings", settingsToJson(contents.settings))
        put("profiles", array(contents.profiles, ::profileToJson))
        put("schedules", array(contents.schedules, ::scheduleToJson))
        put("alarmTriggers", array(contents.alarmTriggers, ::alarmToJson))
        put("locations", array(contents.locations, ::locationToJson))
        put("locationSlots", array(contents.locationSlots, ::slotToJson))
        put("calendarTriggers", array(contents.calendarTriggers, ::calendarToJson))
        put("calendars", array(contents.calendars) { JSONObject().put("id", it.id).put("name", it.name).put("account", it.account) })
        put("nfcTags", array(contents.nfcTags, ::nfcTagToJson))
        put("beaconNames", array(contents.beaconNames) { JSONObject().put("key", it.key).put("name", it.name) })
        put("wakeTriggers", array(contents.wakeTriggers, ::wakeToJson))
    }.toString(2)

    /** @throws IllegalArgumentException if [text] isn't a backup from this app. */
    fun read(text: String): BackupContents {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("Not a JSON file", it) }
        require(root.optString("format") == FORMAT) { "Not a LockMyPhone Extended backup" }
        val version = root.optInt("formatVersion", 0)
        require(version in 1..FORMAT_VERSION) { "Backup format $version is newer than this app understands" }
        return BackupContents(
            formatVersion = version,
            appVersion = root.optString("appVersion"),
            createdAtMs = root.optLong("createdAtMs"),
            settings = settingsFromJson(root.optJSONObject("settings") ?: JSONObject()),
            profiles = list(root, "profiles", ::profileFromJson),
            schedules = list(root, "schedules", ::scheduleFromJson),
            alarmTriggers = list(root, "alarmTriggers", ::alarmFromJson),
            locations = list(root, "locations", ::locationFromJson),
            locationSlots = list(root, "locationSlots", ::slotFromJson),
            calendarTriggers = list(root, "calendarTriggers", ::calendarFromJson),
            calendars = list(root, "calendars") { DeviceCalendar(it.getLong("id"), it.optString("name"), it.optString("account")) },
            nfcTags = list(root, "nfcTags", ::nfcTagFromJson),
            beaconNames = list(root, "beaconNames") { BeaconName(it.getString("key"), it.getString("name")) },
            wakeTriggers = list(root, "wakeTriggers", ::wakeFromJson),
        )
    }

    // ---- settings ----

    private fun settingsToJson(s: SettingsSnapshot) = JSONObject().apply {
        put("quickDurationSec", s.quickDurationSec)
        put("quickAllowedPackages", strings(s.quickAllowedPackages))
        put("quickHiddenPackages", strings(s.quickHiddenPackages))
        put("assistantPopupAllowed", s.assistantPopupAllowed)
        put("homeAction", s.homeAction.name)
        put("unlockIntoAllowedApp", s.unlockIntoAllowedApp)
        put("quickBypassEnabled", s.quickBypass.enabled)
        put("quickBypassMinutes", s.quickBypass.waitMinutes)
        put("quickDndEnabled", s.quickDndEnabled)
        put("quickDndLevel", s.quickDndLevel.name)
        put("quickGrayscale", s.quickGrayscale)
        put("quickResumeMode", s.quickResumeMode.name)
        put("quickChoiceSeconds", s.quickChoiceSeconds)
        put("lockDefaults", JSONObject().apply {
            val d = s.lockDefaults
            put("durationSec", d.durationSec)
            put("allowedPackages", strings(d.allowedPackages))
            put("hiddenPackages", strings(d.hiddenPackages))
            put("bypassEnabled", d.bypass.enabled)
            put("bypassMinutes", d.bypass.waitMinutes)
            put("dndEnabled", d.dndEnabled)
            put("dndLevel", d.dndLevel.name)
            put("grayscale", d.grayscale)
            put("resumeMode", d.resumeMode.name)
            put("choiceSeconds", d.unlockChoiceSeconds)
        })
        put("durationPresetsSec", JSONArray(s.durationPresetsSec))
        put("themeMode", s.themeMode.name)
        put("showAppIcons", s.showAppIcons)
        put("pickerClockSp", s.pickerStyle.clockSizeSp)
        put("pickerAppNameSp", s.pickerStyle.appNameSizeSp)
        put("pickerFont", s.pickerStyle.font.id)
        put("pickerHideStatusBar", s.pickerStyle.hideStatusBar)
        put("pickerShowTimeLeft", s.pickerStyle.showTimeLeft)
        put("alarmDayStartMinutes", s.alarmDayStartMinutes)
        put("locationEngine", s.locationEngine.name)
        put("calendarSkipDeclined", s.calendarSkipDeclined)
        put("calendarSkipFree", s.calendarSkipFree)
        put("oemProfileId", s.oemProfileId ?: JSONObject.NULL)
        put("intentStopEnabled", s.intentStopEnabled)
        put("nfcGateSeconds", s.nfcGateSeconds)
        put("automationCooldown", s.automationCooldown)
        put("beaconPreset", s.beaconPreset.name)
        put("beaconCustomArriveSec", s.beaconCustomTiming.arriveMs / 1_000)
        put("beaconCustomLeaveSec", s.beaconCustomTiming.leaveMs / 1_000)
        put("beaconCustomRecheckSec", s.beaconCustomTiming.recheckMs / 1_000)
        put("beaconCustomScanSpeed", s.beaconCustomTiming.scanSpeed.name)
    }

    private fun settingsFromJson(o: JSONObject): SettingsSnapshot {
        val d = o.optJSONObject("lockDefaults") ?: JSONObject()
        return SettingsSnapshot(
            quickDurationSec = o.optLong("quickDurationSec", 30L * 60),
            quickAllowedPackages = stringSet(o, "quickAllowedPackages"),
            // Backups from before M24 have none of these: nothing hidden, assistant off, Home → allowed apps.
            quickHiddenPackages = stringSet(o, "quickHiddenPackages"),
            assistantPopupAllowed = o.optBoolean("assistantPopupAllowed", false),
            homeAction = enumOr(o.optString("homeAction"), HomeAction.ALLOWED_APPS),
            unlockIntoAllowedApp = o.optBoolean("unlockIntoAllowedApp", true),
            quickBypass = BypassDefaults(o.optBoolean("quickBypassEnabled", true), o.optInt("quickBypassMinutes", 10)),
            quickDndEnabled = o.optBoolean("quickDndEnabled"),
            quickDndLevel = enumOr(o.optString("quickDndLevel"), DndLevel.PRIORITY),
            quickGrayscale = o.optBoolean("quickGrayscale"),
            quickResumeMode = enumOr(o.optString("quickResumeMode"), UnlockResumeMode.RESUME_APP),
            quickChoiceSeconds = o.optInt("quickChoiceSeconds", 3),
            lockDefaults = LockDefaults(
                durationSec = d.optLong("durationSec", 60L * 60),
                allowedPackages = stringSet(d, "allowedPackages"),
                hiddenPackages = stringSet(d, "hiddenPackages"),
                bypass = BypassDefaults(d.optBoolean("bypassEnabled", true), d.optInt("bypassMinutes", 10)),
                dndEnabled = d.optBoolean("dndEnabled"),
                dndLevel = enumOr(d.optString("dndLevel"), DndLevel.PRIORITY),
                grayscale = d.optBoolean("grayscale"),
                resumeMode = enumOr(d.optString("resumeMode"), UnlockResumeMode.RESUME_APP),
                unlockChoiceSeconds = d.optInt("choiceSeconds", 3),
            ),
            durationPresetsSec = o.optJSONArray("durationPresetsSec")?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList(),
            themeMode = enumOr(o.optString("themeMode"), ThemeMode.SYSTEM),
            showAppIcons = o.optBoolean("showAppIcons", true),
            pickerStyle = PickerStyle(
                clockSizeSp = o.optInt("pickerClockSp", PickerStyle.DEFAULT_CLOCK_SIZE_SP),
                appNameSizeSp = o.optInt("pickerAppNameSp", PickerStyle.DEFAULT_APP_NAME_SIZE_SP),
                font = FontChoice.fromId(o.optStringOrNull("pickerFont")),
                hideStatusBar = o.optBoolean("pickerHideStatusBar", true),
                showTimeLeft = o.optBoolean("pickerShowTimeLeft", true),
            ).clamped(),
            alarmDayStartMinutes = o.optInt("alarmDayStartMinutes", 120),
            locationEngine = enumOr(o.optString("locationEngine"), LocationEngineChoice.PLAY_SERVICES),
            calendarSkipDeclined = o.optBoolean("calendarSkipDeclined", true),
            calendarSkipFree = o.optBoolean("calendarSkipFree", false),
            oemProfileId = o.optStringOrNull("oemProfileId"),
            intentStopEnabled = o.optBoolean("intentStopEnabled"),
            nfcGateSeconds = o.optInt("nfcGateSeconds", 10),
            automationCooldown = o.optBoolean("automationCooldown", true),
            beaconPreset = enumOr(o.optString("beaconPreset"), BeaconPreset.BALANCED),
            beaconCustomTiming = BeaconTiming(
                arriveMs = o.optLong("beaconCustomArriveSec", BeaconTiming.BALANCED.arriveMs / 1_000) * 1_000,
                leaveMs = o.optLong("beaconCustomLeaveSec", BeaconTiming.BALANCED.leaveMs / 1_000) * 1_000,
                recheckMs = o.optLong("beaconCustomRecheckSec", BeaconTiming.BALANCED.recheckMs / 1_000) * 1_000,
                scanSpeed = enumOr(o.optString("beaconCustomScanSpeed"), BeaconTiming.BALANCED.scanSpeed),
            ).clamped(),
        )
    }

    // ---- entities ----

    private fun profileToJson(p: LockProfile) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("defaultDurationSec", p.defaultDurationSec)
        put("allowedPackages", strings(p.allowedPackages)); put("bypassEnabled", p.bypassEnabled)
        put("hiddenPackages", strings(p.hiddenPackages))
        put("bypassWaitMinutes", p.bypassWaitMinutes); put("nfcUnlockEnabled", p.nfcUnlockEnabled)
        put("dndEnabled", p.dndEnabled); put("dndLevel", p.dndLevel.name); put("grayscale", p.grayscale)
        put("unlockResumeMode", p.unlockResumeMode.name); put("unlockChoiceSeconds", p.unlockChoiceSeconds)
        put("icon", p.icon ?: JSONObject.NULL); put("sortOrder", p.sortOrder)
    }

    private fun profileFromJson(o: JSONObject) = LockProfile(
        id = o.getLong("id"), name = o.getString("name"), defaultDurationSec = o.optLong("defaultDurationSec", 3600),
        allowedPackages = stringSet(o, "allowedPackages"), bypassEnabled = o.optBoolean("bypassEnabled", true),
        hiddenPackages = stringSet(o, "hiddenPackages"),
        bypassWaitMinutes = o.optInt("bypassWaitMinutes", 10), nfcUnlockEnabled = o.optBoolean("nfcUnlockEnabled"),
        dndEnabled = o.optBoolean("dndEnabled"), dndLevel = enumOr(o.optString("dndLevel"), DndLevel.PRIORITY),
        grayscale = o.optBoolean("grayscale"),
        unlockResumeMode = enumOr(o.optString("unlockResumeMode"), UnlockResumeMode.RESUME_APP),
        unlockChoiceSeconds = o.optInt("unlockChoiceSeconds", 3), icon = o.optStringOrNull("icon"),
        sortOrder = o.optInt("sortOrder"),
    )

    private fun scheduleToJson(s: LockSchedule) = JSONObject().apply {
        put("id", s.id); put("profileId", s.profileId); put("daysMask", s.daysMask)
        put("startMinuteOfDay", s.startMinuteOfDay); put("endMinuteOfDay", s.endMinuteOfDay)
        put("enabled", s.enabled); put("icon", s.icon ?: JSONObject.NULL); put("sortOrder", s.sortOrder)
        put("requiredPlaceId", s.requiredPlaceId ?: JSONObject.NULL)
    }

    private fun scheduleFromJson(o: JSONObject) = LockSchedule(
        id = o.getLong("id"), profileId = o.getLong("profileId"), daysMask = o.getInt("daysMask"),
        startMinuteOfDay = o.getInt("startMinuteOfDay"), endMinuteOfDay = o.getInt("endMinuteOfDay"),
        enabled = o.optBoolean("enabled", true), icon = o.optStringOrNull("icon"), sortOrder = o.optInt("sortOrder"),
        requiredPlaceId = o.optLongOrNull("requiredPlaceId"),
    )

    private fun alarmToJson(a: AlarmTrigger) = JSONObject().apply {
        put("id", a.id); put("name", a.name); put("packageName", a.packageName)
        put("channelId", a.channelId ?: JSONObject.NULL); put("notificationId", a.notificationId ?: JSONObject.NULL)
        put("title", a.title ?: JSONObject.NULL); put("text", a.text ?: JSONObject.NULL); put("matchMode", a.matchMode.name)
        put("profileId", a.profileId ?: JSONObject.NULL); put("delayMinutes", a.delayMinutes); put("enabled", a.enabled)
        put("expectedEveryDays", a.expectedEveryDays); put("icon", a.icon ?: JSONObject.NULL); put("sortOrder", a.sortOrder)
        put("kind", a.kind.name); put("firstAlarmPackages", strings(a.firstAlarmPackages))
        put("activeRule", a.activeRule.name); put("daysMask", a.daysMask); put("dates", strings(a.dates))
    }

    private fun alarmFromJson(o: JSONObject) = AlarmTrigger(
        id = o.getLong("id"), name = o.getString("name"), packageName = o.optString("packageName"),
        channelId = o.optStringOrNull("channelId"), notificationId = o.optIntOrNull("notificationId"),
        title = o.optStringOrNull("title"), text = o.optStringOrNull("text"),
        matchMode = enumOr(o.optString("matchMode"), AlarmMatchMode.NOTIFICATION_ID), profileId = o.optLongOrNull("profileId"),
        delayMinutes = o.optInt("delayMinutes"), enabled = o.optBoolean("enabled", true),
        expectedEveryDays = o.optInt("expectedEveryDays", 1), icon = o.optStringOrNull("icon"), sortOrder = o.optInt("sortOrder"),
        kind = enumOr(o.optString("kind"), AlarmTriggerKind.SPECIFIC_ALARM), firstAlarmPackages = stringSet(o, "firstAlarmPackages"),
        activeRule = enumOr(o.optString("activeRule"), AlarmActiveRule.EVERY_DAY), daysMask = o.optInt("daysMask", 127),
        dates = stringSet(o, "dates"),
    )

    private fun locationToJson(l: LocationTrigger) = JSONObject().apply {
        put("id", l.id); put("name", l.name); put("icon", l.icon ?: JSONObject.NULL); put("enabled", l.enabled)
        put("sortOrder", l.sortOrder); put("latitude", l.latitude); put("longitude", l.longitude)
        put("radiusMeters", l.radiusMeters); put("wifiBssid", l.wifiBssid ?: JSONObject.NULL)
        put("wifiSsid", l.wifiSsid ?: JSONObject.NULL); put("mode", l.mode.name); put("profileId", l.profileId ?: JSONObject.NULL)
        put("activeRule", l.activeRule.name); put("daysMask", l.daysMask); put("dates", strings(l.dates))
        put("matchMode", l.matchMode.name); put("lockOnReturn", l.lockOnReturn); put("stayLockedAfterLeaving", l.stayLockedAfterLeaving)
        put("signals", strings(l.signals)); put("requireAll", l.requireAll)
        put("beaconKey", l.beaconKey ?: JSONObject.NULL); put("beaconName", l.beaconName ?: JSONObject.NULL)
        put("beaconRssiThreshold", l.beaconRssiThreshold); put("chargerMode", l.chargerMode.name)
        put("btAddress", l.btAddress ?: JSONObject.NULL); put("btName", l.btName ?: JSONObject.NULL)
        put("btDetection", l.btDetection.name); put("btRssiThreshold", l.btRssiThreshold)
        put("beaconAddress", l.beaconAddress ?: JSONObject.NULL); put("beaconOneMeterDbm", l.beaconOneMeterDbm ?: JSONObject.NULL)
        put("beaconPreset", l.beaconPreset ?: JSONObject.NULL); put("beaconArriveSec", l.beaconArriveSec ?: JSONObject.NULL)
        put("beaconLeaveSec", l.beaconLeaveSec ?: JSONObject.NULL); put("beaconRecheckSec", l.beaconRecheckSec ?: JSONObject.NULL)
        put("beaconScanSpeed", l.beaconScanSpeed ?: JSONObject.NULL)
        put("beaconBoost", l.beaconBoost ?: JSONObject.NULL); put("beaconBoostZones", strings(l.beaconBoostZones))
        put("beaconBoostWifiBssid", l.beaconBoostWifiBssid ?: JSONObject.NULL); put("beaconBoostWifiSsid", l.beaconBoostWifiSsid ?: JSONObject.NULL)
        put("wifiNetworks", JSONArray(WifiNetworksJson.write(l.wifiNetworks)))
        put("beaconBoostWifiNetworks", JSONArray(WifiNetworksJson.write(l.beaconBoostWifiNetworks)))
    }

    private fun locationFromJson(o: JSONObject) = LocationTrigger(
        id = o.getLong("id"), name = o.getString("name"), icon = o.optStringOrNull("icon"), enabled = o.optBoolean("enabled", true),
        sortOrder = o.optInt("sortOrder"), latitude = o.getDouble("latitude"), longitude = o.getDouble("longitude"),
        radiusMeters = o.optInt("radiusMeters", LocationTrigger.DEFAULT_RADIUS_METERS), wifiBssid = o.optStringOrNull("wifiBssid"),
        wifiSsid = o.optStringOrNull("wifiSsid"), mode = enumOr(o.optString("mode"), LocationMode.WHILE_HERE),
        profileId = o.optLongOrNull("profileId"), activeRule = enumOr(o.optString("activeRule"), AlarmActiveRule.EVERY_DAY),
        daysMask = o.optInt("daysMask", 127), dates = stringSet(o, "dates"),
        matchMode = enumOr(o.optString("matchMode"), LocationMatch.GPS_ONLY), lockOnReturn = o.optBoolean("lockOnReturn", true),
        stayLockedAfterLeaving = o.optBoolean("stayLockedAfterLeaving"),
        signals = stringSet(o, "signals").ifEmpty { legacySignals(o) },
        requireAll = o.optBoolean("requireAll", o.optString("matchMode") != LocationMatch.GPS_OR_WIFI.name),
        beaconKey = o.optStringOrNull("beaconKey"), beaconName = o.optStringOrNull("beaconName"),
        beaconRssiThreshold = o.optInt("beaconRssiThreshold", LocationTrigger.DEFAULT_BEACON_THRESHOLD),
        chargerMode = enumOr(o.optString("chargerMode"), ChargerMode.ANY),
        btAddress = o.optStringOrNull("btAddress"), btName = o.optStringOrNull("btName"),
        btDetection = enumOr(o.optString("btDetection"), BtDetection.CONNECTED),
        btRssiThreshold = o.optInt("btRssiThreshold", LocationTrigger.DEFAULT_BEACON_THRESHOLD),
        beaconAddress = o.optStringOrNull("beaconAddress"), beaconOneMeterDbm = o.optIntOrNull("beaconOneMeterDbm"),
        beaconPreset = o.optStringOrNull("beaconPreset"), beaconArriveSec = o.optIntOrNull("beaconArriveSec"),
        beaconLeaveSec = o.optIntOrNull("beaconLeaveSec"), beaconRecheckSec = o.optIntOrNull("beaconRecheckSec"),
        beaconScanSpeed = o.optStringOrNull("beaconScanSpeed"),
        beaconBoost = o.optStringOrNull("beaconBoost"), beaconBoostZones = stringSet(o, "beaconBoostZones"),
        beaconBoostWifiBssid = o.optStringOrNull("beaconBoostWifiBssid"), beaconBoostWifiSsid = o.optStringOrNull("beaconBoostWifiSsid"),
        // Backups from before DB version 23 had one router each.
        wifiNetworks = o.optJSONArray("wifiNetworks")?.let { WifiNetworksJson.read(it.toString()) }
            ?: WifiNetworksJson.legacy(o.optStringOrNull("wifiBssid"), o.optStringOrNull("wifiSsid")),
        beaconBoostWifiNetworks = o.optJSONArray("beaconBoostWifiNetworks")?.let { WifiNetworksJson.read(it.toString()) }
            ?: WifiNetworksJson.legacy(o.optStringOrNull("beaconBoostWifiBssid"), o.optStringOrNull("beaconBoostWifiSsid")),
    )

    /** Backups from before signals existed: derive them from the old match mode. */
    private fun legacySignals(o: JSONObject): Set<String> = when (o.optString("matchMode")) {
        LocationMatch.WIFI_ONLY.name -> setOf(PlaceSignal.WIFI.name)
        LocationMatch.GPS_AND_WIFI.name, LocationMatch.GPS_OR_WIFI.name -> setOf(PlaceSignal.GPS.name, PlaceSignal.WIFI.name)
        else -> setOf(PlaceSignal.GPS.name)
    }

    private fun slotToJson(s: LocationSlot) = JSONObject().apply {
        put("id", s.id); put("locationId", s.locationId); put("daysMask", s.daysMask)
        put("startMinuteOfDay", s.startMinuteOfDay); put("endMinuteOfDay", s.endMinuteOfDay)
    }

    private fun slotFromJson(o: JSONObject) = LocationSlot(
        id = o.getLong("id"), locationId = o.getLong("locationId"), daysMask = o.optInt("daysMask", 127),
        startMinuteOfDay = o.getInt("startMinuteOfDay"), endMinuteOfDay = o.getInt("endMinuteOfDay"),
    )

    private fun calendarToJson(c: CalendarTrigger) = JSONObject().apply {
        put("id", c.id); put("name", c.name); put("icon", c.icon ?: JSONObject.NULL); put("enabled", c.enabled)
        put("sortOrder", c.sortOrder); put("calendarIds", strings(c.calendarIds)); put("keywords", strings(c.keywords))
        put("searchNotesAndLocation", c.searchNotesAndLocation); put("includeAllDay", c.includeAllDay)
        put("skipDeclined", c.skipDeclined); put("skipFree", c.skipFree); put("lengthMode", c.lengthMode.name)
        put("fixedDurationSec", c.fixedDurationSec); put("startEarlyMinutes", c.startEarlyMinutes)
        put("profileId", c.profileId ?: JSONObject.NULL); put("requiredPlaceId", c.requiredPlaceId ?: JSONObject.NULL)
    }

    private fun calendarFromJson(o: JSONObject) = CalendarTrigger(
        id = o.getLong("id"), name = o.getString("name"), icon = o.optStringOrNull("icon"), enabled = o.optBoolean("enabled", true),
        sortOrder = o.optInt("sortOrder"), calendarIds = stringSet(o, "calendarIds"), keywords = stringSet(o, "keywords"),
        searchNotesAndLocation = o.optBoolean("searchNotesAndLocation"), includeAllDay = o.optBoolean("includeAllDay"),
        skipDeclined = o.optBoolean("skipDeclined", true), skipFree = o.optBoolean("skipFree"),
        lengthMode = enumOr(o.optString("lengthMode"), CalendarLengthMode.EVENT_END),
        fixedDurationSec = o.optLong("fixedDurationSec", CalendarTrigger.DEFAULT_FIXED_DURATION_SEC),
        startEarlyMinutes = o.optInt("startEarlyMinutes"), profileId = o.optLongOrNull("profileId"),
        requiredPlaceId = o.optLongOrNull("requiredPlaceId"),
    )

    private fun wakeToJson(w: WakeTrigger) = JSONObject().apply {
        put("id", w.id); put("name", w.name); put("icon", w.icon ?: JSONObject.NULL); put("enabled", w.enabled)
        put("sortOrder", w.sortOrder); put("profileId", w.profileId ?: JSONObject.NULL); put("lockMinutes", w.lockMinutes)
        put("windowStartMin", w.windowStartMin); put("windowEndMin", w.windowEndMin); put("daysMask", w.daysMask)
        put("alarmFirst", w.alarmFirst); put("alarmPackages", strings(w.alarmPackages))
        put("minSleepMinutes", w.minSleepMinutes); put("sleepPlaceIds", strings(w.sleepPlaceIds.map { it.toString() }.toSet()))
        put("checkCharging", w.checkCharging); put("chargingMinutes", w.chargingMinutes); put("checkPlace", w.checkPlace)
        put("lastFiredDay", w.lastFiredDay ?: JSONObject.NULL)
        put("yieldToAlarms", w.yieldToAlarms); put("yieldAlarmIds", strings(w.yieldAlarmIds.map { it.toString() }.toSet()))
        put("countFromScreenOn", w.countFromScreenOn); put("altEndMin", w.altEndMin ?: JSONObject.NULL)
        put("altDaysMask", w.altDaysMask); put("lateCheck", w.lateCheck); put("lateCheckAfterMin", w.lateCheckAfterMin)
    }

    private fun wakeFromJson(o: JSONObject) = WakeTrigger(
        id = o.getLong("id"), name = o.getString("name"), icon = o.optStringOrNull("icon"), enabled = o.optBoolean("enabled", true),
        sortOrder = o.optInt("sortOrder"), profileId = o.optLongOrNull("profileId"),
        lockMinutes = o.optInt("lockMinutes", WakeTrigger.DEFAULT_LOCK_MINUTES),
        windowStartMin = o.optInt("windowStartMin", WakeTrigger.DEFAULT_WINDOW_START),
        windowEndMin = o.optInt("windowEndMin", WakeTrigger.DEFAULT_WINDOW_END),
        daysMask = o.optInt("daysMask", LockSchedule.EVERY_DAY),
        alarmFirst = o.optBoolean("alarmFirst", true), alarmPackages = stringSet(o, "alarmPackages"),
        minSleepMinutes = o.optInt("minSleepMinutes", WakeTrigger.DEFAULT_MIN_SLEEP_MINUTES),
        sleepPlaceIds = stringSet(o, "sleepPlaceIds").mapNotNull { it.toLongOrNull() }.toSet(),
        checkCharging = o.optBoolean("checkCharging", true),
        chargingMinutes = o.optInt("chargingMinutes", WakeTrigger.DEFAULT_CHARGING_MINUTES),
        checkPlace = o.optBoolean("checkPlace", true), lastFiredDay = o.optStringOrNull("lastFiredDay"),
        yieldToAlarms = o.optBoolean("yieldToAlarms", true),
        yieldAlarmIds = stringSet(o, "yieldAlarmIds").mapNotNull { it.toLongOrNull() }.toSet(),
        countFromScreenOn = o.optBoolean("countFromScreenOn", true),
        altEndMin = if (o.isNull("altEndMin")) null else o.optInt("altEndMin"),
        altDaysMask = o.optInt("altDaysMask", 0), lateCheck = o.optBoolean("lateCheck", false),
        lateCheckAfterMin = o.optInt("lateCheckAfterMin", WakeTrigger.DEFAULT_LATE_CHECK_AFTER),
    )

    private fun nfcTagToJson(t: NfcTag) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("icon", t.icon ?: JSONObject.NULL); put("uid", t.uid)
        put("action", t.action.name); put("duringLock", t.duringLock.name)
        put("linkMode", t.linkMode.name); put("linkedLockIds", strings(t.linkedLockIds))
        put("durationSec", t.durationSec ?: JSONObject.NULL); put("enabled", t.enabled); put("sortOrder", t.sortOrder)
        // Included so restored tags still end locks without being written again.
        put("secret", t.secret ?: JSONObject.NULL)
        put("takeOver", t.takeOver)
    }

    private fun nfcTagFromJson(o: JSONObject) = NfcTag(
        id = o.getLong("id"), name = o.getString("name"), icon = o.optStringOrNull("icon"), uid = o.getString("uid"),
        action = enumOr(o.optString("action"), NfcTagAction.TOGGLE),
        duringLock = enumOr(o.optString("duringLock"), NfcDuringLock.START_BYPASS_WAIT),
        linkMode = enumOr(o.optString("linkMode"), NfcLinkMode.ONE),
        // Backups from before links (DB version 17) name one profile, or none for Quick lock.
        linkedLockIds = if (o.has("linkedLockIds")) stringSet(o, "linkedLockIds") else setOf(o.optLongOrNull("profileId")?.toString() ?: QUICK_LOCK_ID),
        durationSec = o.optLongOrNull("durationSec"),
        enabled = o.optBoolean("enabled", true), sortOrder = o.optInt("sortOrder"),
        secret = o.optStringOrNull("secret"),
        takeOver = o.optBoolean("takeOver"),
    )

    // ---- helpers ----

    private fun <T> array(items: List<T>, toJson: (T) -> JSONObject) = JSONArray().apply { items.forEach { put(toJson(it)) } }

    private fun <T> list(root: JSONObject, key: String, fromJson: (JSONObject) -> T): List<T> {
        val array = root.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
    }

    private fun strings(values: Collection<String>) = JSONArray(values.sorted())

    private fun stringSet(o: JSONObject, key: String): Set<String> {
        val array = o.optJSONArray(key) ?: return emptySet()
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)

    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)
}
