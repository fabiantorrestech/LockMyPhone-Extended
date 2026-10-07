package com.fabiantorrestech.lockmyphone_extended.trigger.location

import java.util.Locale
import java.util.UUID

/** The kinds of Bluetooth beacon the app recognises. */
enum class BeaconType {
    /** Apple iBeacon format: most ESP32 sketches and coin-cell beacons. */
    IBEACON,

    /** Google Eddystone-UID. */
    EDDYSTONE,

    /** The open AltBeacon format (`BE AC` under any company ID). */
    ALTBEACON,

    /** This app's own beacon mode (e.g. on a tablet). */
    APP,

    /** Any Bluetooth device with a fixed address (phones change theirs, so they don't work here). */
    DEVICE,
}

/**
 * One beacon heard during a scan.
 *
 * @param oneMeterPower the signal 1 m away that the beacon says it has (iBeacon, Eddystone,
 *   AltBeacon), in dBm.
 * @param address the Bluetooth address that sent it, so a beacon's formats can be grouped.
 */
data class BeaconSighting(
    val key: String,
    val type: BeaconType,
    val label: String,
    val rssi: Int,
    val oneMeterPower: Int? = null,
    val address: String = "",
)

/**
 * One physical beacon from a scan: every format it sent, from one Bluetooth address (ADR 0006).
 *
 * @param frames the strongest sighting of each recognised format (iBeacon, Eddystone, AltBeacon,
 *   app beacon); a place can match any one of them, or [BeaconRules.deviceKey] for any signal.
 * @param oneMeterPower the 1 m signal one of its formats announces, if any.
 * @param addressRotates its address changes over time, so it can't be matched by address.
 */
data class BeaconGroup(
    val address: String,
    val name: String?,
    val frames: List<BeaconSighting>,
    val strongestRssi: Int,
    val oneMeterPower: Int?,
    val addressRotates: Boolean,
) {
    val deviceKey: String get() = BeaconRules.deviceKey(address)
}

/**
 * What the background check knows about a place's beacon (or nearby device) right now.
 *
 * @param averageDbm the 20 s trimmed mean, or the last one when nothing was heard lately.
 */
data class BeaconStatus(val lastHeardMs: Long, val averageDbm: Double?, val near: Boolean)

/** One signal reading and when it arrived. */
data class Reading(val timeMs: Long, val rssi: Int)

/**
 * The smoothed view of one beacon over time.
 *
 * @param smoothedRssi the trimmed mean of [recent] (dBm), or the last one when nothing's recent.
 * @param recent the readings of the last [BeaconRules.WINDOW_MS].
 * @param aboveSinceMs when the signal went (and stayed) above the threshold.
 * @param belowSinceMs when it dropped below, or stopped being heard.
 */
data class BeaconTrack(
    val smoothedRssi: Double? = null,
    val lastSeenMs: Long = 0,
    val aboveSinceMs: Long? = null,
    val belowSinceMs: Long? = null,
    val near: Boolean = false,
    val recent: List<Reading> = emptyList(),
)

/**
 * Beacon keys and the near/away rule. Pure logic.
 *
 * A beacon's key identifies it the same way a background scan filter does:
 * `ibeacon:<uuid>:<major>:<minor>`, `eddystone:<namespace hex>:<instance hex>`,
 * `altbeacon:<company id>:<id1>:<id2>:<id3>`, `app:<id hex>`, `device:<address>` (any signal
 * from that address, whatever its format; see ADR 0006).
 *
 * Signal strength is noisy (your body, walls and the phone's position change it by several dB),
 * so it's smoothed (a 20 s trimmed mean, like the AltBeacon library's), and "near" / "away"
 * only change after a steady period: the place's [BeaconTiming] (Balanced: 45 s / 2 min).
 */
object BeaconRules {

    /** This app's beacon-mode service UUID (random, fixed). */
    val APP_SERVICE_UUID: UUID = UUID.fromString("5d3a9b2e-6c41-4f8a-9e27-1b0c8d4f6a73")

    /** Eddystone's service UUID (0xFEAA). */
    val EDDYSTONE_SERVICE_UUID: UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

    const val APPLE_COMPANY_ID = 0x004C

    /** How far back the trimmed mean looks. */
    const val WINDOW_MS = 20_000L

    /** With at least this many readings, the highest and lowest 10% are dropped. */
    private const val TRIM_MIN_READINGS = 5

    /** Calibration: the threshold sits this many dB below the median reading at your spot. */
    const val CALIBRATION_MARGIN_DB = 8

    /**
     * A beacon's announced signal at 1 m is only believable in this range. Some beacons put their
     * transmit-power setting there instead (e.g. -3 dBm), which would wreck distance estimates.
     */
    val PLAUSIBLE_ONE_METER_DBM = -100..-30

