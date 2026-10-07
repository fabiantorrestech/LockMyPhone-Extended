package com.fabiantorrestech.lockmyphone_extended.trigger.location

/** How hard the background scan listens: faster finds a beacon sooner, and costs more battery. */
enum class BeaconScanSpeed(
    /** How long the phone collects sightings before delivering them. */
    val reportDelayMs: Long,
) {
    LOW_POWER(5_000),
    BALANCED(2_000),
    LOW_LATENCY(1_000),
}

/** How quickly beacon places react. CUSTOM uses its own numbers (Advanced). */
enum class BeaconPreset { BATTERY, BALANCED, FAST, CUSTOM }

/**
 * How quickly a beacon place reacts. Pure.
 *
 * @param arriveMs the signal must stay strong this long before you count as here.
 * @param leaveMs weak or silent this long before you count as gone.
 * @param recheckMs while near, how often the app wakes to notice silence (Android may stretch
 *   this while the phone is idle).
 * @param scanSpeed the background scan while this place is boosted (its beacon was heard, or
 *   its zone says you're close); otherwise the shared scan stays at low power ([ScanPlan]).
 * @param batchMs how long sightings are collected before the app is woken, while boosted.
 */
data class BeaconTiming(
    val arriveMs: Long,
    val leaveMs: Long,
    val recheckMs: Long,
    val scanSpeed: BeaconScanSpeed,
    val batchMs: Long = scanSpeed.reportDelayMs,
) {
    companion object {
        /** Today's behaviour before M18. */
        val BALANCED = BeaconTiming(45_000, 2 * MINUTE_MS, 2 * MINUTE_MS, BeaconScanSpeed.LOW_POWER)
        /** Fewer wake-ups while near: sightings are delivered every 15 s instead of 5 s. */
        val BATTERY = BeaconTiming(60_000, 5 * MINUTE_MS, 5 * MINUTE_MS, BeaconScanSpeed.LOW_POWER, batchMs = 15_000)
        val FAST = BeaconTiming(15_000, 45_000, MINUTE_MS, BeaconScanSpeed.BALANCED)

        val ARRIVE_RANGE_SEC = 10..120
        val LEAVE_RANGE_SEC = 30..600
        val RECHECK_RANGE_SEC = 60..600

        fun of(preset: BeaconPreset, custom: BeaconTiming): BeaconTiming = when (preset) {
            BeaconPreset.BATTERY -> BATTERY
            BeaconPreset.BALANCED -> BALANCED
            BeaconPreset.FAST -> FAST
            BeaconPreset.CUSTOM -> custom.clamped()
        }

        /**
         * A place's timing: its own preset (and custom numbers), or the default when it has none.
         *
         * @param placePreset the place's preset name, null = use the default.
         */
        fun resolve(
            placePreset: String?,
            arriveSec: Int?,
            leaveSec: Int?,
            recheckSec: Int?,
            scanSpeed: String?,
            default: BeaconTiming,
        ): BeaconTiming {
            val preset = BeaconPreset.entries.firstOrNull { it.name == placePreset } ?: return default
            val custom = BeaconTiming(
                arriveMs = (arriveSec ?: (default.arriveMs / 1_000).toInt()) * 1_000L,
                leaveMs = (leaveSec ?: (default.leaveMs / 1_000).toInt()) * 1_000L,
                recheckMs = (recheckSec ?: (default.recheckMs / 1_000).toInt()) * 1_000L,
                scanSpeed = BeaconScanSpeed.entries.firstOrNull { it.name == scanSpeed } ?: default.scanSpeed,
            )
            return of(preset, custom)
        }

        /**
         * The shared background scan (adaptive): low power with 5 s batches until a place is
         * boosted, then the fastest speed and shortest batch among the [boosted] places' timings.
         */
        fun scanPlan(boosted: List<BeaconTiming>): ScanPlan =
            if (boosted.isEmpty()) {
                ScanPlan.BASE
            } else {
                ScanPlan(boosted.maxOf { it.scanSpeed }, boosted.minOf { it.batchMs })
            }

        private const val MINUTE_MS = 60_000L
    }

    /** Within the allowed ranges. */
    fun clamped(): BeaconTiming = copy(
        arriveMs = (arriveMs / 1_000).toInt().coerceIn(ARRIVE_RANGE_SEC) * 1_000L,
        leaveMs = (leaveMs / 1_000).toInt().coerceIn(LEAVE_RANGE_SEC) * 1_000L,
        recheckMs = (recheckMs / 1_000).toInt().coerceIn(RECHECK_RANGE_SEC) * 1_000L,
    )
}

/** The shared background scan: how hard it listens and how often it delivers sightings. */
data class ScanPlan(val speed: BeaconScanSpeed, val batchMs: Long) {
    companion object {
        /** When no beacon place is boosted: listen lightly, deliver every 5 s. */
        val BASE = ScanPlan(BeaconScanSpeed.LOW_POWER, BeaconScanSpeed.LOW_POWER.reportDelayMs)
    }
}

/** When a beacon place listens harder before its beacon is heard. */
enum class BeaconBoost {
    /** When the place's own Wi-Fi, GPS or charger signal says you're close (the default). */
    AUTO,

    /** Only once its beacon has been heard. */
    HEARD_ONLY,

    /** When one of the zones chosen for it says you're close. */
    ZONES,
}

/** A cheaper signal that says you're probably close to a beacon place. */
enum class BoostZone { WIFI, GPS, CHARGER }

/** Whether a beacon place is boosted. Pure. */
object BoostRules {

    /** Extra margin around a place's GPS circle that still counts as "close" for listening harder. */
    const val GPS_ZONE_MARGIN_METERS = 100

    /**
     * @param heardRecently its beacon is near, or was heard lately (until it counts as gone).
     * @param zoneActive one of its zones says you're close.
     */
    fun isBoosted(heardRecently: Boolean, zoneActive: Boolean): Boolean = heardRecently || zoneActive

    /**
     * The zones a place uses: AUTO takes them from its own presence signals, ZONES from its
     * choice, HEARD_ONLY none.
     */
    fun zonesFor(boost: BeaconBoost, chosen: Set<BoostZone>, usesWifi: Boolean, usesGps: Boolean, usesCharger: Boolean): Set<BoostZone> =
        when (boost) {
            BeaconBoost.AUTO -> setOfNotNull(
                BoostZone.WIFI.takeIf { usesWifi },
                BoostZone.GPS.takeIf { usesGps },
                BoostZone.CHARGER.takeIf { usesCharger },
            )
            BeaconBoost.HEARD_ONLY -> emptySet()
            BeaconBoost.ZONES -> chosen
        }
}
