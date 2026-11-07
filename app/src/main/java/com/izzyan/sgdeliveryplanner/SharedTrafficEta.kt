package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Applies ONLY credible, direction-matched runner traffic ahead to the existing
 * route ETA; unknown roads retain the established routing/phone-speed estimate.
 * No external traffic feed, guessed road coverage or instantaneous speed-only ETA.
 */
internal object SharedTrafficEta {
    fun estimate(
        baselineSeconds: Double,
        route: List<GpsPoint>,
        remainingMeters: Double,
        observed: List<SharedTrafficSegment>
    ): Double {
        if (!baselineSeconds.isFinite() || baselineSeconds <= 0 ||
            !remainingMeters.isFinite() || remainingMeters <= 0 ||
            route.size < 2 || observed.isEmpty()) return baselineSeconds
        val total = route.zipWithNext().sumOf { (a, b) -> navigationDistance(a, b) }
        if (total < 10) return baselineSeconds
        val progress = (total - remainingMeters).coerceIn(0.0, total)
        var cumulative = 0.0
        var correction = 0.0
        var matched = 0
        val verified = observed.filter {
            it.level != LocalTrafficLevel.UNKNOWN &&
                it.speedKph != null && it.speedKph > 2.0 &&
                it.freeFlowKph > 5.0
        }
        for ((start, end) in route.zipWithNext()) {
            val length = navigationDistance(start, end)
            val distanceAtEnd = cumulative + length
            val startAhead = max(cumulative, progress + 70.0)
            val remainingPart = (distanceAtEnd - startAhead).coerceIn(0.0, length)
            cumulative = distanceAtEnd
            if (remainingPart <= 0 || length <= 0.1) continue
            val midpoint = GpsPoint((start.lat + end.lat) / 2.0,
                (start.lon + end.lon) / 2.0)
            val routeBearing = heading(start, end)
            val matching = verified.asSequence().filter { segment ->
                directionDifference(routeBearing, heading(segment.start, segment.end)) < 40.0 &&
                    distanceFromNavigationRoute(midpoint, listOf(segment.start, segment.end)) <= 30.0
            }.minByOrNull { segment ->
                distanceFromNavigationRoute(midpoint, listOf(segment.start, segment.end))
            } ?: continue
            val actual = requireNotNull(matching.speedKph)
            val freeFlow = matching.freeFlowKph
            // Delta travel time (seconds), applied only to future route geometry.
            // Never extend a short observed road edge across an unobserved long route line.
            val coveredMeters = min(remainingPart,
                navigationDistance(matching.start, matching.end))
            correction += coveredMeters * 3.6 * (1.0 / actual - 1.0 / freeFlow)
            matched++
        }
        if (matched == 0 || !correction.isFinite()) return baselineSeconds
        return (baselineSeconds + correction)
            .coerceIn(baselineSeconds * 0.75, baselineSeconds * 2.5)
            .coerceAtLeast(0.0)
    }

    private fun heading(a: GpsPoint, b: GpsPoint): Double {
        val angle = atan2((b.lon - a.lon) *
            cos(Math.toRadians((a.lat + b.lat) / 2.0)), b.lat - a.lat)
        return (Math.toDegrees(angle) + 360.0) % 360.0
    }

    private fun directionDifference(a: Double, b: Double): Double {
        val difference = abs((a - b + 360.0) % 360.0)
        return min(difference, 360.0 - difference)
    }
}
