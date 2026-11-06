package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationDisplayTuningTest {
    @Test fun speedControlsLookAheadWithoutInvalidZoom() {
        assertTrue(NavigationDisplayTuning.zoomForSpeed(0f) > NavigationDisplayTuning.zoomForSpeed(30f))
        assertEquals(17.8, NavigationDisplayTuning.zoomForSpeed(Float.NaN), 0.01)
        assertTrue(NavigationDisplayTuning.zoomForSpeed(15f) >= 17.5)
    }

    @Test fun cameraPaddingPositionsVehicleLowerAndZoomButtonsClamp() {
        val padding = NavigationDisplayTuning.trackingPadding(1000)
        assertEquals(0.0, padding[0], 0.01)
        assertEquals(440.0, padding[1], 0.01)
        assertEquals(0.0, padding[3], 0.01)
        assertEquals(19.5, NavigationDisplayTuning.adjustedZoom(19.4, 0.5), 0.01)
        assertEquals(15.5, NavigationDisplayTuning.adjustedZoom(15.6, -0.5), 0.01)
    }

    @Test fun bannerHighlightsRoadAndRoundaboutExit() {
        assertEquals("Persiaran Indahpura 2", NavigationDisplayTuning.nextRoadLabel(
            "Turn right onto Persiaran Indahpura 2", "Current Road"))
        assertEquals("EXIT 2", NavigationDisplayTuning.roundaboutExit(
            "Enter Bulatan and take the 2nd exit onto Jalan Utama"))
        assertNull(NavigationDisplayTuning.roundaboutExit("Continue straight"))
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
