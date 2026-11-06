package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDisplayTuningTest {
    @Test fun speedControlsLookAheadWithoutInvalidZoom() {
        assertTrue(NavigationDisplayTuning.zoomForSpeed(0f) > NavigationDisplayTuning.zoomForSpeed(30f))
        assertEquals(16.7, NavigationDisplayTuning.zoomForSpeed(Float.NaN), 0.01)
    }

    @Test fun singaporeNightModeChangesAtSeven() {
        assertTrue(NavigationDisplayTuning.isNightSingapore(19))
        assertTrue(NavigationDisplayTuning.isNightSingapore(6))
        assertTrue(!NavigationDisplayTuning.isNightSingapore(7))
        assertTrue(!NavigationDisplayTuning.isNightSingapore(18))
    }

    @Test fun bearingSmoothlyCrossesNorthAndFreezesAtStop() {
        val smoothed = NavigationDisplayTuning.smoothBearing(355f, 5f, 10f)
        assertTrue(smoothed != null && (smoothed > 355f || smoothed < 5f))
        assertEquals(smoothed, NavigationDisplayTuning.smoothBearing(smoothed, 160f, 0f))
        assertNull(NavigationDisplayTuning.smoothBearing(null, null, 0f))
    }

    @Test fun maneuverArrowReflectsInstructions() {
        assertEquals("↱", NavigationDisplayTuning.maneuverArrow("Turn right onto Ang Mo Kio Avenue 3"))
        assertEquals("↰", NavigationDisplayTuning.maneuverArrow("Keep left"))
        assertEquals("↑", NavigationDisplayTuning.maneuverArrow("Continue for 1 km"))
    }
}
