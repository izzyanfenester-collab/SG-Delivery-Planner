package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCameraPolicyTest {
    @Test fun speedAdaptsViewToDrivingConditions() {
        val p = NavigationCameraPolicy()
        val stationary = p.targetZoom(0f, null, null, "Continue straight")
        val city = p.targetZoom(10f, null, null, "Continue straight")
        val highway = p.targetZoom(28f, null, null, "Continue straight")
        assertTrue(stationary > city)
        assertTrue(city > highway)
        assertTrue(stationary <= 19.35)
        assertTrue(highway >= 16.5)
    }

    @Test fun approachingRoundaboutAndDestinationAutomaticallyZoomsIn() {
        val p = NavigationCameraPolicy()
        val baseline = p.targetZoom(20f, 1500.0, 5000.0, "Continue straight")
        val closeExit = p.targetZoom(20f, 90.0, 5000.0, "Take the 2nd exit at the roundabout")
        val arrival = p.targetZoom(4f, 2500.0, 50.0, "Continue straight")
        assertTrue(closeExit > baseline)
        assertTrue(arrival > baseline)
    }

    @Test fun zoomHysteresisAndMinimumTimePreventCameraPumping() {
        val p = NavigationCameraPolicy()
        assertNotNull(p.consume(1000L, 10f, 2000.0, 5000.0, "Continue"))
        assertNull(p.consume(2000L, 30f, 2000.0, 5000.0, "Continue"))
        assertNull(p.consume(5200L, 30f, 2000.0, 5000.0, "Continue"))
        assertNotNull(p.consume(5800L, 30f, 2000.0, 5000.0, "Continue"))
        assertNull(p.consume(5900L, 31f, 2000.0, 5000.0, "Continue"))
    }

    @Test fun urgentTurnPermitsEarlierZoomInButNotConstantChanges() {
        val p = NavigationCameraPolicy()
        assertNotNull(p.consume(1000L, 26f, 2500.0, 4000.0, "Continue"))
        assertNull(p.consume(2800L, 26f, 70.0, 3000.0, "Turn left"))
        assertNotNull(p.consume(4000L, 26f, 70.0, 3000.0, "Turn left"))
        assertNull(p.consume(4200L, 26f, 65.0, 2950.0, "Turn left"))
    }

    @Test fun resetRestoresAutomaticControlAfterManualZoom() {
        val p = NavigationCameraPolicy()
        val initial = p.consume(10L, 12f, null, null, "")
        assertNotNull(initial)
        p.reset()
        assertEquals(initial, p.consume(12L, 12f, null, null, ""))
    }

    @Test fun invalidSpeedsAndDistancesRemainWithinSafeRange() {
        val p = NavigationCameraPolicy()
        val zoom = p.targetZoom(Float.NaN, Double.NaN, Double.NEGATIVE_INFINITY, "")
        assertTrue(zoom in 16.5..19.35)
    }
}
