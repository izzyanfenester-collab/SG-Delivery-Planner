package com.izzyan.sgdeliveryplanner

import kotlin.math.min

/** B862 changes PRESENTATION only. The six-state B860/B861 SmartNavigationZoom
 * decision engine, route geometry, actual GPS location and ETA remain untouched.
 *
 * Limit the maximum automatic zoom around exits and turns so that MapLibre's
 * correctly interpolated vehicle does not appear to travel too fast on screen.
 * Never applies to manual zoom, route overview or standard wide view.
 */
internal class BalancedJunctionZoom {
    private enum class SpeedBand { LOW, MEDIUM, HIGH }
    private var band: SpeedBand? = null

    fun reset() { band = null }

    /** Hysteresis avoids speed-noise causing camera zoom oscillations near band edges. */
    private fun speedBand(speedMps: Float?): SpeedBand {
        val speedKph = speedMps?.takeIf { it.isFinite() && it in 0f..48f }
            ?.times(3.6f) ?: return band ?: SpeedBand.MEDIUM
        val next = when (band) {
            SpeedBand.LOW -> if (speedKph > 43f) SpeedBand.MEDIUM else SpeedBand.LOW
            SpeedBand.MEDIUM -> when {
                speedKph >= 75f -> SpeedBand.HIGH
                speedKph <= 30f -> SpeedBand.LOW
                else -> SpeedBand.MEDIUM
            }
            SpeedBand.HIGH -> if (speedKph < 63f) SpeedBand.MEDIUM else SpeedBand.HIGH
            null -> when {
                speedKph >= 75f -> SpeedBand.HIGH
                speedKph <= 35f -> SpeedBand.LOW
                else -> SpeedBand.MEDIUM
            }
        }
        band = next
        return next
    }

    fun apply(
        target: SmartZoomTarget,
        speedMps: Float?,
        expressway: Boolean,
        maneuverKind: SmartManeuverKind,
        corner: NavigationCorner?,
        nextTurnMeters: Double?,
        gpsAccuracyMeters: Float?,
        screenWidthDp: Float?,
        screenHeightDp: Float?
    ): SmartZoomTarget {
        if (target.mode !in setOf(SmartCameraMode.APPROACHING_MANEUVER,
                SmartCameraMode.CRITICAL_JUNCTION_FOCUS,
                SmartCameraMode.MANEUVER_TRANSITION)) return target

        val band = speedBand(speedMps)
        // Use routing-confirmed expressway road identity OR explicit EXIT maneuver;
        // speed alone never labels a road as an expressway.
        val exit = maneuverKind == SmartManeuverKind.EXIT
        val highwayLike = expressway || exit
        val baselineCap = if (highwayLike) when (band) {
            SpeedBand.HIGH -> 17.85
            SpeedBand.MEDIUM -> 17.95
            SpeedBand.LOW -> 18.05
        } else when (band) {
            SpeedBand.HIGH -> 18.00
            SpeedBand.MEDIUM -> 18.18
            SpeedBand.LOW -> 18.30
        }

        var cap = baselineCap
        val consecutive = nextTurnMeters?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { it <= 180.0 } == true
        if (consecutive) cap -= 0.08 // include nearby second junction
        if (corner != null && corner.angleDegrees >= 75f) cap -= 0.06
        if (gpsAccuracyMeters != null && gpsAccuracyMeters.isFinite() &&
            gpsAccuracyMeters > 20f) cap -= 0.08

        val width = screenWidthDp?.takeIf { it.isFinite() && it > 0f }
        val height = screenHeightDp?.takeIf { it.isFinite() && it > 0f }
        if (width != null && width < 360f) cap -= 0.12
        else if (width != null && width >= 430f) cap += 0.05
        if (height != null && height < 620f) cap -= 0.05

        // Medium-close even in critical mode; close manual zoom remains unrestricted.
        val bounded = cap.coerceIn(17.50, 18.40)
        val limited = min(target.zoom, bounded)
        return if (limited < target.zoom) target.copy(zoom = limited) else target
    }
}
