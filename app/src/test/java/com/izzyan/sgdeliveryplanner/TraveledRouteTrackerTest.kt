package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraveledRouteTrackerTest {
    private val northRoute = listOf(
        GpsPoint(1.3000, 103.8000),
        GpsPoint(1.3003, 103.8000),
        GpsPoint(1.3006, 103.8000),
        GpsPoint(1.3010, 103.8000)
    )

    @Test fun blueLineTurnsGrayBehindSameGeographicPositionAsArrow() {
        val tracker = TraveledRouteTracker()
        tracker.reset(northRoute)
        val position = GpsPoint(1.3002, 103.8000)
        val cut = requireNotNull(tracker.advance(0, position, 10f, 1_000L))
        assertTrue(cut.completedMeters > 20.0)
        assertEquals(northRoute.first(), cut.grayBehind.first())
        assertEquals(position.lat, cut.grayBehind.last().lat, 0.00000001)
        assertEquals(cut.grayBehind.last(), cut.blueAhead.first())
        assertEquals(northRoute.last(), cut.blueAhead.last())
    }

    @Test fun ignoresBackwardGpsJitterWithoutMakingBlueLineReappear() {
        val tracker = TraveledRouteTracker()
        tracker.reset(northRoute)
        val forward = requireNotNull(tracker.advance(1, GpsPoint(1.30050, 103.8000), 10f, 1_000L))
        val backward = requireNotNull(tracker.advance(0, GpsPoint(1.30020, 103.8000), 10f, 2_000L))
        assertEquals(forward.completedMeters, backward.completedMeters, 0.01)
        assertEquals(forward.grayBehind.last(), backward.grayBehind.last())
    }

    @Test fun rejectsLargeInstantaneousPositionJump() {
        val tracker = TraveledRouteTracker()
        tracker.reset(northRoute)
        val start = requireNotNull(tracker.advance(0, GpsPoint(1.30001, 103.8000), 6f, 1_000L))
        val leap = requireNotNull(tracker.advance(2, GpsPoint(1.30099, 103.8000), 7f, 2_000L))
        assertEquals(start.completedMeters, leap.completedMeters, 0.01)
    }

    @Test fun holdsProgressWhenStationaryOrGpsCannotBeMatched() {
        val tracker = TraveledRouteTracker()
        tracker.reset(northRoute)
        val initial = requireNotNull(tracker.advance(0, GpsPoint(1.30025, 103.8000), 8f, 1_000L))
        assertEquals(initial.completedMeters,
            requireNotNull(tracker.advance(1, GpsPoint(1.3004, 103.8000), 0f, 2_000L)).completedMeters,
            0.01)
        assertEquals(initial.completedMeters,
            requireNotNull(tracker.advance(null, GpsPoint(1.3009, 103.8020), 8f, 4_000L)).completedMeters,
            0.01)
    }

    @Test fun resetsOnNewRouteAndNeverShowsPreviousDeliveryGrayPath() {
        val tracker = TraveledRouteTracker()
        tracker.reset(northRoute)
        assertNotNull(tracker.advance(0, GpsPoint(1.30020, 103.8000), 6f, 1_000L))
        tracker.reset(listOf(GpsPoint(1.3100, 103.8100), GpsPoint(1.3110, 103.8100)))
        assertNull(tracker.current())
        val changed = requireNotNull(tracker.advance(0, GpsPoint(1.3102, 103.8100), 7f, 3_000L))
        assertEquals(1.3100, changed.grayBehind.first().lat, 0.0000001)
    }
}
