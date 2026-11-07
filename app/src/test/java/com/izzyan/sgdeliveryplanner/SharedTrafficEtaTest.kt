package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTrafficEtaTest {
    private val road = listOf(GpsPoint(1.3000, 103.8000), GpsPoint(1.3000, 103.8040))
    private val first = GpsPoint(1.3000, 103.8015)
    private val last = GpsPoint(1.3000, 103.8025)

    private fun observed(level: LocalTrafficLevel, speedKph: Double = 10.0) =
        SharedTrafficSegment("osm:1:f", first, last, level, speedKph, 55.0, 1L)

    @Test fun unknownRunnerObservationsNeverChangeEta() {
        assertEquals(240.0, SharedTrafficEta.estimate(240.0, road, 440.0,
            listOf(observed(LocalTrafficLevel.UNKNOWN))), 0.001)
    }

    @Test fun realCongestionIncreasesRemainingEta() {
        val eta = SharedTrafficEta.estimate(240.0, road, 440.0,
            listOf(observed(LocalTrafficLevel.JAMMED)))
        assertTrue(eta > 240.0)
        assertTrue(eta < 600.0)
    }

    @Test fun oppositeDirectionCannotChangeEta() {
        val opposite = observed(LocalTrafficLevel.JAMMED).copy(start = last, end = first)
        assertEquals(240.0, SharedTrafficEta.estimate(240.0, road, 440.0,
            listOf(opposite)), 0.001)
    }

    @Test fun withoutValidRouteNeverChangesEta() {
        assertEquals(240.0, SharedTrafficEta.estimate(240.0, emptyList(), 200.0,
            listOf(observed(LocalTrafficLevel.SLOW))), 0.001)
    }
}
