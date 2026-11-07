package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTravelOverlayTest {
    private val shape = listOf(
        GpsPoint(1.3500, 103.8200),
        GpsPoint(1.3510, 103.8200),
        GpsPoint(1.3520, 103.8200)
    )

    @Test fun initialRouteIsAllBlueAndNoGray() {
        val split = RouteTravelTracker().reset(shape)
        assertTrue(split.traveled.isEmpty())
        assertEquals(shape, split.remaining)
    }

    @Test fun turnsBlueIntoGrayPreciselyAtMatchedArrowLocation() {
        val tracker = RouteTravelTracker()
        tracker.reset(shape)
        val puck = GpsPoint(1.3504, 103.8200)
        val split = requireNotNull(tracker.advance(0, puck))
        assertEquals(puck.lat, split.traveled.last().lat, 0.0000001)
        assertEquals(puck.lat, split.remaining.first().lat, 0.0000001)
        assertEquals(shape.last(), split.remaining.last())
        assertEquals(shape.first(), split.traveled.first())
        assertTrue(split.traveledMeters > 40.0)
    }

    @Test fun progressesAcrossSegmentsAndNeverRecolorsBehindPuckBlue() {
        val tracker = RouteTravelTracker()
        tracker.reset(shape)
        tracker.advance(0, GpsPoint(1.3508, 103.8200))
        val next = requireNotNull(tracker.advance(1, GpsPoint(1.3514, 103.8200)))
        assertTrue(next.traveledMeters > 140.0)
        assertEquals(1.3514, next.remaining.first().lat, 0.0000001)
        assertEquals(103.8200, next.remaining.first().lon, 0.0000001)
        assertNull(tracker.advance(0, GpsPoint(1.3505, 103.8200)))
        assertEquals(next, tracker.current())
    }

    @Test fun rejectsLostGpsAndTeleportingThenResetsWhenRerouted() {
        val tracker = RouteTravelTracker()
        tracker.reset(shape)
        assertNull(tracker.advance(null, GpsPoint(1.3505, 103.8200)))
        val next = tracker.advance(0, GpsPoint(1.3503, 103.8200))
        assertTrue(next != null)
        assertEquals(0.0, tracker.reset(shape).traveledMeters, 0.1)
    }

    @Test fun rejectsLargeJumpOnLongRoad() {
        val tracker = RouteTravelTracker()
        val longRoad = listOf(GpsPoint(1.30, 103.82), GpsPoint(1.34, 103.82))
        tracker.reset(longRoad)
        assertNull(tracker.advance(0, GpsPoint(1.33, 103.82)))
        assertEquals(longRoad, tracker.current().remaining)
    }
}
