package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** Recorded-timing style synthetic GPS camera samples; on-road perceptual
 * smoothness and actual device frame pacing still require physical testing.
 */
class CinematicZoom863Test {
    private fun target(
        mode: SmartCameraMode = SmartCameraMode.NORMAL_FOLLOW,
        zoom: Double = 17.0,
        urgent: Boolean = false
    ) = SmartZoomTarget(mode, zoom, 54.0, 0.44, 430L, urgent)

    @Test fun stableTargetEveryGpsFixNeverRestartsNativeAnimation() {
        val c = CinematicZoomCoordinator()
        val goal = target(zoom = 16.8)
        val initial = requireNotNull(c.next(1_000, 13.5, goal))
        assertEquals(16.8, initial.zoom!!, 0.001)
        assertEquals(1500L, initial.durationMs)
        assertTrue(c.isAnimating(1_100))
        assertNull(c.next(1_200, 14.1, goal))
        assertNull(c.next(1_800, 15.8, goal))
        c.onNativeResult(initial.token, cancelled = false)
        assertFalse(c.isAnimating(2_300))
        assertNull(c.next(2_500, 16.8, goal))
    }

    @Test fun smallSameDirectionDriftIsCoalescedNotInterrupted() {
        val c = CinematicZoomCoordinator()
        val goal = target(SmartCameraMode.EXPRESSWAY_WIDE, 14.2)
        val first = requireNotNull(c.next(1_000, 18.5, goal))
        assertEquals(2200L, first.durationMs)
        // GPS speed changes a little while native zoom-out is still in flight.
        assertNull(c.next(1_500, 17.4, goal.copy(zoom = 14.44)))
        assertNull(c.next(2_000, 16.0, goal.copy(zoom = 14.44)))
        assertTrue(c.isAnimating(2_000))
    }

    @Test fun substantialTargetChangesRetargetFromActualZoomWithSpeedAwareTiming() {
        val c = CinematicZoomCoordinator()
        val first = requireNotNull(c.next(1_000, 16.0, target(zoom = 17.1)))
        assertNotNull(first.zoom)
        assertNull(c.next(1_300, 16.3, target(zoom = 17.1)))
        val updated = requireNotNull(c.next(1_600, 16.8, target(zoom = 18.0)))
        assertEquals(18.0, updated.zoom!!, 0.001)
        assertTrue(updated.durationMs in 550L..1500L)
        assertTrue(updated.token > first.token)
    }

    @Test fun urgentJunctionRetargetsImmediatelyWithoutChangingB862Target() {
        val c = CinematicZoomCoordinator()
        val old = requireNotNull(c.next(1_000, 16.0,
            target(SmartCameraMode.EXPRESSWAY_WIDE, 14.2)))
        val urgent = requireNotNull(c.next(1_090, 15.8,
            target(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 17.85, urgent = true)))
        assertEquals(17.85, urgent.zoom!!, 0.001)
        assertTrue(urgent.durationMs in 400L..800L)
        c.onNativeResult(old.token, cancelled = true) // stale cancel callback
        assertTrue(c.isAnimating(1_200))
        assertNull(c.next(1_200, 16.4,
            target(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 17.85, urgent = true)))
    }

    @Test fun nativeCancelledCanRecoverButNeverStartsAnotherCommandFromCallback() {
        val c = CinematicZoomCoordinator()
        val cmd = requireNotNull(c.next(1_000, 14.0, target(zoom = 17.0)))
        c.onNativeResult(cmd.token, cancelled = true)
        assertFalse(c.isAnimating(1_050))
        // Recovery occurs only on the next normal camera sample, not in callback.
        val retry = requireNotNull(c.next(1_200, 14.2, target(zoom = 17.0)))
        assertEquals(17.0, retry.zoom!!, 0.001)
    }

    @Test fun tinyCorrectionsAreFasterThanFullDistanceSlowMotion() {
        val wide = target(SmartCameraMode.EXPRESSWAY_WIDE, 14.2)
        val small = requireNotNull(CinematicZoomCoordinator().next(1_000, 14.78, wide))
        val large = requireNotNull(CinematicZoomCoordinator().next(1_000, 18.0, wide))
        assertTrue(small.durationMs < large.durationMs)
        assertEquals(2200L, large.durationMs)
    }

    @Test fun manualResetPreventsCanceledOldNativeCallbacksFromChangingFreshState() {
        val c = CinematicZoomCoordinator()
        val first = requireNotNull(c.next(1_000, 17.0,
            target(SmartCameraMode.EXPRESSWAY_WIDE, 14.2)))
        c.reset()
        assertFalse(c.isAnimating(1_100))
        val second = requireNotNull(c.next(1_150, 17.0,
            target(SmartCameraMode.CRITICAL_JUNCTION_FOCUS, 18.18, true)))
        c.onNativeResult(first.token, cancelled = true)
        assertTrue(c.isAnimating(1_200))
        assertNotEquals(first.token, second.token)
    }
}
