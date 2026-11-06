package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A driving-camera policy. Values are navigation presentation only, not routing or ETA. */
internal class NavigationCameraPolicy {
    private var appliedZoom: Double? = null
    private var lastChangedAtMs: Long = Long.MIN_VALUE

    fun reset() {
        appliedZoom = null
        lastChangedAtMs = Long.MIN_VALUE
    }

    fun targetZoom(
        speedMetersPerSecond: Float?,
        distanceToManeuverMeters: Double?,
        remainingJourneyMeters: Double?,
        instruction: String
    ): Double {
        val speedKmh = (speedMetersPerSecond ?: 0f).takeIf { it.isFinite() && it >= 0f }
            ?.times(3.6f) ?: 0f
        var zoom = when {
            speedKmh < 5 -> 18.8
            speedKmh < 20 -> 18.65
            speedKmh < 50 -> 18.25
            speedKmh < 80 -> 17.85
            else -> 17.4
        }
        val maneuver = distanceToManeuverMeters?.takeIf { it.isFinite() && it >= 0.0 }
        val instructionLower = instruction.lowercase()
        val needsCare = listOf("roundabout", "exit", "u-turn", "keep left", "keep right")
            .any { instructionLower.contains(it) }
        if (maneuver != null) {
            zoom = when {
                maneuver <= 75.0 -> max(zoom, 19.05)
                maneuver <= 160.0 -> max(zoom, 18.85)
                maneuver <= 300.0 -> max(zoom, if (needsCare) 18.65 else 18.45)
                needsCare && maneuver <= 500.0 -> max(zoom, 18.3)
                else -> zoom
            }
            if (speedKmh >= 80 && maneuver >= 1800) zoom = min(zoom, 17.5)
        }
        val remaining = remainingJourneyMeters?.takeIf { it.isFinite() && it >= 0.0 }
        if (remaining != null && remaining < 180) zoom = max(zoom, if (remaining < 75) 19.2 else 18.85)
        return zoom.coerceIn(16.5, 19.35)
    }

    /**
     * Decide whether to animate. Min 4.5 s between changes (2.5 s for nearby maneuvers)
     * prevents a camera that pumps in and out at speed thresholds.
     */
    fun consume(
        nowElapsedMs: Long,
        speedMetersPerSecond: Float?,
        distanceToManeuverMeters: Double?,
        remainingJourneyMeters: Double?,
        instruction: String
    ): Double? {
        val next = targetZoom(speedMetersPerSecond, distanceToManeuverMeters, remainingJourneyMeters, instruction)
        val last = appliedZoom
        if (last == null) {
            appliedZoom = next
            lastChangedAtMs = nowElapsedMs
            return next
        }
        val approaching = (distanceToManeuverMeters ?: Double.POSITIVE_INFINITY) < 160 ||
            (remainingJourneyMeters ?: Double.POSITIVE_INFINITY) < 160
        val minInterval = if (approaching && next > last) 2500 else 4500
        if (abs(next - last) < 0.32 || nowElapsedMs - lastChangedAtMs < minInterval) return null
        appliedZoom = next
        lastChangedAtMs = nowElapsedMs
        return next
    }
}
