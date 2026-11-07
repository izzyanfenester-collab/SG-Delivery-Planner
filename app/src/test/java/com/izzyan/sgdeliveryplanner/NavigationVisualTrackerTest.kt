package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationVisualTrackerTest {
    private val northRoad = listOf(
        GpsPoint(1.3500, 103.8200),
        GpsPoint(1.3510, 103.8200)
    )

    @Test fun correctsModestGpsDriftOntoRouteWithoutMovingNavigationFix() {
        val original = GpsPoint(1.3505, 103.82010) // about 11 metres east of route
        val match = NavigationRoadMatcher.match(original, northRoad, 10f, 8f, 0f)
        assertNotNull(match)
        assertEquals(103.8200, requireNotNull(match).point.lon, 0.0000001)
        assertTrue(match.distanceMeters > 9.0)
        assertEquals(103.82010, original.lon, 0.0000001)
    }

    @Test fun neverMagnetizesClearlyOffRouteFixOntoWrongStreet() {
        val wrongStreet = GpsPoint(1.3505, 103.8210)
        assertNull(NavigationRoadMatcher.match(wrongStreet, northRoad, 10f, 9f, 0f))
        val visual = NavigationVisualTracker().update(wrongStreet, northRoad, 10f, 9f, 0f)
        assertFalse(visual.matchedToRoute)
        assertEquals(wrongStreet, visual.position)
    }

    @Test fun avoidsMatchingWrongDirectionOnParallelOrReturnLeg() {
        val original = GpsPoint(1.3505, 103.82005)
        assertNull(NavigationRoadMatcher.match(original, northRoad, 8f, 10f, 180f))
    }

    @Test fun holdsCarStillWithinAccuracyInsteadOfJittering() {
        val tracker = NavigationVisualTracker()
        val first = tracker.update(GpsPoint(1.3505, 103.8200), northRoad, 7f, 0f, null)
        val second = tracker.update(GpsPoint(1.35053, 103.82004), northRoad, 7f, 0f, null)
        assertEquals(first.position, second.position)
    }

    @Test fun usesRoadTangentWhenGpsBearingMissing() {
        val fix = NavigationVisualTracker().update(
            GpsPoint(1.3505, 103.82003), northRoad, 7f, 11f, null)
        assertEquals(0f, requireNotNull(fix.bearingDegrees), 1f)
    }

    @Test fun fallsBackToMovementDirectionWhenOutsideRoute() {
        val tracker = NavigationVisualTracker()
        tracker.update(GpsPoint(1.3520, 103.8210), northRoad, 8f, 8f, null)
        val fix = tracker.update(GpsPoint(1.3520, 103.8211), northRoad, 8f, 8f, null)
        assertFalse(fix.matchedToRoute)
        assertEquals(90f, requireNotNull(fix.bearingDegrees), 3f)
    }

    @Test fun interpolatesAngleAroundNorthAndDoesNotSpinWhileStopped() {
        val tracker = NavigationVisualTracker()
        val nearNorth = tracker.update(GpsPoint(1.3503, 103.8200), northRoad, 8f, 8f, 350f)
        val crossedNorth = tracker.update(GpsPoint(1.3505, 103.8200), northRoad, 8f, 8f, 10f)
        assertTrue(NavigationRoadMatcher.angleDifference(
            requireNotNull(nearNorth.bearingDegrees), requireNotNull(crossedNorth.bearingDegrees)) < 15f)
        val stopped = tracker.update(GpsPoint(1.35051, 103.82002), northRoad, 8f, 0f, 180f)
        assertEquals(crossedNorth.bearingDegrees, stopped.bearingDegrees)
    }

    @Test fun rejectsUnreliableLocationBeforeProjectingIt() {
        val p = GpsPoint(1.3505, 103.82002)
        assertNull(NavigationRoadMatcher.match(p, northRoad, 60f, 10f, 0f))
        assertNull(NavigationRoadMatcher.match(p, emptyList(), 5f, 10f, 0f))
    }
}
