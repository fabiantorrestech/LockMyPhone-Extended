package com.fabiantorrestech.lockmyphone_extended.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

/** What a location trigger does when you arrive or leave. */
enum class LocationMode {
    /** Arriving starts the lock; leaving ends it (if this location started it). */
    WHILE_HERE,

    /** Arriving starts the lock for its normal length. */
    ON_ARRIVAL,

    /** Leaving starts the lock for its normal length. */
    ON_LEAVE,

    /** Locked while you're here during one of its time slots ([LocationSlot]). Added in DB version 11. */
    TIME_SLOTS,

    /** No lock of its own: only used as "Only while at…" by schedules and calendar triggers. */
    CONDITION_ONLY,
}

/** The ways a place can know you're there. Added in DB version 13. */
enum class PlaceSignal {
    /** Inside the GPS circle. */
    GPS,

    /** Connected to the place's Wi-Fi router. */
    WIFI,

    /** Near the place's Bluetooth beacon (desk beacon, ESP32, a tablet in beacon mode…). */
    BEACON,

    /** A paired Bluetooth device: connected to it, or (experimental) near it. Added in DB version 14. */
    BT_DEVICE,

    /** Plugged into a charger. */
    CHARGER,
}

/** Which chargers count for the [PlaceSignal.CHARGER] signal. */
enum class ChargerMode { ANY, WIRELESS }

/** How the [PlaceSignal.BT_DEVICE] signal knows the device is there. */
enum class BtDetection {
    /** Connected to it (car, speaker, headphones, watch): exact, no scanning. */
    CONNECTED,

    /** Heard nearby without a connection. Experimental: only works if Android recognises the device's changing address. */
    NEARBY,
}

/** How a saved Wi-Fi network is recognised. */
enum class WifiMatch {
    /** This exact router (its BSSID): precise, but a mesh network has one per node. */
    ROUTER,

    /** Any router with this network name (SSID): covers mesh networks, but every network with that name counts, anywhere. */
    NAME,
}

/**
 * One Wi-Fi network a place uses. Added in DB version 23 (several per place).
 *
 * @param ssid the network name; null when the network hides it.
 * @param bssid the router's address; null for a network added by name only.
 */
data class WifiNetwork(val ssid: String?, val bssid: String?, val matchBy: WifiMatch = WifiMatch.ROUTER) {
    /** For lists: the name, or the router's address for a hidden network. */
    val label: String get() = ssid ?: bssid ?: "?"
}

/** [WifiNetwork] lists as JSON, for the database and backups. An SSID can contain any character, so not a joined string. */
object WifiNetworksJson {
    fun write(networks: List<WifiNetwork>): String = JSONArray().apply {
        networks.forEach { network ->
            put(
                JSONObject()
                    .put("ssid", network.ssid ?: JSONObject.NULL)
                    .put("bssid", network.bssid ?: JSONObject.NULL)
                    .put("matchBy", network.matchBy.name)
            )
        }
    }.toString()

    fun read(text: String?): List<WifiNetwork> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            WifiNetwork(
                ssid = if (o.isNull("ssid")) null else o.optString("ssid"),
                bssid = if (o.isNull("bssid")) null else o.optString("bssid"),
                matchBy = WifiMatch.entries.firstOrNull { it.name == o.optString("matchBy") } ?: WifiMatch.ROUTER,
            )
        }
    }

    /** The one network a place had before DB version 23, if any. */
    fun legacy(bssid: String?, ssid: String?): List<WifiNetwork> =
        if (bssid == null) emptyList() else listOf(WifiNetwork(ssid, bssid, WifiMatch.ROUTER))
}

/**
 * How GPS and Wi-Fi combined in DB versions 11–12. Replaced by [LocationTrigger.signals] and
 * [LocationTrigger.requireAll]; kept only because the column still exists.
 */
enum class LocationMatch {
    GPS_ONLY,
    WIFI_ONLY,

    /** Both needed: the strictest. */
    GPS_AND_WIFI,

    /** Either is enough: the most reliable indoors. */
    GPS_OR_WIFI,
}

