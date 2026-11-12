package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class NavigationPanelSigns841Test {
    @Test fun allTwentyOneDirectionResourcesAreUnique() {
        assertEquals(21, NavigationSign.entries.size)
        assertEquals(21, NavigationSign.entries.map { it.drawable }.toSet().size)
    }

    @Test fun structuredTypesAndModifiersMapToAllTwentyOneIcons() {
        val cases = listOf(
            Triple("continue", "straight", NavigationSign.STRAIGHT),
            Triple("turn", "left", NavigationSign.TURN_LEFT),
            Triple("turn", "slight left", NavigationSign.SLIGHT_LEFT),
            Triple("turn", "right", NavigationSign.TURN_RIGHT),
            Triple("turn", "slight right", NavigationSign.SLIGHT_RIGHT),
            Triple("turn", "uturn", NavigationSign.UTURN),
            Triple("roundabout", "right", NavigationSign.ROUNDABOUT),
            Triple("turn", "sharp left", NavigationSign.SHARP_LEFT),
            Triple("turn", "sharp right", NavigationSign.SHARP_RIGHT),
            Triple("keep", "left", NavigationSign.KEEP_LEFT),
            Triple("keep", "right", NavigationSign.KEEP_RIGHT),
            Triple("fork", "slight left", NavigationSign.FORK_LEFT),
            Triple("fork", "slight right", NavigationSign.FORK_RIGHT),
            Triple("merge", "left", NavigationSign.MERGE_LEFT),
            Triple("merge", "right", NavigationSign.MERGE_RIGHT),
            Triple("off ramp", "slight left", NavigationSign.EXIT_LEFT),
            Triple("off ramp", "slight right", NavigationSign.EXIT_RIGHT),
            Triple("on ramp", "left", NavigationSign.RAMP_LEFT),
            Triple("on ramp", "right", NavigationSign.RAMP_RIGHT),
            Triple("exit roundabout", "right", NavigationSign.ROUNDABOUT_EXIT),
            Triple("arrive", "left", NavigationSign.ARRIVE)
        )
        for ((type, modifier, expected) in cases)
            assertEquals("$type / $modifier", expected, NavigationPanelSigns.cue(type, modifier, null).sign)
    }

    @Test fun realSingaporeRouteProvidesExit20BAndUnnumberedExitRemainsHidden() {
        val json = requireNotNull(javaClass.getResourceAsStream("/navigation/valhalla-singapore.json"))
            .bufferedReader().use { it.readText() }
        val steps = NavigationPanelSigns.metadata(json)
        assertTrue(steps.size >= 8)
        val exit = NavigationPanelSigns.cue(null, null, null, steps[1], steps[0])
        assertEquals(NavigationSign.EXIT_LEFT, exit.sign)
        assertEquals("20B", exit.expresswayExit)
        assertNull(NavigationPanelSigns.cue(null, null, null, steps[2], steps[1]).expresswayExit)
        val unnamed = NavigationPanelSigns.cue(null, null, null, steps[4], steps[3])
        assertEquals(NavigationSign.EXIT_RIGHT, unnamed.sign)
        assertNull(unnamed.expresswayExit)
    }

    @Test fun explicitExitNumbersAndRouteRefsAreNotConfused() {
        assertEquals("24A", NavigationPanelSigns.cue("off ramp", "left", "Take exit 24A toward road").expresswayExit)
        assertNull(NavigationPanelSigns.cue("continue", "straight", "Continue on 3/Jalan Kota Tinggi").expresswayExit)
        assertNull(NavigationPanelSigns.cue("off ramp", "right", "Take the exit toward 244 Highway").expresswayExit)
        assertNull(NavigationPanelSigns.cue("turn", "left", "Turn left onto Exit 244 Road").expresswayExit)
        assertEquals("7", NavigationPanelSigns.cue("off ramp", "left", null,
            RouteSignMetadata(exitNumber = "7")).expresswayExit)
    }

    @Test fun missingModifierNeverInfersDirectionFromStreetName() {
        assertEquals(NavigationSign.STRAIGHT,
            NavigationPanelSigns.cue("depart", null, "Drive on Right Road").sign)
        assertEquals(NavigationSign.KEEP_LEFT,
            NavigationPanelSigns.cue("keep left", null, null).sign)
        assertEquals(NavigationSign.EXIT_RIGHT,
            NavigationPanelSigns.cue("off ramp", null, "Take the exit on the right").sign)
        assertEquals(NavigationSign.TURN_LEFT,
            NavigationPanelSigns.cue("turn", null, "Turn left onto Right Road").sign)
    }

    @Test fun roundaboutUsesOrdinalNotExpresswayBadge() {
        val cue = NavigationPanelSigns.cue("roundabout", "left", "At the roundabout, take the 3rd exit")
        assertEquals(NavigationSign.ROUNDABOUT, cue.sign)
        assertEquals(3, cue.roundaboutExit)
        assertNull(cue.expresswayExit)
        val leaving = NavigationPanelSigns.cue("exit roundabout", "right", null,
            RouteSignMetadata(roundaboutExit = 2))
        assertEquals(NavigationSign.ROUNDABOUT_EXIT, leaving.sign)
        assertEquals(2, leaving.roundaboutExit)
    }
}
