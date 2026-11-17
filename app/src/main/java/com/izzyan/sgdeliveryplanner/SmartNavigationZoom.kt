package com.izzyan.sgdeliveryplanner

import kotlin.math.max
import kotlin.math.min

/** GPS presentation only: never alters routing, location, ETA, voice, or arrival state. */
internal enum class SmartCameraMode {
    EXPRESSWAY_WIDE, NORMAL_FOLLOW, APPROACHING_MANEUVER,
    CRITICAL_JUNCTION_FOCUS, MANEUVER_TRANSITION, POST_MANEUVER_WIDE
}
internal enum class SmartManeuverKind { NONE, EXIT, TURN }

internal object SmartManeuverClassifier {
    // An actual name supplied by the routing SDK is required; speed alone never labels a highway.
    private val expressway = Regex("""(?i)\b(?:AYE|BKE|CTE|ECP|KPE|MCE|PIE|SLE|TPE)\b|\bexpressway\b""")
    fun isExpressway(nameFromRoute: String?): Boolean =
        nameFromRoute?.let { expressway.containsMatchIn(it) } == true

    fun kind(type: String?, instruction: String): SmartManeuverKind {
        val cue = (type.orEmpty() + " " + instruction).lowercase(java.util.Locale.ROOT)
        if ("roundabout" !in cue && ("off_ramp" in cue || "off ramp" in cue ||
            "take the exit" in cue || "exit toward" in cue || "exit onto" in cue ||
            "exit ramp" in cue || "motorway exit" in cue)) return SmartManeuverKind.EXIT
        return if (listOf("turn", "keep left", "keep right", "fork", "roundabout",
                "u-turn", "u turn", "slip road", "merge", "on_ramp", "on ramp")
                .any { it in cue }) SmartManeuverKind.TURN else SmartManeuverKind.NONE
    }
}

internal data class SmartZoomInput(
    val nowMs: Long,
    val speedMps: Float?,
    val distanceMeters: Double?,
    val followingTurnMeters: Double?,
    val kind: SmartManeuverKind,
    val expressway: Boolean,
    val corner: NavigationCorner?,
    val remainingMeters: Double?,
    val stepIndex: Int,
    val progressMeters: Double?,
    val matched: Boolean,
    val offRoute: Boolean,
    val threeDimensional: Boolean,
    val accuracyMeters: Float?
)

internal data class SmartZoomTarget(
    val mode: SmartCameraMode,
    val zoom: Double,
    val pitch: Double,
    val forwardPaddingFraction: Double,
    val durationMs: Long,
    val urgent: Boolean
)

/** Central six-state controller; accepts only real route progress and approved GPS fixes. */
internal class SmartNavigationZoom {
    var mode: SmartCameraMode = SmartCameraMode.NORMAL_FOLLOW
        private set
    private var lastStepIndex: Int? = null
    private var previousSpeedKph: Double? = null
    private var transitionAt: Double? = null
    private var transitionSince = 0L
    private var postUntil = 0L

    fun reset() {
        mode = SmartCameraMode.NORMAL_FOLLOW
        lastStepIndex = null
        previousSpeedKph = null
        transitionAt = null
        transitionSince = 0L
        postUntil = 0L
    }

    private fun speedKph(sample: Float?): Double {
        val raw = sample?.takeIf { it.isFinite() && it >= 0f && it <= 48f }?.toDouble()?.times(3.6)
        val prev = previousSpeedKph
        val speed = when {
            raw == null -> prev ?: 40.0
            prev == null -> raw
            else -> prev * 0.66 + raw.coerceIn(prev - 30.0, prev + 30.0) * 0.34
        }
        previousSpeedKph = speed
        return speed
    }

