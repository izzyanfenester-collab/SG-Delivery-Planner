package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.roundToLong

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
    val durationMs: Long = 650L,
    val token: Long = 0L
)

/** B863 only: coordinate MapLibre's ONE native frame-interpolated zoom
 * animation. The B860 six-state decision engine and B862 cap are untouched.
 *
 * Each GPS fix reports the ACTUAL camera position; equal targets are ignored
 * while the SDK is animating. A small co-directional target change is deferred
 * until the current animation ends to avoid unnecessary ease restarts.
 *
 * For real changes, duration is adapted to remaining distance and sampled
 * camera velocity. Native MapLibre tracking retains location, bearing and
 * puck animation ownership; no competing custom frame loop is introduced.
 */
internal class CinematicZoomCoordinator {
    private var issuedTarget: Double? = null
    private var issuedAtMs: Long? = null
    private var issuedDurationMs: Long = 0L
    private var lastSampleTimeMs: Long? = null
    private var lastActualZoom: Double? = null
    private var cameraVelocityLevelsPerSec = 0.0
    private var sequence = 0L
    private var nativeCompleted = false
    private var nativeCancelled = false

    fun reset() {
        issuedTarget = null
        issuedAtMs = null
        issuedDurationMs = 0L
        lastSampleTimeMs = null
        lastActualZoom = null
        cameraVelocityLevelsPerSec = 0.0
        // Ignore callbacks from animations that were canceled by manual mode.
        sequence++
        nativeCompleted = false
        nativeCancelled = false
    }

    /** Callbacks are bookkeeping only; never issue camera commands from them. */
    fun onNativeResult(token: Long, cancelled: Boolean) {
        if (token != sequence) return // stale callback from an earlier retarget
        nativeCompleted = true
        nativeCancelled = cancelled
    }

    fun isAnimating(nowMs: Long): Boolean {
        val start = issuedAtMs ?: return false
        return !nativeCompleted && nowMs >= start &&
            nowMs - start < issuedDurationMs
    }

    private fun observeCamera(nowMs: Long, actualZoom: Double) {
        val beforeMs = lastSampleTimeMs
        val beforeZoom = lastActualZoom
        if (beforeMs != null && beforeZoom != null) {
            val elapsed = nowMs - beforeMs
            if (elapsed in 70L..4_000L) {
                val raw = ((actualZoom - beforeZoom) * 1000.0 / elapsed)
                    .coerceIn(-6.0, 6.0)
                cameraVelocityLevelsPerSec =
                    (0.45 * cameraVelocityLevelsPerSec + 0.55 * raw)
            } else if (elapsed > 4_000L || elapsed < 0L) {
                cameraVelocityLevelsPerSec = 0.0
            }
        }
        lastSampleTimeMs = nowMs
        lastActualZoom = actualZoom
    }

