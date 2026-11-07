package com.izzyan.sgdeliveryplanner

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Continuous cut of the current route at the same geographic point as the displayed arrow. */
internal data class TraveledRouteSplit(
    val grayBehind: List<GpsPoint>,
    val blueAhead: List<GpsPoint>,
    val completedMeters: Double
)

/**
 * Monotonic, display-only route progress.
 * Does not alter the delivery stop, navigation SDK's progress, ETA or rerouting decisions.
 */
internal class TraveledRouteTracker {
    private var shape: List<GpsPoint> = emptyList()
    private var cumulativeMeters = DoubleArray(0)
    private var completedMeters = -1.0
    private var lastElapsedMs = Long.MIN_VALUE

    fun reset(routeShape: List<GpsPoint>) {
        shape = routeShape
        cumulativeMeters = DoubleArray(routeShape.size)
        for (i in 1 until routeShape.size) {
            cumulativeMeters[i] = cumulativeMeters[i - 1] +
                navigationDistance(routeShape[i - 1], routeShape[i])
        }
        completedMeters = -1.0
        lastElapsedMs = Long.MIN_VALUE
    }

    fun advance(
        matchSegmentIndex: Int?,
        displayedPosition: GpsPoint,
        speedMetersPerSecond: Float?,
        elapsedMs: Long
    ): TraveledRouteSplit? {
        val index = matchSegmentIndex ?: return current()
        if (index !in 0 until shape.lastIndex || !displayedPosition.valid()) return current()
        val a = shape[index]
        val b = shape[index + 1]
        val scale = cos(Math.toRadians(displayedPosition.lat))
        val dx = (b.lon - a.lon) * scale
        val dy = b.lat - a.lat
        val t = if (dx * dx + dy * dy < 1e-20) 0.0 else {
            val x = (displayedPosition.lon - a.lon) * scale
            val y = displayedPosition.lat - a.lat
            ((x * dx + y * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        }
        val proposed = cumulativeMeters[index] +
            (cumulativeMeters[index + 1] - cumulativeMeters[index]) * t
        if (completedMeters < 0.0) {
            completedMeters = proposed
            lastElapsedMs = elapsedMs
        } else {
            val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f } ?: 0f
            if (speed >= 1.2f && proposed > completedMeters) {
                val seconds = if (lastElapsedMs == Long.MIN_VALUE) 1.0
                    else ((elapsedMs - lastElapsedMs).coerceAtLeast(0L) / 1000.0).coerceAtMost(30.0)
                // Prevent a noisy matched fix from suddenly greying kilometres of road.
                val maximumJump = max(65.0, seconds * max(12.0, speed.toDouble()) + 35.0)
                if (proposed - completedMeters <= maximumJump) {
                    completedMeters = proposed
                    lastElapsedMs = elapsedMs
                }
            }
        }
        return current()
    }

    fun current(): TraveledRouteSplit? {
        if (shape.size < 2 || completedMeters < 0.0) return null
        val index = (0 until shape.lastIndex).firstOrNull {
            completedMeters <= cumulativeMeters[it + 1]
        } ?: (shape.lastIndex - 1)
        val segmentLength = cumulativeMeters[index + 1] - cumulativeMeters[index]
        val fraction = if (segmentLength <= 0.0) 0.0
            else ((completedMeters - cumulativeMeters[index]) / segmentLength).coerceIn(0.0, 1.0)
        val a = shape[index]
        val b = shape[index + 1]
        val cut = GpsPoint(
            a.lat + (b.lat - a.lat) * fraction,
            a.lon + (b.lon - a.lon) * fraction
        )
        return TraveledRouteSplit(
            grayBehind = shape.take(index + 1) + cut,
            blueAhead = listOf(cut) + shape.drop(index + 1),
            completedMeters = completedMeters
        )
    }
}
