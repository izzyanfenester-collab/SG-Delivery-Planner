package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** Deterministic parameterized driving states; real-device and GPS trace replay
 * remain acceptance tasks, not something unit tests can falsely certify.
 */
class BalancedJunctionZoom862Test {
    private fun state(mode: SmartCameraMode, zoom: Double = 19.0, urgent: Boolean = true) =
        SmartZoomTarget(mode, zoom, 48.0, 0.52, 250L, urgent)

    private fun apply(speedKph: Float = 50f,
                      kind: SmartManeuverKind = SmartManeuverKind.TURN,
                      expressway: Boolean = false,
                      mode: SmartCameraMode = SmartCameraMode.CRITICAL_JUNCTION_FOCUS,
                      zoom: Double = 19.0,
                      width: Float = 390f, height: Float = 800f,
                      next: Double? = null,
                      accuracy: Float = 5f,
                      corner: NavigationCorner? = null) =
        BalancedJunctionZoom().apply(state(mode, zoom), speedKph / 3.6f,
            expressway, kind, corner, next, accuracy, width, height)

    @Test fun cityCriticalZoomIsAroundPointEightLessAndExitWiderThanCity() {
        val city = apply().zoom
        val highway = apply(kind = SmartManeuverKind.EXIT, expressway = true).zoom
        assertTrue(city in 18.0..18.4)
        assertTrue(highway in 17.7..18.1)
        assertTrue(highway < city)
        assertTrue(19.0 - city in 0.5..1.0)
    }

    @Test fun atFiftyOrLessMetersDoesNotMagnifyMoreThanAtHundredMeters() {
        val controller = BalancedJunctionZoom()
        for (meters in listOf(500.0, 200.0, 100.0, 50.0, 0.0)) {
            val raw = state(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 19.0)
            val limited = controller.apply(raw, 13f, false, SmartManeuverKind.TURN,
                NavigationCorner(meters, 65f), 300.0, 5f, 390f, 800f)
            assertEquals(18.18, limited.zoom, 0.001)
            assertEquals(raw.pitch, limited.pitch, 0.001)
            assertEquals(raw.forwardPaddingFraction, limited.forwardPaddingFraction, 0.001)
        }
    }

    @Test fun highSpeedWiderThanLowSpeedButManualCanStillExceedCap() {
        val fast = apply(speedKph = 98f).zoom
        val slow = apply(speedKph = 18f).zoom
        assertTrue(fast < slow)
        assertTrue(slow <= 18.4)
        val manual = state(SmartCameraMode.NORMAL_FOLLOW, 19.5)
        assertEquals(19.5, BalancedJunctionZoom().apply(manual, 3f, false,
            SmartManeuverKind.NONE, null, null, 5f, 390f, 800f).zoom, 0.0)
    }

    @Test fun screenSizeJunctionComplexityAndGpsNoiseWidenRatherThanOverZoom() {
        val normal = apply().zoom
        val smallScreen = apply(width = 320f).zoom
        val complicated = apply(next = 100.0, corner = NavigationCorner(80.0, 90f)).zoom
        val poorGps = apply(accuracy = 30f).zoom
        assertTrue(smallScreen < normal)
        assertTrue(complicated < normal)
        assertTrue(poorGps < normal)
        assertTrue(apply(width = 460f).zoom >= normal)
    }

    @Test fun originalExpresswayWideNormalAndPostTurnTargetsRemainUnchanged() {
        val controller = BalancedJunctionZoom()
        for (mode in listOf(SmartCameraMode.EXPRESSWAY_WIDE,
            SmartCameraMode.NORMAL_FOLLOW, SmartCameraMode.POST_MANEUVER_WIDE)) {
            val raw = state(mode, 19.0)
            assertEquals(19.0, controller.apply(raw, 28f, true,
                SmartManeuverKind.EXIT, null, null, 5f, 390f, 800f).zoom, 0.0)
        }
    }

    @Test fun speedBandHysteresisAvoidsTargetOscillationNearBoundary() {
        val controller = BalancedJunctionZoom()
        fun zoom(speed: Float) = controller.apply(state(
            SmartCameraMode.CRITICAL_JUNCTION_FOCUS), speed / 3.6f, false,
            SmartManeuverKind.TURN, null, null, 5f, 390f, 800f).zoom
        val medium = zoom(50f)
        assertEquals(medium, zoom(36f), 0.001)
        assertEquals(medium, zoom(33f), 0.001)
        val low = zoom(29f)
        assertTrue(low > medium)
        assertEquals(low, zoom(40f), 0.001)
        assertEquals(medium, zoom(45f), 0.001)
    }

    @Test fun baseB861CinematicCoordinatorStillDeduplicatesSameLimitedTarget() {
        val controller = CinematicZoomCoordinator()
        val limited = apply().copy(mode = SmartCameraMode.CRITICAL_JUNCTION_FOCUS)
        assertNotNull(controller.next(1000L, 16.0, limited))
        assertNull(controller.next(1200L, 17.1, limited))
        assertNull(controller.next(1300L, 17.5, limited))
    }

    @Test fun directorCriticalExitCapsTargetAndManualOverrideIsNotTouched() {
        val d = NavigationCameraDirector()
        val cmd = requireNotNull(d.choose(1000L, 15.0, 54.0, 25f,
            100.0, "Take the exit", 9000.0, null, true, null,
            roadName = "PIE", maneuverType = "off_ramp",
            screenWidthDp = 390f, screenHeightDp = 800f))
        assertTrue(cmd.zoom!! <= 18.05)
        assertTrue(cmd.durationMs in 400L..800L)
        assertNull(d.choose(1500L, 19.5, 54.0, 25f,
            80.0, "Take the exit", 9000.0, null, true, 19.5,
            roadName = "PIE", maneuverType = "off_ramp",
            screenWidthDp = 390f, screenHeightDp = 800f))
    }
}
