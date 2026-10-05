package com.izzyan.sgdeliveryplanner

import kotlin.math.exp

/** Navigation-only state: never writes the delivery plan or saved schedule. Uses monotonic time. */
internal class AdaptiveNavigationEta {
    private var speed: Double? = null
    private var firstSample: Long? = null
    private var lastSample: Long? = null
    private var stoppedSince: Long? = null
    private var samples = 0
    private var factor = 1.0
    private var lastEstimate: Long? = null
    val smoothedSpeed: Double? get() = speed

    fun reset() {
        speed = null; firstSample = null; lastSample = null; stoppedSince = null
        samples = 0; factor = 1.0; lastEstimate = null
    }

    fun observe(speedMps: Double?, accuracyMeters: Double, now: Long, speedAccuracyMps: Double? = null) {
        if (speedMps == null || !speedMps.isFinite() || speedMps !in 0.0..55.0 ||
            !accuracyMeters.isFinite() || accuracyMeters !in 0.0..25.0 ||
            (speedAccuracyMps != null && (!speedAccuracyMps.isFinite() || speedAccuracyMps !in 0.0..3.0))) return
        val previous = lastSample
        if (previous != null && now <= previous) return
        if (previous != null && now - previous > 15_000) reset()
        val elapsed = lastSample?.let { (now - it) / 1000.0 } ?: 0.0
        if (firstSample == null) firstSample = now
        lastSample = now; samples++
        if (speedMps < 1.0) {
            if (stoppedSince == null) stoppedSince = now
            // Traffic-light grace: hold the moving-speed estimate for the first 20 seconds.
            if (now - requireNotNull(stoppedSince) < 20_000) return
        } else stoppedSince = null
        val old = speed
        speed = if (old == null) speedMps else old + (1 - exp(-elapsed / 20.0)) * (speedMps - old)
    }

    fun remainingSeconds(baseSeconds: Double, remainingMeters: Double, now: Long): Double {
        val base = if (baseSeconds.isFinite()) baseSeconds.coerceAtLeast(0.0) else 0.0
        if (base == 0.0) return 0.0
        val measured = speed
        val enough = measured != null && samples >= 5 && firstSample?.let { now - it >= 10_000 } == true &&
            lastSample?.let { now - it in 0..15_000 } == true && remainingMeters.isFinite() && remainingMeters > 0
        if (!enough) { factor = 1.0; lastEstimate = now; return base }
        // Valhalla's remaining distance/duration remains the baseline. Bound adaptation to 0.7–2x.
        val expectedSpeed = remainingMeters / base
        val target = (expectedSpeed / requireNotNull(measured).coerceAtLeast(2.0)).coerceIn(0.7, 2.0)
        val elapsed = lastEstimate?.let { ((now - it) / 1000.0).coerceIn(0.0, 5.0) } ?: 0.0
        lastEstimate = now
        val change = (target - factor) * (1 - exp(-elapsed / 30.0))
        factor += change.coerceIn(-0.01 * elapsed, 0.01 * elapsed)
        return (base * factor).takeIf { it.isFinite() } ?: base
    }
}
