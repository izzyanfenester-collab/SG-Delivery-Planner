package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class AdaptiveNavigationEtaTest {
    @Test fun insufficientOrUnavailableSpeedFallsBackToValhalla() {
        val eta = AdaptiveNavigationEta()
        assertEquals(600.0, eta.remainingSeconds(600.0, 6000.0, 0), 0.0)
        eta.observe(5.0, 10.0, 0)
        assertEquals(600.0, eta.remainingSeconds(600.0, 6000.0, 2000), 0.0)
        for (now in 2000L..40000L step 2000) { eta.observe(5.0, 10.0, now); eta.remainingSeconds(600.0, 6000.0, now) }
        assertTrue(eta.remainingSeconds(600.0, 6000.0, 40000) > 600)
        assertEquals(600.0, eta.remainingSeconds(600.0, 6000.0, 56000), 0.0)
    }
    @Test fun smoothingRejectsSpikesAndBadAccuracy() {
        val eta = AdaptiveNavigationEta()
        eta.observe(10.0, 10.0, 0)
        eta.observe(30.0, 10.0, 2000)
        assertTrue(eta.smoothedSpeed!! in 10.0..12.0)
        val before = eta.smoothedSpeed!!
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 100.0).forEach { eta.observe(it, 10.0, 4000) }
        eta.observe(20.0, 50.0, 4000)
        eta.observe(20.0, Double.NaN, 4000)
        eta.observe(20.0, 10.0, 4000, 8.0)
        eta.observe(null, 10.0, 4000)
        eta.observe(20.0, 10.0, 1000)
        assertEquals(before, eta.smoothedSpeed!!, 0.0)
    }
    @Test fun slowerAndFasterDrivingGraduallyAdjustsCurrentRemainingRouteTime() {
        fun run(speed: Double): Double {
            val eta = AdaptiveNavigationEta()
            var before = 600.0
            for (now in 0L..120000L step 2000) {
                eta.observe(speed, 10.0, now)
                val value = eta.remainingSeconds(600.0, 6000.0, now)
                assertTrue(kotlin.math.abs(value - before) <= 12.001)
                before = value
            }
            // Uses latest remaining distance/time, rather than the original route's total.
            assertEquals(before / 2, eta.remainingSeconds(300.0, 3000.0, 120000), 0.001)
            return before
        }
        assertTrue(run(5.0) in 1000.0..1200.0)
        assertTrue(run(20.0) in 420.0..480.0)
    }
    @Test fun briefStopHasGraceAndProlongedStopIsBounded() {
        val eta = AdaptiveNavigationEta()
        for (now in 0L..30000L step 2000) { eta.observe(10.0, 10.0, now); eta.remainingSeconds(600.0, 6000.0, now) }
        for (now in 32000L..50000L step 2000) {
            eta.observe(0.0, 10.0, now)
            assertEquals(600.0, eta.remainingSeconds(600.0, 6000.0, now), .001)
        }
        for (now in 52000L..180000L step 2000) {
            eta.observe(0.0, 10.0, now)
            assertTrue(eta.remainingSeconds(600.0, 6000.0, now) in 600.0..1200.0)
        }
    }
    @Test fun longGapRequiresFreshWarmupRatherThanReusingOldSpeed() {
        val eta = AdaptiveNavigationEta()
        for (now in 0L..40000L step 2000) { eta.observe(5.0, 10.0, now); eta.remainingSeconds(600.0, 6000.0, now) }
        eta.observe(20.0, 10.0, 60000)
        assertEquals(20.0, eta.smoothedSpeed!!, 0.0)
        assertEquals(600.0, eta.remainingSeconds(600.0, 6000.0, 60000), 0.0)
    }
    @Test fun invalidDurationsArrivalAndResetStayFiniteAndNonnegative() {
        val eta = AdaptiveNavigationEta()
        for (now in 0L..60000L step 2000) { eta.observe(3.0, 10.0, now); eta.remainingSeconds(600.0, 6000.0, now) }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0).forEach { assertEquals(0.0, eta.remainingSeconds(it, 100.0, 60000), 0.0) }
        assertEquals(30.0, eta.remainingSeconds(30.0, Double.NaN, 60000), 0.0)
        eta.reset()
        assertNull(eta.smoothedSpeed)
        assertEquals(600.0, eta.remainingSeconds(600.0, 6000.0, 62000), 0.0)
    }
}
