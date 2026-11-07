package com.izzyan.sgdeliveryplanner

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Visual-only position matching. Never use the result for arrival, GPS history or rerouting. */
internal data class NavigationVisualFix(
    val position: GpsPoint,
    val bearingDegrees: Float?,
    val matchedToRoute: Boolean,
    val segmentIndex: Int? = null
)

internal data class NavigationRoadMatch(
    val point: GpsPoint,
    val bearingDegrees: Float,
    val distanceMeters: Double,
    val segmentIndex: Int
)

/**
 * Project a trustworthy GPS fix onto the nearest *directionally compatible* routed road segment.
 * Do not pretend that every GPS fix is on the route: a real wrong turn must remain visible.
 */
internal object NavigationRoadMatcher {
    fun match(
        raw: GpsPoint,
        shape: List<GpsPoint>,
        accuracyMeters: Float,
        speedMetersPerSecond: Float?,
        headingDegrees: Float?
    ): NavigationRoadMatch? {
        if (!raw.valid() || shape.size < 2 || !accuracyMeters.isFinite() ||
            accuracyMeters <= 0f || accuracyMeters > 30f) return null

        val latMeters = 111_195.0
        val lonMeters = latMeters * cos(Math.toRadians(raw.lat)).coerceAtLeast(0.01)
        // Never pull a location more than 24 m across a street / onto a parallel road.
        val tolerance = (accuracyMeters * 1.6).toDouble().coerceIn(9.0, 24.0)
        val headingReliable = speedMetersPerSecond != null &&
            speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 3.0f &&
            headingDegrees != null && headingDegrees.isFinite()
        var nearest: NavigationRoadMatch? = null
        var bestScore = Double.POSITIVE_INFINITY

        for (i in 0 until shape.lastIndex) {
            val a = shape[i]
            val b = shape[i + 1]
            if (!a.valid() || !b.valid()) continue
            val dx = (b.lon - a.lon) * lonMeters
            val dy = (b.lat - a.lat) * latMeters
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared < 1.0) continue
            val px = (raw.lon - a.lon) * lonMeters
            val py = (raw.lat - a.lat) * latMeters
            val t = ((px * dx + py * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(px - t * dx, py - t * dy)
            if (distance > tolerance) continue

            val bearing = normalizeBearing(Math.toDegrees(atan2(dx, dy)).toFloat())
            val headingDifference = if (headingReliable)
                angleDifference(requireNotNull(headingDegrees), bearing) else 0f
            // Opposite carriageway / other side of a U-turn must never be chosen as a match.
            if (headingReliable && headingDifference > 95f) continue
            val score = distance + if (headingReliable) headingDifference * 0.045 else 0.0
            if (score < bestScore) {
                bestScore = score
                nearest = NavigationRoadMatch(
                    GpsPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t),
                    bearing,
                    distance,
                    i
                )
            }
        }
        return nearest
    }

    fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f

    fun angleDifference(a: Float, b: Float): Float {
        val delta = (normalizeBearing(a) - normalizeBearing(b) + 540f) % 360f - 180f
        return kotlin.math.abs(delta)
    }

    fun bearingFromTwoPoints(start: GpsPoint, end: GpsPoint): Float? {
        if (!start.valid() || !end.valid() || navigationDistance(start, end) < 5.0) return null
        val north = (end.lat - start.lat) * 111_195.0
        val east = (end.lon - start.lon) * 111_195.0 * cos(Math.toRadians(end.lat))
        return normalizeBearing(Math.toDegrees(atan2(east, north)).toFloat())
    }
}

/**
 * Smoothly hold the car on the road during modest GPS drift, while preserving all raw fixes
 * for real navigation. A fix far away from the route is deliberately not snapped.
 */
internal class NavigationVisualTracker {
    private var previousRaw: GpsPoint? = null
    private var previousDisplay: GpsPoint? = null
    private var previousHeading: Float? = null

    fun reset() {
        previousRaw = null
        previousDisplay = null
        previousHeading = null
    }

    fun update(
        raw: GpsPoint,
        shape: List<GpsPoint>,
        accuracyMeters: Float,
        speedMetersPerSecond: Float?,
        gpsBearingDegrees: Float?
    ): NavigationVisualFix {
        val moving = speedMetersPerSecond != null && speedMetersPerSecond.isFinite() &&
            speedMetersPerSecond >= 1.5f
        val fromTravel = if (moving) previousRaw?.let {
            NavigationRoadMatcher.bearingFromTwoPoints(it, raw)
        } else null
        val validGpsHeading = gpsBearingDegrees?.takeIf { it.isFinite() && moving }
        val travelHeading = validGpsHeading ?: fromTravel
        val match = NavigationRoadMatcher.match(raw, shape, accuracyMeters, speedMetersPerSecond, travelHeading)

        // Stationary devices wander by several metres. Hold the previously displayed point if
        // the newer fix remains within its accuracy radius; never freeze a vehicle that is moving.
        val previous = previousDisplay
        val holdStill = !moving && previous != null && accuracyMeters.isFinite() &&
            accuracyMeters > 0f && navigationDistance(previous, raw) <=
                (accuracyMeters.toDouble() * 1.6).coerceIn(6.0, 16.0)
        val display = when {
            holdStill -> requireNotNull(previous)
            match != null -> match.point
            else -> raw
        }

        // Route tangent is a fallback when the phone does not report a GPS course.
        // Actual measured travel takes priority when available, especially while turning.
        val targetHeading = if (moving) travelHeading ?: match?.bearingDegrees else null
        val heading = NavigationDisplayTuning.smoothBearing(
            previousHeading, targetHeading, speedMetersPerSecond)
        previousRaw = raw
        previousDisplay = display
        previousHeading = heading
        return NavigationVisualFix(display, heading, match != null || holdStill, match?.segmentIndex)
    }
}