    fun next(nowMs: Long, actualZoom: Double, target: SmartZoomTarget): NavigationCameraCommand? {
        if (!actualZoom.isFinite() || !target.zoom.isFinite()) return null
        observeCamera(nowMs, actualZoom)
        val threshold = if (target.urgent) 0.10 else 0.20
        val distance = abs(target.zoom - actualZoom)
        val prior = issuedTarget
        val started = issuedAtMs
        val elapsed = if (started != null && nowMs >= started) nowMs - started else Long.MAX_VALUE

        if (prior != null && abs(prior - target.zoom) < threshold) {
            // Never restart an identical animation for each 1 Hz GPS fix.
            if (isAnimating(nowMs) || distance < 0.28) return null
            if (!nativeCancelled && elapsed < issuedDurationMs + 400L) return null
            // Native transition was interrupted or ended short: recover once.
        } else {
            if (distance < threshold) return null
            if (!target.urgent && elapsed < 400L) return null

            // Tiny target drift in the SAME direction should never reset the
            // SDK ease curve while it is moving. Finish, then correct residual.
            if (!target.urgent && prior != null && isAnimating(nowMs) &&
                abs(target.zoom - prior) <= 0.32 &&
                (prior - actualZoom) * (target.zoom - actualZoom) > 0.0) return null
        }

        val base = when (target.mode) {
            SmartCameraMode.EXPRESSWAY_WIDE -> 2200L
            SmartCameraMode.POST_MANEUVER_WIDE -> 2200L
            SmartCameraMode.NORMAL_FOLLOW -> if (target.zoom < actualZoom) 1850L else 1500L
            SmartCameraMode.APPROACHING_MANEUVER -> if (target.urgent) 900L else 1350L
            SmartCameraMode.CRITICAL_JUNCTION_FOCUS -> if (distance > 2.0) 700L else 550L
            SmartCameraMode.MANEUVER_TRANSITION -> 600L
        }
        // Keep B862's full-range timing. Small corrections need less time:
        // stretching 0.2 levels over 2.2s feels like a frozen camera.
        val minDuration = if (target.urgent) 400L else 550L
        var duration = if (distance >= 0.75) base else
            (base * (0.50 + 0.50 * distance / 0.75)).roundToLong()
                .coerceIn(minDuration, base)

        // When target changes mid-flight in the SAME direction, use measured
        // zoom speed as a duration reference so the transition doesn't suddenly
        // become much faster/slower. Native SDK still controls easing frames.
        val velocity = cameraVelocityLevelsPerSec
        if (prior != null && isAnimating(nowMs) && distance >= 0.40 &&
            velocity * (target.zoom - actualZoom) > 0.0 &&
            abs(velocity) >= 0.22) {
            val continuityMs = (distance / abs(velocity) * 1000.0)
                .roundToLong().coerceAtLeast(minDuration)
            duration = min(duration, continuityMs.coerceAtMost(base))
        }
        duration = duration.coerceIn(minDuration, 2500L)

        issuedTarget = target.zoom
        issuedAtMs = nowMs
        issuedDurationMs = duration
        nativeCompleted = false
        nativeCancelled = false
        sequence++
        return NavigationCameraCommand(zoom = target.zoom, durationMs = duration,
            token = sequence)
    }
}

/** Single decision path for automatic zoom and tilt. The six-state B860
 * SmartNavigationZoom controller is preserved completely unchanged.
 */
internal class NavigationCameraDirector {
    private val smart = SmartNavigationZoom()
    private val cinematic = CinematicZoomCoordinator()
    private val balancedJunction = BalancedJunctionZoom()
    private var issuedPitch: Double? = null
    private var pitchIssuedAtMs: Long? = null
    private var pitchDurationMs: Long = 0L
    var focusFraction: Double = 0.44
        private set
    val mode: SmartCameraMode get() = smart.mode

    /** MapLibre callback lifecycle, scoped to the latest native zoom only. */
    fun onNativeZoomResult(token: Long, cancelled: Boolean) {
        cinematic.onNativeResult(token, cancelled)
    }

    @Suppress("UNUSED_PARAMETER")
    fun reset(zoom: Double? = null, pitch: Double? = null) {
        cinematic.reset()
        balancedJunction.reset()
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
        screenWidthDp: Float? = null,
        screenHeightDp: Float? = null,
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

        // B862 caps only automatic junction/exit presentation targets. This keeps
        // B861's exact maneuver decisions and cinematic retargeting unchanged.
        val balanced = balancedJunction.apply(
            target = target,
            speedMps = speedMps,
            expressway = SmartManeuverClassifier.isExpressway(roadName),
            maneuverKind = SmartManeuverClassifier.kind(maneuverType, instruction),
            corner = corner,
            nextTurnMeters = followingTurnMeters,
            gpsAccuracyMeters = gpsAccuracyMeters,
            screenWidthDp = screenWidthDp,
            screenHeightDp = screenHeightDp
        )

        // Native LocationComponent.zoomWhileTracking continues one uninterrupted
        // frame-interpolated movement towards the medium-close automatic target.
        cinematic.next(nowMs, currentZoom, balanced)?.let { return it }
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
