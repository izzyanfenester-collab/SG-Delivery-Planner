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
    private val smart = SmartNavigationZoom()
    private var previousZoom: Double? = null
    private var previousPitch: Double? = null
    private var lastChangeMs: Long? = null
    private var lastDurationMs = 0L
    var focusFraction: Double = 0.44
        private set
    val mode: SmartCameraMode get() = smart.mode

    fun reset(zoom: Double? = null, pitch: Double? = null) {
        previousZoom = zoom?.takeIf { it.isFinite() }
        previousPitch = pitch?.takeIf { it.isFinite() }
        lastChangeMs = null
        lastDurationMs = 0L
        focusFraction = 0.44
        smart.reset()
    }

    /** MapLibre location owns geographical tracking; director only animates one
     * camera property at a time. Neither voice nor routing waits for it.
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
        manualZoom: Double?,
        roadName: String?,
        maneuverType: String?,
        followingTurnMeters: Double?,
        maneuverIndex: Int,
        progressedMeters: Double?,
        trustedMatch: Boolean,
        gpsAccuracyMeters: Float?,
        offRoute: Boolean,
        smartEnabled: Boolean
    ): NavigationCameraCommand? {
        if (offRoute) { reset(currentZoom, currentPitch); return null }
        if (!smartEnabled || manualZoom != null || !currentZoom.isFinite() ||
            !currentPitch.isFinite()) return null
        val target = smart.target(SmartZoomInput(
            nowMs=nowMs, speedMps=speedMps, distanceMeters=turnMeters,
            followingTurnMeters=followingTurnMeters,
            kind=SmartManeuverClassifier.kind(maneuverType,instruction),
            expressway=SmartManeuverClassifier.isExpressway(roadName),
            corner=corner, remainingMeters=remainingMeters,
            stepIndex=maneuverIndex, progressMeters=progressedMeters,
            matched=trustedMatch, offRoute=offRoute,
            threeDimensional=threeDimensional, accuracyMeters=gpsAccuracyMeters
        )) ?: return null
        focusFraction=target.forwardPaddingFraction
        val previousMs=lastChangeMs
        if (previousMs!=null && nowMs-previousMs in 0..(lastDurationMs+90L)) return null

        val baseZoom=(previousZoom ?: currentZoom).takeIf { abs(it-currentZoom)<1.75 } ?: currentZoom
        val basePitch=(previousPitch ?: currentPitch).takeIf { abs(it-currentPitch)<13.0 } ?: currentPitch
        val deltaZoom=target.zoom-baseZoom
        val deltaPitch=target.pitch-basePitch
        val command=when {
            abs(deltaZoom)>=0.17 -> NavigationCameraCommand(
                zoom=(baseZoom+deltaZoom.coerceIn(
                    if(target.urgent) -1.4 else -1.0,
                    if(target.urgent) 1.4 else 1.0)).coerceIn(13.8,19.2),
                durationMs=target.durationMs)
            abs(deltaPitch)>=2.5 -> NavigationCameraCommand(
                pitch=(basePitch+deltaPitch.coerceIn(-7.0,7.0)).coerceIn(0.0,60.0),
                durationMs=target.durationMs)
            else -> return null
        }
        previousZoom=command.zoom ?: baseZoom
        previousPitch=command.pitch ?: basePitch
        lastChangeMs=nowMs
        lastDurationMs=command.durationMs
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