/**
 * Starts a lock by place: a circle around a point, and optionally a specific Wi-Fi router
 * (its BSSID), which is exact to a building or room. Added in DB version 10.
 *
 * @param radiusMeters the circle's radius, 25–2000 m.
 * @param wifiBssid legacy (before DB version 23): the one router; replaced by [wifiNetworks].
 * @param wifiSsid legacy, with [wifiBssid].
 * @param wifiNetworks the Wi-Fi signal's networks: connected to any one of them = here.
 * @param beaconBoostWifiNetworks the "Listen harder" Wi-Fi zone's networks (ZONES): connected to,
 *   or in range of, any one of them. [beaconBoostWifiBssid] / [beaconBoostWifiSsid] are legacy.
 * @param profileId the lock to start; null = Quick lock.
 * @param activeRule which days it's active on; shared with alarm triggers. Not used by
 *   [LocationMode.TIME_SLOTS], whose slots have their own days.
 * @param matchMode legacy (versions 11–12), replaced by [signals] + [requireAll].
 * @param signals which of [PlaceSignal] decide "here" (names). Added in DB version 13.
 * @param requireAll true = every signal must say "here"; false = any one is enough.
 * @param beaconKey the beacon to watch, as [com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules] keys it.
 * @param beaconRssiThreshold "near" means a smoothed signal at or above this (dBm), set by calibrating.
 * @param beaconAddress the beacon's Bluetooth address, when picked as one beacon with all its
 *   formats (ADR 0006); null for places picked before DB version 20.
 * @param beaconOneMeterDbm the beacon's signal 1 m away (announced by it, or measured), for
 *   distance estimates and the "Near means" guide; null = unknown (a typical -59 is assumed).
 * @param beaconPreset how quickly the beacon (or nearby device) reacts: a
 *   [com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconPreset] name, or null for
 *   the default in Settings. With CUSTOM, [beaconArriveSec], [beaconLeaveSec],
 *   [beaconRecheckSec] and [beaconScanSpeed] are its numbers. Added in DB version 21.
 * @param beaconBoost when the beacon scan listens harder before the beacon is heard: a
 *   [com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconBoost] name, null = AUTO
 *   (its own Wi-Fi / GPS / charger signals). With ZONES, [beaconBoostZones] (BoostZone names)
 *   and, for the Wi-Fi zone, [beaconBoostWifiBssid]. The GPS zone uses [latitude] /
 *   [longitude] / [radiusMeters]. Added in DB version 22.
 * @param chargerMode which chargers count for [PlaceSignal.CHARGER].
 * @param btAddress the paired device for [PlaceSignal.BT_DEVICE]; [btName] is for display only.
 * @param btDetection connected to it, or near it; [btRssiThreshold] is "near" for the latter.
 * @param lockOnReturn time slots: lock when you arrive or come back during a slot.
 * @param stayLockedAfterLeaving time slots: leaving keeps the lock until the slot ends, instead
 *   of unlocking.
 */
@Entity(
    tableName = "location_triggers",
    foreignKeys = [
        ForeignKey(
            entity = LockProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class LocationTrigger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    val wifiBssid: String? = null,
    val wifiSsid: String? = null,
    val mode: LocationMode = LocationMode.WHILE_HERE,
    val profileId: Long? = null,
    val activeRule: AlarmActiveRule = AlarmActiveRule.EVERY_DAY,
    val daysMask: Int = LockSchedule.EVERY_DAY,
    val dates: Set<String> = emptySet(),
    @ColumnInfo(defaultValue = "GPS_ONLY") val matchMode: LocationMatch = LocationMatch.GPS_ONLY,
    @ColumnInfo(defaultValue = "1") val lockOnReturn: Boolean = true,
    @ColumnInfo(defaultValue = "0") val stayLockedAfterLeaving: Boolean = false,
    @ColumnInfo(defaultValue = "GPS") val signals: Set<String> = setOf(PlaceSignal.GPS.name),
    @ColumnInfo(defaultValue = "1") val requireAll: Boolean = true,
    val beaconKey: String? = null,
    val beaconName: String? = null,
    @ColumnInfo(defaultValue = "-75") val beaconRssiThreshold: Int = DEFAULT_BEACON_THRESHOLD,
    @ColumnInfo(defaultValue = "ANY") val chargerMode: ChargerMode = ChargerMode.ANY,
    val btAddress: String? = null,
    val btName: String? = null,
    @ColumnInfo(defaultValue = "CONNECTED") val btDetection: BtDetection = BtDetection.CONNECTED,
    @ColumnInfo(defaultValue = "-75") val btRssiThreshold: Int = DEFAULT_BEACON_THRESHOLD,
    val beaconAddress: String? = null,
    val beaconOneMeterDbm: Int? = null,
    val beaconPreset: String? = null,
    val beaconArriveSec: Int? = null,
    val beaconLeaveSec: Int? = null,
    val beaconRecheckSec: Int? = null,
    val beaconScanSpeed: String? = null,
    val beaconBoost: String? = null,
    @ColumnInfo(defaultValue = "") val beaconBoostZones: Set<String> = emptySet(),
    val beaconBoostWifiBssid: String? = null,
    val beaconBoostWifiSsid: String? = null,
    @ColumnInfo(defaultValue = "[]") val wifiNetworks: List<WifiNetwork> = emptyList(),
    @ColumnInfo(defaultValue = "[]") val beaconBoostWifiNetworks: List<WifiNetwork> = emptyList(),
) {
    val signalSet: Set<PlaceSignal> get() = PlaceSignal.entries.filter { it.name in signals }.toSet()

    fun uses(signal: PlaceSignal): Boolean = signal.name in signals

    /** Whether the GPS circle takes part in deciding "here". */
    val usesGps: Boolean get() = uses(PlaceSignal.GPS)

    /** Whether the Wi-Fi network takes part in deciding "here". */
    val usesWifi: Boolean get() = uses(PlaceSignal.WIFI) && wifiNetworks.isNotEmpty()

    val usesBeacon: Boolean get() = uses(PlaceSignal.BEACON) && beaconKey != null

    val usesCharger: Boolean get() = uses(PlaceSignal.CHARGER)

    val usesBtDevice: Boolean get() = uses(PlaceSignal.BT_DEVICE) && btAddress != null

    val usesBtConnected: Boolean get() = usesBtDevice && btDetection == BtDetection.CONNECTED

    val usesBtNearby: Boolean get() = usesBtDevice && btDetection == BtDetection.NEARBY

    companion object {
        const val DEFAULT_RADIUS_METERS = 100
        const val DEFAULT_BEACON_THRESHOLD = -75
        val RADIUS_RANGE = 25..2000
    }
}
