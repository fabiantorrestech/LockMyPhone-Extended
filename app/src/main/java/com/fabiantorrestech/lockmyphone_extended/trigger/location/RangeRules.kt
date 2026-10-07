package com.fabiantorrestech.lockmyphone_extended.trigger.location

import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How much the space in between weakens the signal: the path-loss exponent n. */
enum class Walls(val exponent: Double) {
    /** One open room. */
    OPEN(2.2),

    /** A few interior walls or furniture in the way. */
    SOME(3.0),

    /** Several walls or rooms. */
    MANY(3.5),
}

/** Where the beacon sits in the space. */
enum class BeaconSpot { CENTER, WALL, CORNER }

/**
 * A suggested "Near means" value.
 *
 * @param typedValue what to type (the threshold without its minus), 30–100.
 * @param expectedRssi the signal expected at the farthest point, in dBm.
 * @param clamped the space is too big (or too small) for one beacon: [typedValue] is the limit.
 */
data class RangeSuggestion(val farthestMeters: Double, val expectedRssi: Int, val typedValue: Int, val clamped: Boolean)

/**
 * Turns the size of a space into a "Near means" value. Pure logic.
 *
 * Uses the log-distance path-loss model: `rssi(d) = P1m − 10·n·log10(d)`, where P1m is the
 * signal 1 m from the beacon and n how quickly the space weakens it ([Walls]). The distance
 * that matters is the one to the farthest point that should still count as "near".
 *
 * It's an estimate (signal strength wobbles by about 10 dB), so a margin keeps the edge "near",
 * and calibrating at the edge ([BeaconRules.edgeThreshold]) fine-tunes it.
 */
object RangeRules {

    /** A typical beacon's signal 1 m away when it doesn't say. */
    const val DEFAULT_ONE_METER_DBM = -59

    /** Kept below the expected signal at the edge, for normal swings. */
    const val MARGIN_DB = 6

    /** Closer than this, the model stops meaning much. */
    private const val MIN_DISTANCE_M = 0.5

    /** The typed range of "Near means" (−30…−100 dBm). */
    val TYPED_RANGE = 30..100

    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_INCH = 0.0254

    /** The farthest point of an L × W rectangle from a beacon at [spot]. */
    fun farthestInRoom(lengthM: Double, widthM: Double, spot: BeaconSpot): Double {
        val long = max(lengthM, widthM)
        val short = minOf(lengthM, widthM)
        return when (spot) {
            BeaconSpot.CENTER -> 0.5 * sqrt(long * long + short * short)
            // In the middle of a long wall: the far corners across the room.
            BeaconSpot.WALL -> sqrt(short * short + (long / 2) * (long / 2))
            BeaconSpot.CORNER -> sqrt(long * long + short * short)
        }
    }

    /** The farthest point of a square of [areaM2] (only the area is known). */
    fun farthestInArea(areaM2: Double, spot: BeaconSpot): Double {
        val side = sqrt(areaM2)
        return farthestInRoom(side, side, spot)
    }

    /** Adds the height between the beacon and where the phone usually is. */
    fun withHeightGap(distanceM: Double, heightGapM: Double): Double = sqrt(distanceM * distanceM + heightGapM * heightGapM)

    /** The signal expected [distanceM] away (not closer than [minDistanceM], where the model stops meaning much). */
    fun expectedRssi(oneMeterDbm: Int, walls: Walls, distanceM: Double, minDistanceM: Double = MIN_DISTANCE_M): Double =
        oneMeterDbm - 10 * walls.exponent * log10(max(distanceM, minDistanceM))

    /** What to type so the farthest point still counts as near. */
    fun suggest(oneMeterDbm: Int, walls: Walls, farthestM: Double): RangeSuggestion {
        val expected = expectedRssi(oneMeterDbm, walls, farthestM)
        val raw = -(expected - MARGIN_DB).roundToInt()
        val typed = raw.coerceIn(TYPED_RANGE)
        return RangeSuggestion(farthestM, expected.roundToInt(), typed, clamped = typed != raw)
    }

    /** The path-loss exponent for distance estimates: a typical room. */
    const val ESTIMATE_EXPONENT = 2.2

    /**
     * A rough distance from a signal: the path-loss model solved for d,
     * `10^((P1m − rssi) / (10·n))`. Expect ±30–50%.
     */
    fun estimateDistance(rssi: Double, oneMeterDbm: Int, exponent: Double = ESTIMATE_EXPONENT): Double =
        10.0.pow((oneMeterDbm - rssi) / (10 * exponent))

    /** The signal expected [distanceM] away in a typical room, as "Near means" is typed (30–100). */
    fun typedValueAt(distanceM: Double, oneMeterDbm: Int): Int =
        (-expectedRssi(oneMeterDbm, Walls.OPEN, distanceM, minDistanceM = 0.1)).roundToInt().coerceIn(TYPED_RANGE)

    fun metersOf(meters: Int, centimeters: Int): Double = meters + centimeters / 100.0

    fun metersOfFeet(feet: Int, inches: Int): Double = feet * METERS_PER_FOOT + inches * METERS_PER_INCH

    fun squareMetersOfSquareFeet(squareFeet: Double): Double = squareFeet * METERS_PER_FOOT * METERS_PER_FOOT

    fun cubicMetersOfCubicFeet(cubicFeet: Double): Double = cubicFeet * METERS_PER_FOOT * METERS_PER_FOOT * METERS_PER_FOOT

    /** "5.7 m (18 ft 8 in)". */
    fun formatBoth(meters: Double): String {
        val totalInches = (meters / METERS_PER_INCH).roundToInt()
        return String.format(Locale.getDefault(), "%.1f m (%d ft %d in)", meters, totalInches / 12, totalInches % 12)
    }
}
