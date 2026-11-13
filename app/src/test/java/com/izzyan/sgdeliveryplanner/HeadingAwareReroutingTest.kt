package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingAwareReroutingTest {
    @Test fun doesNotRerouteForOneGpsJumpOrInaccurateBearing() {
        val p = ReroutePolicy()
        assertFalse(p.shouldReroute(30.0, 9f, 10_000, 160f, 8f))
        assertFalse(p.shouldReroute(30.0, 40f, 25_000, 160f, 8f))
        assertFalse(p.shouldReroute(30.0, 9f, 25_100, 80f, 8f))
        assertFalse(p.shouldReroute(30.0, 9f, 35_100, 160f, 2f))
    }

    @Test fun sustainedWrongExitCanTriggerEvenBeforeSixtyMetres() {
        val p = ReroutePolicy()
        assertFalse(p.shouldReroute(28.0, 8f, 10_000, 150f, 10f))
        assertFalse(p.shouldReroute(28.0, 8f, 10_399, 150f, 10f))
        assertTrue(p.shouldReroute(28.0, 8f, 10_400, 150f, 10f))
    }

    @Test fun speedOrHeadingWithoutRoadDepartureNeverTriggers() {
        val p = ReroutePolicy()
        assertFalse(p.shouldReroute(8.0, 5f, 10_000, 179f, 15f))
        assertFalse(p.shouldReroute(8.0, 5f, 50_000, 179f, 15f))
    }
}
