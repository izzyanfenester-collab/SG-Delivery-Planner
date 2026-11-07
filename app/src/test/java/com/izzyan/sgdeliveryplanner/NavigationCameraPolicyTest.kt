package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationCameraPolicyTest {
    @Test fun highwayWiderThanCityAndStationary() {
        val stop = NavigationCameraPolicy.targetZoom(0f, 3000.0, "Continue", 9000.0)
        val city = NavigationCameraPolicy.targetZoom(10f, 3000.0, "Continue", 9000.0)
        val highway = NavigationCameraPolicy.targetZoom(28f, 3000.0, "Continue", 9000.0)
        assertTrue(stop > city && city > highway)
    }

    @Test fun approachingRoundaboutZoomsInAndDestinationIsClose() {
        val road = NavigationCameraPolicy.targetZoom(18f, 2000.0, "Continue", 5000.0)
        val roundabout = NavigationCameraPolicy.targetZoom(18f, 75.0,
            "At roundabout take the 2nd exit", 5000.0)
        val arrival = NavigationCameraPolicy.targetZoom(18f, null, "Continue", 70.0)
        assertTrue(roundabout > road)
        assertTrue(arrival > road)
    }

    @Test fun stopsChatteringAndAvoidsLargeJump() {
        val ctrl = NavigationZoomController()
        assertEquals(18.8, ctrl.choose(18.8, 1000L)!!, 0.001)
        assertNull(ctrl.choose(18.7, 5000L))
        assertNull(ctrl.choose(17.4, 2000L))
        assertEquals(18.1, ctrl.choose(17.4, 6000L)!!, 0.001)
        assertNull(ctrl.choose(17.4, 6500L))
        assertTrue(ctrl.choose(19.1, 7000L, urgent = true)!! > 18.1)
    }
}
