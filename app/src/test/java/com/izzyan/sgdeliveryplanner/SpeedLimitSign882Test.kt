package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

/** B882: speed-limit sign must show digits ONLY while current-speed km/h stays. */
class SpeedLimitSign882Test {
    @Test fun speedLimit90IsDigitsOnly() {
        assertEquals("90",SpeedLimitSign882.visibleNumber(90))
        assertEquals(32,SpeedLimitSign882.fontSizeSp(90))
    }

    @Test fun validNumbers80And100RemainDigitsOnly() {
        assertEquals("80",SpeedLimitSign882.visibleNumber(80))
        assertEquals("100",SpeedLimitSign882.visibleNumber(100))
        assertEquals(32,SpeedLimitSign882.fontSizeSp(80))
        assertEquals(25,SpeedLimitSign882.fontSizeSp(100))
    }

    @Test fun missingOrInvalidLimitDoesNotInventNumber() {
        assertNull(SpeedLimitSign882.visibleNumber(null))
        assertNull(SpeedLimitSign882.visibleNumber(0))
        assertNull(SpeedLimitSign882.visibleNumber(-1))
    }

    @Test fun originalCircleAndHorizontalMeterDimensionsRemainUnchanged() {
        assertEquals(52,NavigationBottomSpeedLayout881.SPEED_LIMIT_SIGN_DP)
        assertEquals(64,NavigationBottomSpeedLayout881.SPEED_LIMIT_WIDTH_DP)
        assertEquals(142,NavigationBottomSpeedLayout881.pairWidthDp)
        assertTrue(NavigationBottomSpeedLayout881.inlineWithActions(400f,1f))
    }

    @Test fun speedLimitSignEnlargedWithoutChangingCurrentSpeedNumberSize() {
        assertTrue(SpeedLimitSign882.fontSizeSp(90)>23)
        assertTrue(SpeedLimitSign882.fontSizeSp(100)>23)
    }

    @Test fun numericValueDoesNotIncludeUnitSourceLabelOrDate() {
        val label=checkNotNull(SpeedLimitSign882.visibleNumber(90))
        assertTrue(label.all(Char::isDigit))
        assertFalse(label.contains("km/h",ignoreCase=true))
        assertFalse(label.contains("MAP DATA",ignoreCase=true))
        assertFalse(label.contains("2026"))
    }
}
