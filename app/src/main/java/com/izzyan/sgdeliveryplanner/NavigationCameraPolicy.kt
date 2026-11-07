package com.izzyan.sgdeliveryplanner

import kotlin.math.abs

/**
 * Navigation camera policy only. Changes requested zoom (not GPS location) and deliberately
 * avoids changing it every time a phone reports another fix.
 */
internal object NavigationCameraPolicy {
    fun targetZoom(
        speedMetersPerSecond: Float?,
        turnMeters: Double?,
        instruction: String,
        remainingMeters: Double?
    ): Double {
        val kph = (speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f } ?: 0f) * 3.6f
        var target = when {
            kph < 5f -> 18.8
            kph < 20f -> 18.6
            kph < 50f -> 18.2
            kph < 80f -> 17.8
            else -> 17.3
        }
        val turning = Regex("(?i)(turn|roundabout|exit|u-turn|junction|keep (left|right))")
            .containsMatchIn(instruction)
        val distance = turnMeters?.takeIf { it.isFinite() && it >= 0.0 }
        if (turning && distance != null) {
            target = when {
                distance <= 90.0 -> maxOf(target, 18.7)
                distance <= 250.0 -> maxOf(target, 18.4)
                distance <= 500.0 -> maxOf(target, 18.0)
                else -> target
            }
        }
        if (remainingMeters != null && remainingMeters.isFinite() &&
            remainingMeters in 0.0..300.0) target = maxOf(target, 18.7)
        return target.coerceIn(16.0, 19.2)
    }
}

internal class NavigationZoomController {
    private var previous: Double? = null
    private var previousTransition: Long = Long.MIN_VALUE

    fun reset() { previous = null; previousTransition = Long.MIN_VALUE }

    /**
     * Return null if current camera should be left alone.
     * 4-second cooldown for ordinary adjustments; a nearby junction may override it.
     */
    fun choose(target: Double, nowElapsedMs: Long, urgent: Boolean = false): Double? {
        require(target.isFinite())
        val old = previous
        if (old == null) {
            previous = target
            previousTransition = nowElapsedMs
            return target
        }
        val delta = abs(target - old)
        if (delta < 0.3) return null
        val elapsed = if (nowElapsedMs >= previousTransition) nowElapsedMs - previousTransition else 0L
        if (elapsed < 4_000L && !(urgent && target > old + 0.45)) return null
        // Minimise perspective jumps; call site performs an animated MapLibre zoom.
        val next = old + (target - old).coerceIn(-0.7, 0.7)
        previous = next
        previousTransition = nowElapsedMs
        return next
    }
}
