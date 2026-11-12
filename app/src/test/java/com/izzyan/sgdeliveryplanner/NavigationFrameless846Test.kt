package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class NavigationFrameless846Test {
    @Test fun directionIconsUseAllTwentyOneSuppliedResourcesAndResponsiveSizes() {
        assertEquals(21, NavigationSign.entries.map { it.drawable }.toSet().size)
        assertEquals(72, NavigationPanelSizing.directionIconDp(320))
        assertEquals(72, NavigationPanelSizing.directionIconDp(360))
        assertEquals(80, NavigationPanelSizing.directionIconDp(400))
        assertEquals(88, NavigationPanelSizing.directionIconDp(480))
    }

    @Test fun rightUTurnIsDefaultAndOnlyExplicitLeftUTurnIsMirrored() {
        listOf(Triple("turn","uturn","Make a U-turn"),
            Triple("turn","uturn right","Make a right U-turn"),
            Triple("uturn","right","Make a U-turn")).forEach { (t,m,i) ->
            val cue = NavigationPanelSigns.cue(t,m,i)
            assertEquals(NavigationSign.UTURN, cue.sign)
            assertFalse(cue.mirrorHorizontally)
        }
        listOf(Triple("turn","uturn left","Make a U-turn"),
            Triple("uturn","left","Make a U-turn"),
            Triple("turn","uturn","Make a left U-turn")).forEach { (t,m,i) ->
            val cue = NavigationPanelSigns.cue(t,m,i)
            assertEquals(NavigationSign.UTURN, cue.sign)
            assertTrue(cue.mirrorHorizontally)
        }
        assertFalse(NavigationPanelSigns.cue("turn","left","Turn left onto U-turn Road").mirrorHorizontally)
        assertFalse(NavigationPanelSigns.cue("turn","uturn","Make a U-turn onto Left Road").mirrorHorizontally)
    }

    @Test fun numberedExitStillUsesActualRouteDataAndRoundaboutOrdinalRemainsSeparate() {
        assertEquals(18f, NavigationPanelSizing.EXIT_FONT_SP, 0.01f)
        assertEquals("24A", NavigationPanelSigns.cue("off ramp","right","Take exit 24A").expresswayExit)
        assertNull(NavigationPanelSigns.cue("off ramp","left","Take the exit").expresswayExit)
        val round = NavigationPanelSigns.cue("roundabout","left","At the roundabout, take the 3rd exit")
        assertEquals(NavigationSign.ROUNDABOUT, round.sign)
        assertEquals(3, round.roundaboutExit)
        assertNull(round.expresswayExit)
        assertFalse(round.mirrorHorizontally)
    }

    @Test fun distanceAndExitTypographyFitAvailableWidthWithoutChangingPreferredSize() {
        assertEquals(18f, NavigationPanelSizing.fitTextSp(18f,110f,90),0.01f)
        assertEquals(15f, NavigationPanelSizing.fitTextSp(18f,75f,90),0.01f)
        assertEquals(32f, NavigationPanelSizing.fitTextSp(32f,120f,100),0.01f)
        assertEquals(24f, NavigationPanelSizing.fitTextSp(32f,75f,100),0.01f)
        assertTrue(NavigationPanelSizing.EXIT_MAX_WIDTH_FRACTION < 0.60f)
    }
}
