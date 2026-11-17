package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class SmartNavigationZoomTest {
    private fun input(
        now: Long = 1000, speedKph: Float = 90f, meters: Double? = 5000.0,
        kind: SmartManeuverKind = SmartManeuverKind.EXIT,
        expressway: Boolean = true, step: Int = 1, progress: Double = 0.0,
        following: Double? = 5000.0, matched: Boolean = true, offRoute: Boolean = false
    ) = SmartZoomInput(now, speedKph / 3.6f, meters, following, kind, expressway, null,
        10000.0, step, progress, matched, offRoute, true, 5f)

    @Test fun expresswayWideExitZoomAndCriticalFocus() {
        val controller = SmartNavigationZoom()
        val wide = controller.target(input())!!
        val approaching = controller.target(input(now=2000,meters=2000.0))!!
        val closer = controller.target(input(now=3000,meters=500.0))!!
        val critical = controller.target(input(now=4000,meters=100.0))!!
        assertEquals(SmartCameraMode.EXPRESSWAY_WIDE,wide.mode)
        assertEquals(SmartCameraMode.APPROACHING_MANEUVER,approaching.mode)
        assertEquals(SmartCameraMode.CRITICAL_JUNCTION_FOCUS,critical.mode)
        assertTrue(wide.zoom < approaching.zoom && approaching.zoom < closer.zoom && closer.zoom < critical.zoom)
        assertTrue(critical.forwardPaddingFraction > wide.forwardPaddingFraction)
    }

    @Test fun urbanJunctionApproachAndSecondTurnPreservesFocus() {
        val c=SmartNavigationZoom()
        val normal=c.target(input(speedKph=35f,kind=SmartManeuverKind.TURN,expressway=false,meters=550.0))!!
        val approach=c.target(input(now=2000,speedKph=35f,kind=SmartManeuverKind.TURN,expressway=false,meters=200.0))!!
        val turn=c.target(input(now=3000,speedKph=35f,kind=SmartManeuverKind.TURN,expressway=false,meters=45.0))!!
        assertEquals(SmartCameraMode.NORMAL_FOLLOW,normal.mode)
        assertEquals(SmartCameraMode.APPROACHING_MANEUVER,approach.mode)
        assertEquals(SmartCameraMode.CRITICAL_JUNCTION_FOCUS,turn.mode)
        assertTrue(turn.zoom > normal.zoom)
        assertEquals(SmartCameraMode.MANEUVER_TRANSITION,
            c.target(input(now=4000,speedKph=35f,kind=SmartManeuverKind.TURN,expressway=false,
                meters=150.0,step=2,progress=10.0,following=150.0))!!.mode)
        val next=c.target(input(now=5000,speedKph=35f,kind=SmartManeuverKind.TURN,
            expressway=false,meters=80.0,step=2,progress=50.0,following=150.0))!!
        assertEquals(SmartCameraMode.CRITICAL_JUNCTION_FOCUS,next.mode)
    }

    @Test fun completedExitReturnsWideOnlyWhenNextManeuverFar() {
        val c=SmartNavigationZoom()
        c.target(input(meters=40.0))
        val transition=c.target(input(now=2000,step=2,progress=10.0,meters=5000.0))!!
        assertEquals(SmartCameraMode.MANEUVER_TRANSITION,transition.mode)
        val post=c.target(input(now=3200,step=2,progress=60.0,meters=5000.0))!!
        assertEquals(SmartCameraMode.POST_MANEUVER_WIDE,post.mode)
        assertEquals(SmartCameraMode.EXPRESSWAY_WIDE,c.target(input(now=4200,
            step=2,progress=90.0,meters=5000.0))!!.mode)
    }

    @Test fun rerouteCancelsObsoleteFocus() {
        val c=SmartNavigationZoom()
        c.target(input(meters=50.0))
        assertNull(c.target(input(now=2000,offRoute=true)))
        assertEquals(SmartCameraMode.EXPRESSWAY_WIDE,c.target(input(now=3000,meters=6000.0))!!.mode)
    }

    @Test fun routeMetadataTypeAvoidsMistakingRoundaboutForExpresswayExit() {
        assertEquals(SmartManeuverKind.EXIT, SmartManeuverClassifier.kind("off_ramp","Take the exit on left"))
        assertEquals(SmartManeuverKind.TURN, SmartManeuverClassifier.kind("roundabout","At roundabout take 2nd exit"))
        assertFalse(SmartManeuverClassifier.isExpressway(null))
        assertTrue(SmartManeuverClassifier.isExpressway("Pan Island Expressway (PIE)"))
    }
}