    fun plausibleOneMeter(dbm: Int?): Int? = dbm?.takeIf { it in PLAUSIBLE_ONE_METER_DBM }

    /** The too-strict warning only uses hearings this recent. */
    const val TOO_STRICT_RECENT_MS = 30L * 60 * 1_000

    /** ...and only when the average is more than this much weaker than the range. */
    const val TOO_STRICT_MARGIN_DB = 5

    /**
     * The range is stricter than what the phone actually hears: the beacon was heard lately, you
     * don't count as here, and its average is clearly weaker than the range. You may never
     * count as here.
     */
    fun rangeTooStrict(status: BeaconStatus?, thresholdDbm: Int, nowMs: Long): Boolean {
        if (status == null || status.near) return false
        val average = status.averageDbm ?: return false
        val heardLately = status.lastHeardMs > 0 && nowMs - status.lastHeardMs < TOO_STRICT_RECENT_MS
        return heardLately && average < thresholdDbm - TOO_STRICT_MARGIN_DB
    }

    fun iBeaconKey(manufacturerData: ByteArray): String? {
        if (manufacturerData.size < 22 || manufacturerData[0] != 0x02.toByte() || manufacturerData[1] != 0x15.toByte()) return null
        val uuid = uuidFrom(manufacturerData, 2)
        val major = u16(manufacturerData, 18)
        val minor = u16(manufacturerData, 20)
        return "ibeacon:$uuid:$major:$minor"
    }

    /** The iBeacon's "measured power": its signal 1 m away (byte 22, signed). Null if missing or implausible. */
    fun iBeaconMeasuredPower(manufacturerData: ByteArray): Int? =
        plausibleOneMeter(manufacturerData.getOrNull(22)?.toInt()?.takeIf { it != 0 })

    /** An Eddystone-UID's signal 1 m away: its TX power at 0 m (byte 1, signed) minus 41 dB. Null if implausible. */
    fun eddystoneOneMeterPower(serviceData: ByteArray): Int? =
        plausibleOneMeter(serviceData.getOrNull(1)?.toInt()?.takeIf { it != 0 }?.let { it - EDDYSTONE_0M_TO_1M_DB })

    private const val EDDYSTONE_0M_TO_1M_DB = 41

    /** AltBeacon: `BE AC`, a 16-byte ID1, 2-byte ID2 and ID3, then the signal at 1 m (signed). */
    fun altBeaconKey(companyId: Int, manufacturerData: ByteArray): String? {
        if (manufacturerData.size < 24 || manufacturerData[0] != 0xBE.toByte() || manufacturerData[1] != 0xAC.toByte()) return null
        return "altbeacon:$companyId:${uuidFrom(manufacturerData, 2)}:${u16(manufacturerData, 18)}:${u16(manufacturerData, 20)}"
    }

    fun altBeaconOneMeterPower(manufacturerData: ByteArray): Int? =
        plausibleOneMeter(manufacturerData.getOrNull(22)?.toInt()?.takeIf { it != 0 })

    fun eddystoneKey(serviceData: ByteArray): String? {
        // Frame type 0x00 = UID: [type][tx power][10-byte namespace][6-byte instance]
        if (serviceData.size < 18 || serviceData[0] != 0x00.toByte()) return null
        return "eddystone:${hex(serviceData, 2, 10)}:${hex(serviceData, 12, 6)}"
    }

    fun appKey(serviceData: ByteArray): String? =
        if (serviceData.size < 4) null else "app:${hex(serviceData, 0, 4)}"

    fun deviceKey(address: String): String = "device:${address.uppercase(Locale.ROOT)}"

