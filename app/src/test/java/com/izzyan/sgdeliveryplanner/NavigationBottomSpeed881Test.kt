package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class NavigationBottomSpeed881Test {
    @Test fun speedAndLimitAreHorizontalSiblingsWithExactWidth() {
        assertEquals(64,NavigationBottomSpeedLayout881.SPEED_WIDTH_DP)
        assertEquals(64,NavigationBottomSpeedLayout881.SPEED_LIMIT_WIDTH_DP)
        assertEquals(8,NavigationBottomSpeedLayout881.GAUGE_SPACING_DP)
        assertEquals(136,NavigationBottomSpeedLayout881.pairWidthDp)
    }

    @Test fun widerPanelUsesSingleRowWithDistanceAndDelivery() {
        assertTrue(NavigationBottomSpeedLayout881.inlineWithActions(400f,1f))
        assertTrue(NavigationBottomSpeedLayout881.inlineWithActions(370f,1f))
    }

    @Test fun smallScreenStillKeepsBothGaugesHorizontalInOwnPanelRow() {
        assertFalse(NavigationBottomSpeedLayout881.inlineWithActions(335f,1f))
        assertFalse(NavigationBottomSpeedLayout881.inlineWithActions(340f,1.5f))
    }

    @Test fun largerAndroidFontChangesRowBreakWithoutReducingMeterText() {
        assertTrue(NavigationBottomSpeedLayout881.inlineWithActions(400f,1f))
        assertFalse(NavigationBottomSpeedLayout881.inlineWithActions(400f,1.5f))
    }

    @Test fun layoutNeverRequiresStackingTheTwoGauges() {
        assertTrue(NavigationBottomSpeedLayout881.pairWidthDp<=175)
        assertTrue(NavigationBottomSpeedLayout881.SPEED_LIMIT_SIGN_DP>=48)
        assertEquals(NavigationBottomSpeedLayout881.SPEED_LIMIT_WIDTH_DP,NavigationBottomSpeedLayout881.SPEED_WIDTH_DP)
    }

    @Test fun originalControlsStayTwentyPercentSmallerWithUsableTouchTargets() {
        assertEquals(52,GpsCompactLayout880.CONTROL_WIDTH_DP)
        assertEquals(49,GpsCompactLayout880.CONTROL_HEIGHT_DP)
        assertTrue(GpsCompactLayout880.CONTROL_WIDTH_DP>=GpsCompactLayout880.MIN_TOUCH_TARGET_DP)
        assertTrue(GpsCompactLayout880.CONTROL_HEIGHT_DP>=GpsCompactLayout880.MIN_TOUCH_TARGET_DP)
    }

    @Test fun visualSpeedRemainsIndependentOfPandaVoiceCooldown() {
        val meter=OverspeedVisualMonitor879()
        val road=RoadSpeedState875(
            RoadLimit875(90,RoadLimitSource875.MAP_DATA,"https://www.openstreetmap.org/","2026-11-21"),
            "PIE:F","PIE",observedAtMs=1000)
        val fix=RoadSpeedFix875(GpsPoint(1.32,103.9),1000,5f,90f,
            (150.0/3.6).toFloat(),0.5f)
        assertTrue(meter.observe(road,fix,true).red)
    }
}
