package com.izzyan.sgdeliveryplanner

import kotlin.math.abs

/** One geographic split shared by the passed (grey) and remaining (blue) route. */
internal data class NavigationRouteSplit(
    val passed: List<GpsPoint>,
    val remaining: List<GpsPoint>,
    val progressMeters: Double
)

/**
 * Keeps passed-road geometry accurate and monotonic. GPS is NOT inferred from the screen.
 * Only direction-compatible road matches are accepted; a true off-route fix leaves the
 * last safe route split unchanged while the navigation engine considers rerouting.
 */
internal class NavigationRouteTrail {
    private var boundRoute: Any? = null
    private var shape: List<GpsPoint> = emptyList()
    private var accumulated = DoubleArray(0)
    private var progressMeters = 0.0
    private var lastLocationTimeMs: Long? = null

    fun ensureRoute(route: Any?, points: List<GpsPoint>) {
        if (boundRoute === route && shape == points) return
        boundRoute = route
        shape = points
        progressMeters = 0.0
        lastLocationTimeMs = null
        accumulated = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            accumulated[i] = accumulated[i - 1] + navigationDistance(shape[i - 1], shape[i])
        }
    }

    fun advance(
        match: NavigationRoadMatch?,
        speedMetersPerSecond: Float?,
        elapsedRealtimeMs: Long
    ): Boolean {
        if (match == null || shape.size < 2 ||
            match.segmentIndex !in 0 until shape.lastIndex ||
            speedMetersPerSecond == null || !speedMetersPerSecond.isFinite() ||
            speedMetersPerSecond < 1f) return false

        val i = match.segmentIndex
        val segmentMeters = accumulated[i + 1] - accumulated[i]
        val proposed = accumulated[i] +
            navigationDistance(shape[i], match.point).coerceIn(0.0, segmentMeters)

        // Snap ambiguity around parallel roads/loops must not skip several streets.
        if (proposed <= progressMeters + 1.5) return false
        val seconds = lastLocationTimeMs?.let {
            ((elapsedRealtimeMs - it).coerceAtLeast(0L) / 1000.0).coerceAtMost(30.0)
        } ?: 2.0
        val plausibleAdvance = maxOf(32.0, speedMetersPerSecond * seconds * 2.0 + 25.0)
        if (lastLocationTimeMs != null && proposed - progressMeters > plausibleAdvance) return false
        progressMeters = proposed.coerceAtMost(accumulated.last())
        lastLocationTimeMs = elapsedRealtimeMs
        return true
    }

    fun split(): NavigationRouteSplit {
        if (shape.size < 2) return NavigationRouteSplit(emptyList(), shape, 0.0)
        val total = accumulated.last()
        if (progressMeters <= 0.0) return NavigationRouteSplit(emptyList(), shape, 0.0)
        if (progressMeters >= total) return NavigationRouteSplit(shape, emptyList(), total)

        var segmentIndex = 0
        while (segmentIndex < shape.lastIndex - 1 &&
            accumulated[segmentIndex + 1] < progressMeters) segmentIndex++
        val segmentMeters = accumulated[segmentIndex + 1] - accumulated[segmentIndex]
        val fraction = if (segmentMeters <= 0.0) 0.0 else
            ((progressMeters - accumulated[segmentIndex]) / segmentMeters).coerceIn(0.0, 1.0)
        val a = shape[segmentIndex]
        val b = shape[segmentIndex + 1]
        val splitPoint = GpsPoint(
            a.lat + (b.lat - a.lat) * fraction,
            a.lon + (b.lon - a.lon) * fraction
        )
        return NavigationRouteSplit(
            shape.take(segmentIndex + 1) + splitPoint,
            listOf(splitPoint) + shape.drop(segmentIndex + 1),
            progressMeters
        )
    }
}
