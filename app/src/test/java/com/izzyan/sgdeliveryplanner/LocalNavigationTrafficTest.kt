package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNavigationTrafficTest {
    private val shape = (0..20).map { GpsPoint(1.31 + it * 0.0001, 103.80) }
    private fun model() = LocalNavigationTraffic().apply { reset(shape, 230.0, 23.0) }

    @Test fun unknownWhenNotEnoughSpeedObservations() {
        val m = model()
        m.observe(0, 4f, 6f, 0L, 1000.0, true)
        assertEquals(LocalTrafficLevel.UNKNOWN, m.snapshot(35_000L, 0).ahead)
    }

    @Test fun rejectsParkedDeliveryAndInaccurateGps() {
        val m = model()
        for (i in 0..6) {
            m.observe(0, 0f, 5f, i * 3_000L, 500.0, true)
            m.observe(0, 5f, 30f, i * 3_000L, 500.0, true)
            m.observe(0, 5f, 5f, i * 3_000L, 500.0, true, deliveryActivity = true)
        }
        assertEquals(LocalTrafficLevel.UNKNOWN, m.snapshot(40_000L, 0).ahead)
    }

    @Test fun recordedSlowSegmentsUseActualSamplesNotFabricatedColors() {
        val m = model()
        for (i in 0..6) m.observe(0, 4f, 6f, i * 3_000L, 900.0, true)
        assertEquals(LocalTrafficLevel.SLOW, m.snapshot(40_000L, 0).ahead)
        assertTrue(m.snapshot(40_000L, 0).bands.isNotEmpty())
    }

    @Test fun fastTrafficShowsGreenAndAgingOutReturnsUnknown() {
        val m = model()
        for (i in 0..6) m.observe(0, 9f, 6f, i * 3_000L, 900.0, true)
        assertEquals(LocalTrafficLevel.SMOOTH, m.snapshot(40_000L, 0).ahead)
        assertEquals(LocalTrafficLevel.UNKNOWN, m.snapshot(16 * 60_000L, 0).ahead)
    }

    @Test fun invalidRouteHasNoColor() {
        val m = LocalNavigationTraffic()
        m.reset(shape, 0.0, 0.0)
        for (i in 0..6) m.observe(0, 2.2f, 6f, i * 3_000L, 900.0, true)
        assertEquals(LocalTrafficLevel.UNKNOWN, m.snapshot(40_000L, 0).ahead)
    }
}
