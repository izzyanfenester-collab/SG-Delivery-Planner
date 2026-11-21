package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class DualCircleSpeed883Test {
    @Test fun speedAndLimitCirclesHaveExactlySameDiameter() {
        assertEquals(52,NavigationBottomSpeedLayout881.CURRENT_SPEED_SIGN_DP)
        assertEquals(NavigationBottomSpeedLayout881.SPEED_LIMIT_SIGN_DP,
            NavigationBottomSpeedLayout881.CURRENT_SPEED_SIGN_DP)
    }

    @Test fun bothCirclesOccupyEqualWidthLanesAndOneHorizontalRow() {
        assertEquals(64,NavigationBottomSpeedLayout881.SPEED_WIDTH_DP)
        assertEquals(NavigationBottomSpeedLayout881.SPEED_LIMIT_WIDTH_DP,
            NavigationBottomSpeedLayout881.SPEED_WIDTH_DP)
        assertEquals(8,NavigationBottomSpeedLayout881.GAUGE_SPACING_DP)
        assertEquals(136,NavigationBottomSpeedLayout881.pairWidthDp)
    }

    @Test fun numberSizingMatchesLimitCircleForNormalSpeed() {
        assertEquals(32,SpeedLimitSign882.fontSizeSp(85))
        assertEquals(32,SpeedLimitSign882.fontSizeSp(90))
    }

    @Test fun threeDigitSpeedFitsSameSizeCircleWithoutShrinkingSignItself() {
        assertEquals(25,SpeedLimitSign882.fontSizeSp(150))
        assertEquals(25,SpeedLimitSign882.fontSizeSp(100))
        assertEquals(52,NavigationBottomSpeedLayout881.CURRENT_SPEED_SIGN_DP)
    }

    @Test fun smallPhonesCanRetainHorizontallyAlignedPairInBottomPanel() {
        assertTrue(NavigationBottomSpeedLayout881.inlineWithActions(380f,1f))
        assertFalse(NavigationBottomSpeedLayout881.inlineWithActions(340f,1f))
        assertFalse(NavigationBottomSpeedLayout881.inlineWithActions(400f,1.5f))
        assertTrue(NavigationBottomSpeedLayout881.pairWidthDp<=152)
    }

    @Test fun validated90LimitStillShowsDigitsOnlyWithNoInventedValue() {
        assertEquals("90",SpeedLimitSign882.visibleNumber(90))
        assertNull(SpeedLimitSign882.visibleNumber(null))
    }

    @Test fun theExistingOverspeedModelStillDetects150On90MapData() {
        val monitor=OverspeedVisualMonitor879()
        val road=RoadSpeedState875(
            RoadLimit875(90,RoadLimitSource875.MAP_DATA,"https://www.openstreetmap.org/","2026-11-21"),
            "PIE:F","PIE",observedAtMs=1000L)
        val fix=RoadSpeedFix875(GpsPoint(1.32,103.9),1000L,5f,90f,
            (150.0/3.6).toFloat(),0.5f)
        assertTrue(monitor.observe(road,fix,true).red)
    }
}
