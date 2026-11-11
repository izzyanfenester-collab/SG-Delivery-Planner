package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Route geometry preview: never inferred from an unverified street name.
 * Detects the first meaningful change of road bearing ahead of a matched
 * vehicle, including progressive curves without a single sharp vertex.
 */
internal data class NavigationCorner(val distanceMeters: Double, val angleDegrees: Float)

internal object NavigationCornerForecast {
    fun next(shape: List<GpsPoint>, match: NavigationRoadMatch?, horizonMeters: Double = 450.0): NavigationCorner? {
        if (shape.size < 3 || match == null || match.segmentIndex !in 0 until shape.lastIndex)
            return null
        val index = match.segmentIndex
        val initial = NavigationRoadMatcher.bearingFromTwoPoints(shape[index], shape[index + 1])
            ?: return null
        var along = navigationDistance(match.point, shape[index + 1])
        var candidate: NavigationCorner? = null
        for (i in index + 1 until shape.lastIndex) {
            if (along > horizonMeters) break
            val segmentLength = navigationDistance(shape[i], shape[i + 1])
            if (segmentLength < 0.5) continue
            val bearing = NavigationRoadMatcher.bearingFromTwoPoints(shape[i], shape[i + 1])
            if (bearing != null) {
                val difference = NavigationRoadMatcher.angleDifference(initial, bearing)
                if (difference >= 32.0f) {
                    // Avoid targeting tiny one-point kinks in supplied polylines.
                    val requiredPersistence = (i + 2).coerceAtMost(shape.lastIndex)
                    val forward = NavigationRoadMatcher.bearingFromTwoPoints(
                        shape[i], shape[requiredPersistence])
                    if (forward != null && NavigationRoadMatcher.angleDifference(initial, forward) >= 27f) {
                        candidate = NavigationCorner(along.coerceAtLeast(0.0), difference)
                        break
                    }
                }
            }
            along += segmentLength
        }
        return candidate
    }
}

/** One source of zoom and pitch decisions; MapLibre's native location component
 * remains the ONLY owner of continuous position/bearing camera tracking.
 */
internal data class NavigationCameraCommand(
    val zoom: Double? = null,
    val pitch: Double? = null,
    val durationMs: Long = 650L
)

internal class NavigationCameraDirector {
    private var previousZoom: Double? = null
    private var previousPitch: Double? = null
    private var lastChangeMs: Long? = null

    fun reset(zoom: Double? = null, pitch: Double? = null) {
        previousZoom = zoom?.takeIf { it.isFinite() }
        previousPitch = pitch?.takeIf { it.isFinite() }
        lastChangeMs = null
    }

    /** Exactly one native camera property animation at a time; the caller
     * never starts independent zoom and pitch animations concurrently.
     * Rate limit longer than the animation prevents cancellation / flicker.
     */
    fun choose(
        nowMs: Long,
        currentZoom: Double,
        currentPitch: Double,
        speedMps: Float?,
        turnMeters: Double?,
        instruction: String,
        remainingMeters: Double?,
        corner: NavigationCorner?,
        threeDimensional: Boolean,
        manualZoom: Double?
    ): NavigationCameraCommand? {
        if (!currentZoom.isFinite() || !currentPitch.isFinite()) return null
        val previousMs = lastChangeMs
        if (previousMs != null && nowMs - previousMs in 0..749L) return null

        val naturalTurn = listOfNotNull(turnMeters?.takeIf { it.isFinite() && it >= 0.0 },
            corner?.distanceMeters).minOrNull()
        val targetZoom = (manualZoom ?: NavigationCameraPolicy.targetZoom(
            speedMps, naturalTurn, if (corner != null) "$instruction junction" else instruction,
            remainingMeters)).coerceIn(16.0, 19.2)
        val approachCurve = threeDimensional && naturalTurn != null &&
            naturalTurn < 180.0 && (corner?.angleDegrees ?: 45f) >= 28f
        val targetPitch = if (!threeDimensional) 0.0 else if (approachCurve) 49.0 else 54.0
        val baseZoom = (previousZoom ?: currentZoom).takeIf { abs(it - currentZoom) < 1.3 } ?: currentZoom
        val basePitch = (previousPitch ?: currentPitch).takeIf { abs(it - currentPitch) < 13.0 } ?: currentPitch
        val deltaZoom = targetZoom - baseZoom
        val deltaPitch = targetPitch - basePitch
        val command = when {
            abs(deltaZoom) >= 0.19 -> NavigationCameraCommand(
                zoom = (baseZoom + deltaZoom.coerceIn(-0.42, 0.42)).coerceIn(16.0, 19.2),
                durationMs = 650
            )
            abs(deltaPitch) >= 3.0 -> NavigationCameraCommand(
                pitch = (basePitch + deltaPitch.coerceIn(-5.0, 5.0)).coerceIn(0.0, 60.0),
                durationMs = 650
            )
            else -> return null
        }
        previousZoom = command.zoom ?: baseZoom
        previousPitch = command.pitch ?: basePitch
        lastChangeMs = nowMs
        return command
    }
}

/** Rejects grossly impossible one-off jumps only; raw accepted coordinates
 * remain the authoritative input for navigation, ETA, arrival, and reroute.
 * A reacquired GPS signal after a genuine outage is allowed immediately.
 */
internal data class NavigationGpsSample(
    val point: GpsPoint, val elapsedMs: Long, val accuracyM: Float, val speedMps: Float?
)

internal class NavigationGpsSanityFilter {
    private var previous: NavigationGpsSample? = null

    fun reset() { previous = null }

    fun accept(sample: NavigationGpsSample): Boolean {
        if (!sample.point.valid() || !sample.accuracyM.isFinite() ||
            sample.accuracyM !in 0f..50f) return false
        val old = previous
        if (old != null) {
            val delta = sample.elapsedMs - old.elapsedMs
            if (delta <= 0) return false // stale or reordered GPS fixes
            if (delta < 12_000L) {
                val time = delta / 1000.0
                val speed = max(sample.speedMps?.takeIf { it.isFinite() && it >= 0 }?.toDouble() ?: 0.0,
                    old.speedMps?.takeIf { it.isFinite() && it >= 0 }?.toDouble() ?: 0.0)
                val allowedMeters = max(70.0, speed * 2.4 + 28.0) * time +
                    (sample.accuracyM + old.accuracyM).coerceAtMost(40f)
                if (navigationDistance(old.point, sample.point) > allowedMeters) return false
            }
        }
        previous = sample
        return true
    }
}