    /** The 4 bytes this app advertises in beacon mode. */
    fun appPayload(id: Int): ByteArray = byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte())

    /**
     * The last few characters of [key]'s identity ("A3F21C"), so two beacons of the same kind
     * with the same name still look different in the picker.
     */
    fun shortId(key: String): String =
        key.substringAfter(':').filter { it.isLetterOrDigit() }.takeLast(SHORT_ID_LENGTH).uppercase(Locale.ROOT)

    private const val SHORT_ID_LENGTH = 6

    fun typeOf(key: String): BeaconType? = when (key.substringBefore(':')) {
        "ibeacon" -> BeaconType.IBEACON
        "eddystone" -> BeaconType.EDDYSTONE
        "altbeacon" -> BeaconType.ALTBEACON
        "app" -> BeaconType.APP
        "device" -> BeaconType.DEVICE
        else -> null
    }

    /**
     * The mean of [values] without the highest and lowest 10% (with at least 5 readings), so a
     * single spike or dip doesn't move it. Null without readings.
     */
    fun trimmedMean(values: List<Int>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val drop = if (sorted.size >= TRIM_MIN_READINGS) maxOf(1, sorted.size / 10) else 0
        return sorted.subList(drop, sorted.size - drop).average()
    }

    /** Folds new readings ([readings], empty for "not heard") into [track], with the place's [timing]. */
    fun update(
        track: BeaconTrack,
        readings: List<Int>,
        nowMs: Long,
        thresholdDbm: Int,
        timing: BeaconTiming = BeaconTiming.BALANCED,
    ): BeaconTrack {
        var t = track
        val recent = (t.recent + readings.map { Reading(nowMs, it) }).filter { nowMs - it.timeMs < WINDOW_MS }
        t = t.copy(recent = recent, smoothedRssi = trimmedMean(recent.map { it.rssi }) ?: t.smoothedRssi)
        if (readings.isNotEmpty()) t = t.copy(lastSeenMs = nowMs)
        val heardRecently = t.lastSeenMs > 0 && nowMs - t.lastSeenMs < timing.leaveMs
        val strong = heardRecently && (t.smoothedRssi ?: Double.NEGATIVE_INFINITY) >= thresholdDbm
        return if (strong) {
            val above = t.aboveSinceMs ?: nowMs
            t.copy(aboveSinceMs = above, belowSinceMs = null, near = t.near || nowMs - above >= timing.arriveMs)
        } else {
            // Weak since now, or silent since it was last heard.
            val below = t.belowSinceMs ?: if (heardRecently) nowMs else t.lastSeenMs
            t.copy(aboveSinceMs = null, belowSinceMs = below, near = t.near && nowMs - below < timing.leaveMs)
        }
    }

    /**
     * One deliberate check (Refresh): near or away right now from a single short scan, without the
     * usual [BeaconTiming] waits. [readings] are the scan's readings (empty if not heard); their
     * trimmed mean decides. Later background readings carry on from here with the usual rule.
     */
    fun snapshot(readings: List<Int>, nowMs: Long, thresholdDbm: Int, timing: BeaconTiming = BeaconTiming.BALANCED): BeaconTrack {
        val mean = trimmedMean(readings)
        val recent = readings.map { Reading(nowMs, it) }
        return if (mean != null && mean >= thresholdDbm) {
            BeaconTrack(smoothedRssi = mean, lastSeenMs = nowMs, aboveSinceMs = nowMs - timing.arriveMs, near = true, recent = recent)
        } else {
            // Weak, or silent: away now. A silent beacon has no recent reading for the rule to lean on.
            BeaconTrack(
                smoothedRssi = mean,
                lastSeenMs = if (mean != null) nowMs else 0,
                belowSinceMs = nowMs - timing.leaveMs,
                near = false,
                recent = recent,
            )
        }
    }

    /**
     * Whether a Bluetooth address changes over time, so matching "any signal from this beacon" by
     * address would stop working. Only random addresses can rotate: resolvable-private (top bits
     * 01) and non-resolvable (00) do; random-static (11) doesn't. Public addresses never do.
     *
     * @param isRandom the address type is random (Android 15+ can tell); null when unknown.
     */
    fun addressRotates(address: String, isRandom: Boolean?): Boolean {
        if (isRandom != true) return false
        val firstByte = address.substringBefore(':').toIntOrNull(16) ?: return false
        return firstByte ushr 6 != 0b11
    }

    /** A threshold from calibration readings taken at your spot. */
    fun calibratedThreshold(readings: List<Int>): Int? {
        if (readings.isEmpty()) return null
        val sorted = readings.sorted()
        return (sorted[sorted.size / 2] - CALIBRATION_MARGIN_DB).coerceIn(-100, -30)
    }

    /**
     * Calibrating at the edge of the area that should count: the signal there is at its weakest
     * and dips, so the threshold sits just below its low readings (the 10th percentile, −3 dB).
     */
    fun edgeThreshold(readings: List<Int>): Int? {
        if (readings.isEmpty()) return null
        val sorted = readings.sorted()
        return (sorted[sorted.size / EDGE_PERCENTILE_DIVISOR] - EDGE_MARGIN_DB).coerceIn(-100, -30)
    }

    private const val EDGE_PERCENTILE_DIVISOR = 10
    private const val EDGE_MARGIN_DB = 3

    private fun uuidFrom(bytes: ByteArray, offset: Int): String {
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (bytes[offset + i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return UUID(msb, lsb).toString()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun hex(bytes: ByteArray, offset: Int, length: Int): String =
        (offset until offset + length).joinToString("") { String.format(Locale.ROOT, "%02x", bytes[it]) }
}
