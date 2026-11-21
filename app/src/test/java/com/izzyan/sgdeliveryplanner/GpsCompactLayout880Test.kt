package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class GpsCompactLayout880Test {
    @Test fun everyGpsControlShrinksAboutTwentyPercent() {
        assertEquals(52,GpsCompactLayout880.CONTROL_WIDTH_DP)
        assertEquals(49,GpsCompactLayout880.CONTROL_HEIGHT_DP)
        assertEquals(20,GpsCompactLayout880.visibleWidthReductionPercent)
        assertEquals(19,GpsCompactLayout880.visibleHeightReductionPercent)
        assertEquals(20,GpsCompactLayout880.CONTROL_ICON_DP)
    }

    @Test fun allSixControlsShareTheSameCompactDimensions() {
        assertEquals(6,GpsCompactLayout880.CONTROL_COUNT)
        assertEquals(319,GpsCompactLayout880.controlsVerticalExtentDp())
    }

    @Test fun visualControlsStillMeetMinimumTouchTarget() {
        assertTrue(GpsCompactLayout880.CONTROL_WIDTH_DP>=GpsCompactLayout880.MIN_TOUCH_TARGET_DP)
        assertTrue(GpsCompactLayout880.CONTROL_HEIGHT_DP>=GpsCompactLayout880.MIN_TOUCH_TARGET_DP)
    }

    @Test fun speedAndSpeedLimitHaveTheirOwnLaneLeftOfSettings() {
        assertTrue(GpsCompactLayout880.lanesDoNotOverlap())
        assertTrue(GpsCompactLayout880.METER_WIDTH_DP+GpsCompactLayout880.MIN_LANE_GAP_DP <=
            GpsCompactLayout880.RAIL_WIDTH_DP-
                GpsCompactLayout880.RAIL_END_PADDING_DP-GpsCompactLayout880.CONTROL_WIDTH_DP)
    }

    @Test fun speedPanelFitsBetweenNormalTopAndArrivalPanels() {
        assertTrue(GpsCompactLayout880.meterInsideSafeViewport(150f,150f,720f,150f))
        assertTrue(GpsCompactLayout880.meterInsideSafeViewport(160f,165f,800f,150f))
    }

    @Test fun impossibleTinyViewportIsDetectedRatherThanClaimedSafe() {
        assertFalse(GpsCompactLayout880.meterInsideSafeViewport(160f,155f,425f,150f))
    }

    @Test fun textRemainsReadableAndSpeedFontSizeIsNotReducedByControlResizing() {
        assertTrue(GpsCompactLayout880.CONTROL_LABEL_SP>=9)
        assertEquals(108,GpsCompactLayout880.METER_WIDTH_DP)
    }
}
