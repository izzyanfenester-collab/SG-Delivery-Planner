package com.izzyan.sgdeliveryplanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** B864: scheduling and formatting of SPOKEN instructions only.
 * Distances used for GPS, rerouting, UI, ETA and position stay precise doubles.
 *
 * No arbitrary rounding of 1.9km to 1km or 3.7km to 3km: only a
 * crossed checkpoint, or an already-nearby checkpoint on a fresh route, may
 * produce a numeric Panda Warrior phrase.
 */
internal object WholeDistanceVoiceScheduler {
    /** Sparse whole-kilometre levels chosen for long-range routes. 19km is
     * supported for route starts at that distance, but the existing voice
     * gate still prevents excessive announcements for a single maneuver.
     */
    internal val kilometres = listOf(
        50000, 40000, 30000, 25000, 20000, 19000,
        15000, 10000, 7000, 5000, 3000, 2000, 1000
    )
    internal val metres = listOf(900, 800, 700, 600, 500, 400, 300, 200, 150, 100, 50)

    private fun relevant(speedMps: Float): List<Int> {
        val speed = speedMps.takeIf { it.isFinite() && it>=0f } ?: 0f
        val km = if (speed >= 20f) kilometres else
            kilometres.filter { it <= 3000 || it >= 10000 }
        val m = if (speed >= 20f)
            metres.filter { it in setOf(900, 800, 500, 200, 100, 50) }
        else metres.filter { it in setOf(900,800,500,300,200,100,50) }
        return km+m
    }

    fun checkpoint(
        remainingMetres: Double,
        speedMps: Float,
        previousDistance: Double?,
        force: Boolean = false
    ): Int? {
        if (!remainingMetres.isFinite() || remainingMetres < 0.0) return null
        val prior = previousDistance?.takeIf { it.isFinite() && it >= 0.0 }
        val speed = speedMps.takeIf { it.isFinite() && it>=0f }?.toDouble() ?: 0.0
        val marks = relevant(speedMps)

        for (mark in marks) {
            val checkpoint = mark.toDouble()
            if (remainingMetres > checkpoint) continue // no premature shorter distance
            val overshoot = checkpoint - remainingMetres
            val allowed = max(15.0, max(speed*2.8, min(80.0, checkpoint*0.055)))
            if (overshoot > allowed) continue // skip stale crossed checkpoint
            if (prior != null) {
                // A genuine crossing, including 1080m -> 970m at 1km.
                if (prior > checkpoint) return mark
            } else {
                // First fix/new route: speak ONLY when already at/just past an
                // actual checkpoint. 1.9km or 3.7km must wait, never truncate.
                val firstFixTolerance = min(22.0, max(7.0, speed*1.2))
                if (overshoot <= firstFixTolerance) return mark
            }
        }
        // Force from rerouting is NOT permission to invent a rounded distance.
        // Status and urgent generic maneuver instructions remain independent.
        return null
    }
}
