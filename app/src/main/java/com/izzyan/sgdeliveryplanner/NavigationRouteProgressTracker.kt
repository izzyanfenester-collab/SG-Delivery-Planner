package com.izzyan.sgdeliveryplanner

import kotlin.math.max
import kotlin.math.min

/**
 * Visual-only, monotonic progress along the exact polyline used by the navigation puck.
 * Keeps arrow and travelled/remaining line meeting at the same geographic coordinate.
 * Never changes the raw GPS fix, navigation progress, ETA or delivery data.
 */
internal data class RouteLineFrame(
    val boundary: GpsPoint,
    val traveled: List<GpsPoint>,
    val remaining: List<GpsPoint>,
    val progressedMeters: Double,
    val trustedMatch: Boolean
)

internal class NavigationRouteProgressTracker {
    private var route: List<GpsPoint> = emptyList()
    private var lengths = DoubleArray(0)
    private var progressed = 0.0
    private var lastAcceptedTime: Long? = null
    private var initialized = false

    fun reset(shape: List<GpsPoint>) {
        route = shape.toList()
        lengths = DoubleArray(route.size)
        for (i in 1 until route.size) {
            lengths[i] = lengths[i - 1] + navigationDistance(route[i - 1], route[i])
        }
        progressed = 0.0
        lastAcceptedTime = null
        initialized = false
    }

    fun advance(match: NavigationRoadMatch?, speedMps: Float?, elapsedMs: Long): RouteLineFrame? {
        if (route.size < 2 || lengths.last() <= 0.0) return null
        if (match == null || match.segmentIndex !in 0 until route.lastIndex) return frame(false)
        val index = match.segmentIndex
        val start = route[index]
        val end = route[index + 1]
        // Since match.point is already a perpendicular projection, its measure along
        // the segment is bounded by the actual segment length.
        val distanceOnSegment = navigationDistance(start, match.point)
            .coerceIn(0.0, navigationDistance(start, end))
        val target = (lengths[index] + distanceOnSegment).coerceIn(0.0, lengths.last())
        val motion = speedMps?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        val first = !initialized
        val elapsed = lastAcceptedTime?.let { max(0L, elapsedMs - it) } ?: 1_000L
        val allowedJump = if (first) Double.POSITIVE_INFINITY else max(
            35.0, (motion ?: 10.0) * (elapsed / 1000.0).coerceIn(1.0, 12.0) * 2.2 + 25.0
        )
        // Deliberately ignore an impossible forward jump until subsequent GPS fixes
        // corroborate it. This avoids coloring hundreds of metres grey on one glitch.
        if (target > progressed + allowedJump) return frame(false)
        if (first || target > progressed) {
            progressed = target
            initialized = true
            lastAcceptedTime = elapsedMs
        }
        // Small backward GPS jitter never restores grey segments to blue; keep the car
        // at the very same boundary. Large backwards divergence is left unsnapped.
        return frame(first || target >= progressed - 35.0)
    }

    fun current(): RouteLineFrame? = if (route.size < 2) null else frame(false)

    private fun frame(trusted: Boolean): RouteLineFrame {
        val measure = progressed.coerceIn(0.0, lengths.last())
        if (measure <= 0.00001) return RouteLineFrame(
            route.first(), emptyList(), route, 0.0, trusted)
        if (measure >= lengths.last() - 0.00001) return RouteLineFrame(
            route.last(), route, emptyList(), lengths.last(), trusted)

        // Find segment containing the progress point and SPLIT both polylines there.
        var seg = 0
        while (seg < route.lastIndex - 1 && lengths[seg + 1] < measure) seg++
        val span = lengths[seg + 1] - lengths[seg]
        val fraction = if (span < 0.00001) 0.0 else
            ((measure - lengths[seg]) / span).coerceIn(0.0, 1.0)
        val a = route[seg]
        val b = route[seg + 1]
        val boundary = GpsPoint(
            a.lat + (b.lat - a.lat) * fraction,
            a.lon + (b.lon - a.lon) * fraction
        )
        val behind = route.take(seg + 1) + boundary
        val ahead = listOf(boundary) + route.drop(seg + 1)
        return RouteLineFrame(boundary, behind, ahead, measure, trusted)
    }
}
