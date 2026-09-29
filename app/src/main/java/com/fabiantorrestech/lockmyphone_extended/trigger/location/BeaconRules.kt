package com.fabiantorrestech.lockmyphone_extended.trigger.location

import java.util.Locale
import java.util.UUID

/** The kinds of Bluetooth beacon the app recognises. */
enum class BeaconType {
    /** Apple iBeacon format: most ESP32 sketches and coin-cell beacons. */
    IBEACON,

    /** Google Eddystone-UID. */
    EDDYSTONE,

    /** This app's own beacon mode (e.g. on a tablet). */
    APP,

    /** Any Bluetooth device with a fixed address (phones change theirs, so they don't work here). */
    DEVICE,
}

/** One beacon heard during a scan. */
data class BeaconSighting(val key: String, val type: BeaconType, val label: String, val rssi: Int)

/**
 * The smoothed view of one beacon over time.
 *
 * @param smoothedRssi exponential moving average of the signal (dBm).
 * @param aboveSinceMs when the signal went (and stayed) above the threshold.
 * @param belowSinceMs when it dropped below, or stopped being heard.
 */
data class BeaconTrack(
    val smoothedRssi: Double? = null,
    val lastSeenMs: Long = 0,
    val aboveSinceMs: Long? = null,
    val belowSinceMs: Long? = null,
    val near: Boolean = false,
)

/**
 * Beacon keys and the near/away rule. Pure logic.
 *
 * A beacon's key identifies it the same way a background scan filter does:
 * `ibeacon:<uuid>:<major>:<minor>`, `eddystone:<namespace hex>:<instance hex>`, `app:<id hex>`,
 * `device:<address>`.
 *
 * Signal strength is noisy (your body, walls and the phone's position change it by several dB),
 * so it's smoothed, and "near" / "away" only change after a steady period.
 */
object BeaconRules {

    /** This app's beacon-mode service UUID (random, fixed). */
    val APP_SERVICE_UUID: UUID = UUID.fromString("5d3a9b2e-6c41-4f8a-9e27-1b0c8d4f6a73")

    /** Eddystone's service UUID (0xFEAA). */
    val EDDYSTONE_SERVICE_UUID: UUID = UUID.fromString("0000feaa-0000-1000-8000-00805f9b34fb")

    const val APPLE_COMPANY_ID = 0x004C

    /** Near only after the signal has been strong for this long. */
    const val DWELL_MS = 45_000L

    /** Away after the signal has been weak (or absent) for this long. */
    const val LEAVE_MS = 2L * 60 * 1_000

    /** Weight of a new reading in the moving average. */
    private const val SMOOTHING = 0.3

    /** Calibration: the threshold sits this many dB below the median reading at your spot. */
    const val CALIBRATION_MARGIN_DB = 8

    fun iBeaconKey(manufacturerData: ByteArray): String? {
        if (manufacturerData.size < 22 || manufacturerData[0] != 0x02.toByte() || manufacturerData[1] != 0x15.toByte()) return null
        val uuid = uuidFrom(manufacturerData, 2)
        val major = u16(manufacturerData, 18)
        val minor = u16(manufacturerData, 20)
        return "ibeacon:$uuid:$major:$minor"
    }

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

    fun typeOf(key: String): BeaconType? = when (key.substringBefore(':')) {
        "ibeacon" -> BeaconType.IBEACON
        "eddystone" -> BeaconType.EDDYSTONE
        "app" -> BeaconType.APP
        "device" -> BeaconType.DEVICE
        else -> null
    }

    /** Folds one reading ([rssi], or null for "not heard") into [track]. */
    fun update(track: BeaconTrack, rssi: Int?, nowMs: Long, thresholdDbm: Int): BeaconTrack {
        var t = track
        if (rssi != null) {
            val smoothed = t.smoothedRssi?.let { SMOOTHING * rssi + (1 - SMOOTHING) * it } ?: rssi.toDouble()
            t = t.copy(smoothedRssi = smoothed, lastSeenMs = nowMs)
        }
        val heardRecently = t.lastSeenMs > 0 && nowMs - t.lastSeenMs < LEAVE_MS
        val strong = heardRecently && (t.smoothedRssi ?: Double.NEGATIVE_INFINITY) >= thresholdDbm
        return if (strong) {
            val above = t.aboveSinceMs ?: nowMs
            t.copy(aboveSinceMs = above, belowSinceMs = null, near = t.near || nowMs - above >= DWELL_MS)
        } else {
            // Weak since now, or silent since it was last heard.
            val below = t.belowSinceMs ?: if (heardRecently) nowMs else t.lastSeenMs
            t.copy(aboveSinceMs = null, belowSinceMs = below, near = t.near && nowMs - below < LEAVE_MS)
        }
    }

    /** A threshold from calibration readings taken at your spot. */
    fun calibratedThreshold(readings: List<Int>): Int? {
        if (readings.isEmpty()) return null
        val sorted = readings.sorted()
        return sorted[sorted.size / 2] - CALIBRATION_MARGIN_DB
    }

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
