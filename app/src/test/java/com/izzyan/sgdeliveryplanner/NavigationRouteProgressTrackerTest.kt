package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationRouteProgressTrackerTest {
    private val shape = listOf(
        GpsPoint(1.35000, 103.82000),
        GpsPoint(1.35100, 103.82000),
        GpsPoint(1.35100, 103.82100)
    )
    private fun match(lat: Double, lon: Double, index: Int) =
        NavigationRoadMatch(GpsPoint(lat, lon), if (index == 0) 0f else 90f, 2.0, index)

    @Test fun fullRouteBlueBeforeVehicleStarts() {
        val progress = NavigationRouteProgressTracker()
        progress.reset(shape)
        val view = requireNotNull(progress.current())
        assertTrue(view.traveled.isEmpty())
        assertEquals(shape, view.remaining)
        assertEquals(shape.first(), view.boundary)
    }

    @Test fun passedRoadTurnsGreyImmediatelyAndSharesBoundaryWithCar() {
        val progress = NavigationRouteProgressTracker()
        progress.reset(shape)
        val frame = requireNotNull(progress.advance(match(1.35040, 103.82000, 0), 9f, 1000))
        assertTrue(frame.trustedMatch)
        assertEquals(frame.boundary, frame.traveled.last())
        assertEquals(frame.boundary, frame.remaining.first())
        assertEquals(1.35040, frame.boundary.lat, 0.000001)
        assertTrue(frame.traveled.size >= 2)
        assertTrue(frame.remaining.size >= 2)
    }

    @Test fun gpsJitterCannotTurnCompletedGreyRoadBackToBlue() {
        val p = NavigationRouteProgressTracker()
        p.reset(shape)
        val first = requireNotNull(p.advance(match(1.35060, 103.82000, 0), 8f, 1000))
        val second = requireNotNull(p.advance(match(1.35055, 103.82000, 0), 8f, 2000))
        assertEquals(first.progressedMeters, second.progressedMeters, 0.001)
        assertEquals(first.boundary, second.boundary)
        assertTrue(second.trustedMatch)
    }

    @Test fun impossibleGpsLeapDoesNotGreyWholeRoad() {
        val p = NavigationRouteProgressTracker()
        p.reset(shape)
        val first = requireNotNull(p.advance(match(1.35002, 103.82000, 0), 5f, 1000))
        val jumped = requireNotNull(p.advance(match(1.35100, 103.82090, 1), 5f, 2000))
        assertEquals(first.progressedMeters, jumped.progressedMeters, 0.001)
        assertFalse(jumped.trustedMatch)
    }

    @Test fun bendMatchesBothLineHalvesAndRerouteResetsProgress() {
        val p = NavigationRouteProgressTracker()
        p.reset(shape)
        val frame = requireNotNull(p.advance(match(1.35100, 103.82035, 1), 9f, 2000))
        assertTrue(frame.traveled.any { it == shape[1] })
        assertEquals(frame.traveled.last(), frame.remaining.first())
        val newRoute = listOf(GpsPoint(1.352, 103.823), GpsPoint(1.353, 103.824))
        p.reset(newRoute)
        assertEquals(newRoute.first(), p.current()?.boundary)
        assertTrue(p.current()!!.traveled.isEmpty())
    }

    @Test fun offRouteCarDoesNotExtendGreySection() {
        val p = NavigationRouteProgressTracker()
        p.reset(shape)
        val first = requireNotNull(p.advance(match(1.35030, 103.82, 0), 6f, 1000))
        val off = requireNotNull(p.advance(null, 6f, 3000))
        assertEquals(first.progressedMeters, off.progressedMeters, 0.001)
        assertFalse(off.trustedMatch)
    }
}
