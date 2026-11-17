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

/** B861: issue a single native, frame-interpolated MapLibre zoom animation to the
 * FULL target, never a series of incremental zoom commands. While running,
 * location/heading continue to be owned by the LocationComponent.
 *
 * A changed target retargets MapLibre from its actual current camera zoom,
 * without queueing zoom commands. Stable targets cannot restart on each GPS fix.
 */
internal class CinematicZoomCoordinator {
    private var issuedTarget: Double? = null
    private var issuedAtMs: Long? = null
    private var issuedDurationMs: Long = 0L

    fun reset() {
        issuedTarget = null
        issuedAtMs = null
        issuedDurationMs = 0L
    }

    fun isAnimating(nowMs: Long): Boolean {
        val start = issuedAtMs ?: return false
        return nowMs >= start && nowMs - start < issuedDurationMs
    }

    fun next(nowMs: Long, actualZoom: Double, target: SmartZoomTarget): NavigationCameraCommand? {
        if (!actualZoom.isFinite() || !target.zoom.isFinite()) return null
        val deadZone = if (target.urgent) 0.10 else 0.20
        val last = issuedTarget
        val start = issuedAtMs
        val differenceFromActual = abs(target.zoom - actualZoom)

        if (last != null && abs(last - target.zoom) < deadZone) {
            // GPS updates during an existing native animation do NOT restart it.
            // After completion, tolerate small residuals and avoid flickering.
            if (isAnimating(nowMs) || differenceFromActual < 0.28) return null
            // Recover if the SDK/system interrupted an animation unexpectedly.
            if (start != null && nowMs >= start &&
                nowMs - start < issuedDurationMs + 400L) return null
        } else {
            if (differenceFromActual < deadZone) return null
            // Normal changes are rate-limited, critical exit/junction targets are not.
            if (!target.urgent && start != null && nowMs >= start &&
                nowMs - start < 400L) return null
        }

        val duration = when (target.mode) {
            SmartCameraMode.EXPRESSWAY_WIDE -> 2200L
            SmartCameraMode.POST_MANEUVER_WIDE -> 2200L
            SmartCameraMode.NORMAL_FOLLOW -> if (target.zoom < actualZoom) 1850L else 1500L
            SmartCameraMode.APPROACHING_MANEUVER -> if (target.urgent) 900L else 1350L
            SmartCameraMode.CRITICAL_JUNCTION_FOCUS ->
                if (differenceFromActual > 2.0) 700L else 550L
            SmartCameraMode.MANEUVER_TRANSITION -> 600L
        }
        issuedTarget = target.zoom
        issuedAtMs = nowMs
        issuedDurationMs = duration
        return NavigationCameraCommand(zoom = target.zoom, durationMs = duration)
    }
}

/** Single decision path for automatic zoom and tilt. The six-state B860
 * SmartNavigationZoom controller is preserved completely unchanged.
 */
internal class NavigationCameraDirector {
    private val smart = SmartNavigationZoom()
    private val cinematic = CinematicZoomCoordinator()
    private var issuedPitch: Double? = null
    private var pitchIssuedAtMs: Long? = null
    private var pitchDurationMs: Long = 0L
    var focusFraction: Double = 0.44
        private set
    val mode: SmartCameraMode get() = smart.mode

    @Suppress("UNUSED_PARAMETER")
    fun reset(zoom: Double? = null, pitch: Double? = null) {
        cinematic.reset()
        issuedPitch = null
        pitchIssuedAtMs = null
        pitchDurationMs = 0L
        focusFraction = 0.44
        smart.reset()
    }

    /** MapLibre tracking owns position/bearing. Each returned zoom command animates
     * directly from native.cameraPosition.zoom to the FULL smart target, not a step.
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
        roadName: String? = null,
        maneuverType: String? = null,
        followingTurnMeters: Double? = null,
        maneuverIndex: Int = 0,
        progressedMeters: Double? = null,
        trustedMatch: Boolean = false,
        gpsAccuracyMeters: Float? = null,
        offRoute: Boolean = false,
        smartEnabled: Boolean = true
    ): NavigationCameraCommand? {
        if (offRoute) { reset(currentZoom, currentPitch); return null }
        if (!smartEnabled || manualZoom != null || !currentZoom.isFinite() ||
            !currentPitch.isFinite()) {
            // A manual zoom or camera-mode change immediately relinquishes auto zoom.
            cinematic.reset()
            return null
        }
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
        focusFraction = target.forwardPaddingFraction

        // Native LocationComponent.zoomWhileTracking performs the single continuous
        // interpolation, retaining heading-up, puck snapping and route overlays.
        cinematic.next(nowMs, currentZoom, target)?.let { return it }
        // Tilt should not compete with an active, long-running native zoom.
        if (cinematic.isAnimating(nowMs)) return null

        if (abs(target.pitch - currentPitch) < 2.5) return null
        val previous = issuedPitch
        val since = pitchIssuedAtMs
        if (previous != null && abs(previous - target.pitch) < 1.0 && since != null &&
            nowMs >= since && nowMs - since < pitchDurationMs + 300L) return null

        val duration = if (threeDimensional) 600L else 450L
        issuedPitch = target.pitch
        pitchIssuedAtMs = nowMs
        pitchDurationMs = duration
        return NavigationCameraCommand(pitch = target.pitch, durationMs = duration)
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
