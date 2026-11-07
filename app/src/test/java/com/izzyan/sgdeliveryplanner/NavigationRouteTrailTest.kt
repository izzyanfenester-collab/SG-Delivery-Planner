package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRouteTrailTest {
    private val route = listOf(
        GpsPoint(1.3000, 103.8000),
        GpsPoint(1.3010, 103.8000),
        GpsPoint(1.3020, 103.8000),
        GpsPoint(1.3030, 103.8000)
    )
    private fun match(segment: Int, fraction: Double) = NavigationRoadMatch(
        GpsPoint(
            route[segment].lat + (route[segment + 1].lat - route[segment].lat) * fraction,
            103.8000
        ), 0f, 0.0, segment
    )
    @Test fun startsBlueAndTurnsOnlyTraversedDistanceGrey() {
        val tracker = NavigationRouteTrail()
        val token = Any()
        tracker.ensureRoute(token, route)
        assertTrue(tracker.split().passed.isEmpty())
        assertEquals(route, tracker.split().remaining)
        assertTrue(tracker.advance(match(0, 0.25), 12f, 1_000L))
        val split = tracker.split()
        assertEquals(route.first(), split.passed.first())
        assertEquals(split.passed.last(), split.remaining.first())
        assertEquals(route.last(), split.remaining.last())
        assertTrue(split.progressMeters > 20)
        assertTrue(split.progressMeters < 40)
    }
    @Test fun passedSectionGrowsAndNeverRegressesOnGpsJitter() {
        val tracker = NavigationRouteTrail()
        tracker.ensureRoute(Any(), route)
        tracker.advance(match(0, .30), 12f, 1_000L)
        val current = tracker.split()
        assertFalse(tracker.advance(match(0, .10), 12f, 2_000L))
        assertEquals(current.progressMeters, tracker.split().progressMeters, 0.1)
        assertTrue(tracker.advance(match(0, .40), 12f, 3_000L))
        assertTrue(tracker.split().progressMeters > current.progressMeters)
    }
    @Test fun wrongTurnOrHugeJumpDoesNotPaintRoadGrey() {
        val tracker = NavigationRouteTrail()
        tracker.ensureRoute(Any(), route)
        tracker.advance(match(0, .30), 10f, 1_000L)
        val current = tracker.split()
        assertFalse(tracker.advance(null, 10f, 2_000L))
        assertFalse(tracker.advance(match(2, .5), 10f, 2_000L))
        assertEquals(current.progressMeters, tracker.split().progressMeters, 0.1)
    }
    @Test fun stationaryCarDoesNotAdvanceIntoFutureRoad() {
        val tracker = NavigationRouteTrail()
        tracker.ensureRoute(Any(), route)
        assertFalse(tracker.advance(match(0, .4), 0f, 1_000L))
        assertTrue(tracker.split().passed.isEmpty())
    }
    @Test fun rerouteResetsPassedRoadButStyleRefreshKeepsIt() {
        val tracker = NavigationRouteTrail()
        val token = Any()
        tracker.ensureRoute(token, route)
        tracker.advance(match(0, .3), 10f, 1_000L)
        assertTrue(tracker.split().passed.isNotEmpty())
        tracker.ensureRoute(token, route)
        assertTrue(tracker.split().passed.isNotEmpty())
        tracker.ensureRoute(Any(), route)
        assertTrue(tracker.split().passed.isEmpty())
    }
}
