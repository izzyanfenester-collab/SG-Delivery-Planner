package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** Pure trace-like controller tests; physical-device frame rates require on-road verification. */
class CinematicZoom861Test {
    private fun state(mode: SmartCameraMode, zoom: Double, urgent: Boolean = false) =
        SmartZoomTarget(mode, zoom, 54.0, 0.44, 430L, urgent)

    @Test fun fullRangeZoomIsOneNativeCommandNotStepped() {
        val camera = CinematicZoomCoordinator()
        val target = state(SmartCameraMode.NORMAL_FOLLOW, 16.5)
        val command = requireNotNull(camera.next(1_000L, 13.5, target))
        assertEquals(16.5, command.zoom!!, 0.0001)
        assertEquals(1500L, command.durationMs)
        assertNull(camera.next(1_250L, 14.1, target))
        assertNull(camera.next(1_800L, 15.2, target))
        assertNull(camera.next(2_800L, 16.5, target))
    }

    @Test fun expresswayAndPostManeuverZoomOutAreSlowAndContinuous() {
        val wide = CinematicZoomCoordinator().next(
            1_000L, 18.9, state(SmartCameraMode.EXPRESSWAY_WIDE, 14.2))!!
        val post = CinematicZoomCoordinator().next(
            1_000L, 18.5, state(SmartCameraMode.POST_MANEUVER_WIDE, 15.1))!!
        assertEquals(2200L, wide.durationMs)
        assertEquals(2200L, post.durationMs)
        assertEquals(14.2, wide.zoom!!, 0.001)
    }

    @Test fun criticalExitImmediatelyRetargetsUsingNewActualCameraZoom() {
        val camera = CinematicZoomCoordinator()
        camera.next(1_000L, 17.2, state(SmartCameraMode.EXPRESSWAY_WIDE, 14.2))
        val critical = camera.next(1_090L, 16.9,
            state(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 18.85, urgent = true))!!
        assertEquals(18.85, critical.zoom!!, 0.001)
        assertTrue(critical.durationMs in 400L..800L)
        assertNull(camera.next(1_200L, 17.5,
            state(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 18.85, urgent = true)))
    }

    @Test fun smallSpeedChangesDontCauseZoomRestartAndNormalRetargetsAreLimited() {
        val camera = CinematicZoomCoordinator()
        camera.next(1_000L, 16.0, state(SmartCameraMode.NORMAL_FOLLOW, 17.1))
        assertNull(camera.next(1_100L, 16.3, state(SmartCameraMode.NORMAL_FOLLOW, 17.18)))
        assertNull(camera.next(1_200L, 16.5, state(SmartCameraMode.NORMAL_FOLLOW, 17.65)))
        val updated = camera.next(1_500L, 16.7,
            state(SmartCameraMode.NORMAL_FOLLOW, 17.65))!!
        assertEquals(17.65, updated.zoom!!, 0.001)
    }

    @Test fun interruptedNativeAnimationCanBeRecoveredOnlyAfterTimeout() {
        val camera = CinematicZoomCoordinator()
        camera.next(1_000L, 13.5, state(SmartCameraMode.NORMAL_FOLLOW, 16.5))
        assertNull(camera.next(2_700L, 14.0, state(SmartCameraMode.NORMAL_FOLLOW, 16.5)))
        assertNotNull(camera.next(3_000L, 14.0, state(SmartCameraMode.NORMAL_FOLLOW, 16.5)))
    }

    @Test fun resettingToManualOrOverviewDiscardsPreviousAnimationTarget() {
        val camera = CinematicZoomCoordinator()
        assertNotNull(camera.next(1_000L, 18.0,
            state(SmartCameraMode.EXPRESSWAY_WIDE, 14.2)))
        camera.reset()
        assertFalse(camera.isAnimating(1_050L))
        assertNotNull(camera.next(1_050L, 17.0,
            state(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 18.85, urgent = true)))
    }
}
