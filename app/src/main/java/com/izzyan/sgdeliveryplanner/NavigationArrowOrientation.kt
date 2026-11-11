package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Geographic bearings only. In MapLibre RenderMode.GPS the location component
 * performs the map-camera-to-screen transform itself. Do NOT subtract camera
 * bearing or manually rotate the drawable: doing so would double rotate it.
 */
internal object NavigationArrowOrientation {
    fun shortestDelta(from: Float, to: Float): Float {
        val a = NavigationRoadMatcher.normalizeBearing(from)
        val b = NavigationRoadMatcher.normalizeBearing(to)
        return ((b - a + 540f) % 360f) - 180f
    }

    fun toward(from: Float, to: Float, fraction: Float): Float =
        NavigationRoadMatcher.normalizeBearing(
            from + shortestDelta(from, to) * fraction.coerceIn(0f, 1f))

    /**
     * Smooth local tangent across route vertices: bearing between a short
     * point behind and a short point ahead of the matched position.
     * Unlike per-segment bearing it does not jump 90 degrees at a vertex.
     * Actual GPS must corroborate the route; geometry alone never drives a
     * raw location, reroute decision, distance countdown or delivery status.
     */
    fun curveTangent(
        shape: List<GpsPoint>,
        match: NavigationRoadMatch?,
        speedMps: Float?
    ): Float? {
        if (match == null || match.segmentIndex !in 0 until shape.lastIndex) return null
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val behindMeters = (2.0 + speed * 0.13).coerceIn(2.0, 4.8)
        val aheadMeters = (3.4 + speed * 0.24).coerceIn(3.4, 9.0)
        val behind = sample(shape, match, -behindMeters)
        val ahead = sample(shape, match, aheadMeters)
        return NavigationRoadMatcher.bearingFromTwoPoints(behind, ahead)
            ?: match.bearingDegrees
    }

    private fun sample(
        shape: List<GpsPoint>, match: NavigationRoadMatch, signedDistance: Double
    ): GpsPoint {
        var index = match.segmentIndex
        var cursor = match.point
        var remaining = abs(signedDistance)
        val forward = signedDistance >= 0.0
        while (remaining > 0.0001) {
            if (index !in 0 until shape.lastIndex) break
            val next = if (forward) shape[index + 1] else shape[index]
            val segmentMeters = navigationDistance(cursor, next)
            if (segmentMeters > 0.0001) {
                if (remaining <= segmentMeters) {
                    val t = (remaining / segmentMeters).coerceIn(0.0, 1.0)
                    return GpsPoint(cursor.lat + (next.lat - cursor.lat) * t,
                        cursor.lon + (next.lon - cursor.lon) * t)
                }
                remaining -= segmentMeters
            }
            cursor = next
            index += if (forward) 1 else -1
        }
        return cursor
    }

    /**
     * Use the true measured GPS course where available; route tangent removes
     * small wandering errors only when a directionally compatible route match
     * exists. Significant divergence is treated as a potential real wrong turn,
     * never forced back to the blue line.
     */
    fun target(
        gpsCourse: Float?,
        routeCurve: Float?,
        speedMps: Float?
    ): Float? {
        val moving = speedMps != null && speedMps.isFinite() && speedMps >= 1.5f
        if (!moving) return null
        val gps = gpsCourse?.takeIf { it.isFinite() }
            ?.let(NavigationRoadMatcher::normalizeBearing)
        val route = routeCurve?.takeIf { it.isFinite() }
            ?.let(NavigationRoadMatcher::normalizeBearing)
        if (gps == null) return route
        if (route == null) return gps
        val divergence = abs(shortestDelta(route, gps))
        if (divergence > 60f) return gps
        // At low speed favor the actual route curve to avoid noisy course jitter.
        // At higher speed real GPS course has progressively more weight.
        val weight = when {
            speedMps < 4f -> 0.35f
            speedMps < 12f -> 0.52f
            else -> 0.66f
        }
        return toward(route, gps, weight)
    }
}

/** Rate-limited geographic heading tracker. MapLibre's native GPS location
 * component animates the resulting bearing on rendering frames.
 * No new bitmap, camera transition, or Compose recomposition per frame.
 */
internal class NavigationArrowHeadingController {
    private var previous: Float? = null
    private var lastSampleMs: Long? = null

    fun reset() {
        previous = null
        lastSampleMs = null
    }

    fun smooth(
        desiredBearing: Float?,
        speedMps: Float?,
        elapsedMs: Long? = null
    ): Float? {
        val moving = speedMps != null && speedMps.isFinite() && speedMps >= 1.5f
        if (!moving || desiredBearing == null || !desiredBearing.isFinite()) return previous
        val target = NavigationRoadMatcher.normalizeBearing(desiredBearing)
        val old = previous
        if (old == null) {
            previous = target
            lastSampleMs = elapsedMs
            return target
        }
        val realDeltaMs = if (elapsedMs != null && lastSampleMs != null)
            elapsedMs - requireNotNull(lastSampleMs) else 1_000L
        if (realDeltaMs <= 0L) return old
        val dt = (realDeltaMs / 1000.0).coerceIn(0.08, 1.20)
        val delta = NavigationArrowOrientation.shortestDelta(old, target)
        val tau = if (abs(delta) > 38f) 0.45 else 0.82
        val fraction = (1.0 - exp(-dt / tau)).toFloat()
        // A 90- or 180-degree input cannot turn the puck instantly. At the
        // same time smaller bends respond in a single GPS sample.
        val maxDegreesPerSecond = when {
            speedMps < 4f -> 62.0
            speedMps < 15f -> 88.0
            else -> 110.0
        }
        val limited = (delta * fraction).toDouble()
            .coerceIn(-maxDegreesPerSecond * dt, maxDegreesPerSecond * dt)
        val updated = NavigationRoadMatcher.normalizeBearing(old + limited.toFloat())
        previous = if (abs(NavigationArrowOrientation.shortestDelta(updated, target)) < 0.7f)
            target else updated
        lastSampleMs = elapsedMs
        return previous
    }
}