    fun target(input: SmartZoomInput): SmartZoomTarget? {
        if (input.offRoute) { reset(); return null }
        if (input.accuracyMeters != null &&
            (!input.accuracyMeters.isFinite() || input.accuracyMeters > 35f)) return null
        val speed = speedKph(input.speedMps)
        val previous = lastStepIndex
        if (previous != null && input.stepIndex != previous &&
            mode in setOf(SmartCameraMode.APPROACHING_MANEUVER,
                SmartCameraMode.CRITICAL_JUNCTION_FOCUS, SmartCameraMode.MANEUVER_TRANSITION)) {
            transitionAt = input.progressMeters
            transitionSince = input.nowMs
            postUntil = 0L
        }
        lastStepIndex = input.stepIndex

        val distance = listOfNotNull(
            input.distanceMeters?.takeIf { it.isFinite() && it >= 0.0 &&
                input.kind != SmartManeuverKind.NONE },
            input.corner?.takeIf { it.angleDegrees >= 40f }?.distanceMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
        ).minOrNull()
        val kind = when {
            input.kind != SmartManeuverKind.NONE -> input.kind
            distance != null -> SmartManeuverKind.TURN
            else -> SmartManeuverKind.NONE
        }
        // A second turn within 180m must not trigger a wide/close oscillation.
        val followingNear = input.followingTurnMeters?.let { it.isFinite() && it in 0.0..180.0 } == true
        val transition = transitionAt
        val completed = transition != null && input.matched &&
            input.progressMeters != null && input.progressMeters - transition >= 22.0 &&
            input.nowMs - transitionSince >= 500L
        if (completed) {
            transitionAt = null
            postUntil = if (followingNear || (distance != null && distance < 200)) 0L
                else input.nowMs + 800L
        }
        if (transitionAt != null && input.nowMs - transitionSince > 10_000L &&
            input.matched && input.progressMeters == null) {
            // Progress not trustworthy: stay focused rather than claiming completion.
            postUntil = 0L
        }

        val criticalThreshold = if (kind == SmartManeuverKind.EXIT) 180.0 else 90.0
        val startThreshold = if (kind == SmartManeuverKind.EXIT) {
            max(2500.0, speed * 12.0)
        } else {
            max(430.0, speed * 3.5)
        }
        val critical = distance != null &&
            distance <= criticalThreshold + if (mode == SmartCameraMode.CRITICAL_JUNCTION_FOCUS) 35.0 else 0.0
        val approaching = distance != null &&
            distance <= startThreshold + if (mode == SmartCameraMode.APPROACHING_MANEUVER) 60.0 else 0.0

        mode = when {
            transitionAt != null -> SmartCameraMode.MANEUVER_TRANSITION
            critical -> SmartCameraMode.CRITICAL_JUNCTION_FOCUS
            approaching -> SmartCameraMode.APPROACHING_MANEUVER
            input.nowMs < postUntil -> SmartCameraMode.POST_MANEUVER_WIDE
            input.expressway && speed >= 42 -> SmartCameraMode.EXPRESSWAY_WIDE
            else -> SmartCameraMode.NORMAL_FOLLOW
        }
        val normalZoom = when {
            speed >= 100 -> 16.0
            speed >= 75 -> 16.5
            speed >= 40 -> 17.1
            speed >= 15 -> 17.65
            else -> 18.05
        }
        val wideZoom = when {
            speed >= 100 -> 14.2
            speed >= 80 -> 14.5
            speed >= 60 -> 14.85
            else -> 15.15
        }
        val zoom = when (mode) {
            SmartCameraMode.EXPRESSWAY_WIDE -> wideZoom
            SmartCameraMode.NORMAL_FOLLOW -> normalZoom
            SmartCameraMode.POST_MANEUVER_WIDE -> if (input.expressway && speed >= 42) wideZoom else normalZoom
            SmartCameraMode.MANEUVER_TRANSITION -> 18.55
            SmartCameraMode.CRITICAL_JUNCTION_FOCUS -> if (kind == SmartManeuverKind.EXIT) 18.85 else 19.0
            SmartCameraMode.APPROACHING_MANEUVER -> {
                val d = distance ?: Double.POSITIVE_INFINITY
                if (kind == SmartManeuverKind.EXIT) when {
                    d <= 200 -> 18.6
                    d <= 500 -> 17.95
                    d <= 1000 -> 17.0
                    d <= 2000 -> 15.95
                    else -> 15.25
                } else when {
                    d <= 100 -> 18.65
                    d <= 200 -> 18.0
                    d <= 400 -> 17.55
                    else -> normalZoom
                }
            }
        }.let {
            if ((input.remainingMeters ?: Double.POSITIVE_INFINITY) in 0.0..200.0)
                max(it, 18.6) else it
        }.coerceIn(13.8, 19.2)
        val near = mode in setOf(SmartCameraMode.CRITICAL_JUNCTION_FOCUS,
            SmartCameraMode.MANEUVER_TRANSITION)
        return SmartZoomTarget(
            mode = mode, zoom = zoom,
            pitch = if (!input.threeDimensional) 0.0 else if (near) 48.0 else 54.0,
            forwardPaddingFraction = if (near) 0.52 else if (mode == SmartCameraMode.APPROACHING_MANEUVER) 0.48 else 0.44,
            durationMs = when (mode) {
                SmartCameraMode.CRITICAL_JUNCTION_FOCUS -> 250L
                SmartCameraMode.MANEUVER_TRANSITION -> 300L
                SmartCameraMode.POST_MANEUVER_WIDE -> 600L
                else -> 430L
            },
            urgent = near || (mode == SmartCameraMode.APPROACHING_MANEUVER && distance != null && distance <= 500)
        )
    }
}
