package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Deterministic synthetic driving traces; actual phone/on-road acceptance is separate. */
class NavigationUltimateSmooth839Test {
    private val bend = listOf(
        GpsPoint(1.3500, 103.8200),
        GpsPoint(1.3510, 103.8200),
        GpsPoint(1.3510, 103.8210),
        GpsPoint(1.3510, 103.8220)
    )

    private fun matchOnNorth(lat: Double = 1.35050) =
        NavigationRoadMatch(GpsPoint(lat, 103.8200), 0f, 2.0, 0)

    @Test fun routeGeometryPredictsRealUpcomingCornerWithoutHardcodedInstruction() {
        val corner = requireNotNull(NavigationCornerForecast.next(bend, matchOnNorth()))
        assertTrue(corner.distanceMeters in 45.0..70.0)
        assertTrue(corner.angleDegrees in 80f..100f)
        assertNull(NavigationCornerForecast.next(emptyList(), matchOnNorth()))
        assertNull(NavigationCornerForecast.next(bend, null))
    }

    @Test fun noCurvatureOnStraightRouteAndNoInventedTurn() {
        val straight = listOf(
            GpsPoint(1.3500, 103.8200), GpsPoint(1.3510, 103.8200),
            GpsPoint(1.3520, 103.8200)
        )
        assertNull(NavigationCornerForecast.next(straight, matchOnNorth()))
    }

    @Test fun unifiedCameraMovesDirectlyToFullTargetAndDoesNotRestartEachGpsFix() {
        val director = NavigationCameraDirector()
        val wide = requireNotNull(director.choose(
            1_000L, 17.0, 54.0, 25f, 5_000.0, "Take the exit", 9_000.0,
            null, true, null, roadName="PIE", maneuverType="off_ramp"))
        assertEquals(14.5, wide.zoom!!, 0.01) // full wide target, not one zoom step
        assertEquals(2200L, wide.durationMs)
        assertNull(director.choose(
            1_200L, 16.4, 54.0, 25f, 5_000.0, "Take the exit", 9_000.0,
            null, true, null, roadName="PIE", maneuverType="off_ramp"))
        assertNull(director.choose(
            1_800L, 15.2, 54.0, 25f, 5_000.0, "Take the exit", 9_000.0,
            null, true, null, roadName="PIE", maneuverType="off_ramp"))
        val critical = requireNotNull(director.choose(
            1_900L, 15.0, 54.0, 25f, 100.0, "Take the exit", 9_000.0,
            null, true, null, roadName="PIE", maneuverType="off_ramp"))
        assertTrue(critical.zoom!! > 18.0)
        assertTrue(critical.durationMs in 400L..800L)
    }

    @Test fun cameraPreparesTurnAndPreservesManualZoom() {
        val corner = NavigationCorner(65.0, 90f)
        val director = NavigationCameraDirector()
        val turnZoom = requireNotNull(director.choose(
            1_000L, 17.5, 54.0, 16f, 65.0, "Turn right", 1_200.0,
            corner, true, null, maneuverType="turn"))
        assertTrue(requireNotNull(turnZoom.zoom) > 17.5)
        director.reset(19.0, 54.0)
        val pitch = requireNotNull(director.choose(
            2_000L, 19.0, 54.0, 16f, 65.0, "Turn right", 1_200.0,
            corner, true, null, maneuverType="turn"))
        assertNull(pitch.zoom)
        assertNotNull(pitch.pitch)
        assertTrue(requireNotNull(pitch.pitch) in 47.0..54.0)
        director.reset(18.3, 54.0)
        assertNull(director.choose(
            3_000L, 18.3, 54.0, 0f, 50.0, "Turn left", 900.0,
            corner, true, 18.3))
    }

    @Test fun manualZoomIsNeverOverriddenBySmartDirectorUntilRecenter() {
        val director = NavigationCameraDirector()
        director.reset(19.2, 54.0)
        assertNull(director.choose(
            1_000L, 19.2, 54.0, 24f, null, "Continue", 9_000.0,
            null, true, 19.5))
        director.reset(15.9, 54.0)
        assertNull(director.choose(
            2_000L, 15.9, 54.0, 0f, null, "Continue", 9_000.0,
            null, true, 15.5))
    }

    @Test fun switchingFrom3dTo2dAdjustsPitchOnlyAtTargetZoom() {
        val director = NavigationCameraDirector()
        director.reset(18.05, 54.0)
        val cmd = requireNotNull(director.choose(
            1_000L, 18.05, 54.0, 0f, null, "Continue", 9_000.0,
            null, false, null))
        assertNull(cmd.zoom)
        assertEquals(0.0, cmd.pitch!!, 0.001)
    }

    @Test fun jumpFilterAcceptsNormalMotionButRejectsUnrealisticTeleport() {
        val guard = NavigationGpsSanityFilter()
        fun s(lat: Double, t: Long, speed: Float = 7f) =
            NavigationGpsSample(GpsPoint(lat, 103.8200), t, 7f, speed)
        assertTrue(guard.accept(s(1.35000, 1000)))
        assertTrue(guard.accept(s(1.35004, 2000)))
        assertFalse(guard.accept(s(1.37000, 3000)))
        // Rejection leaves previous valid location unchanged; recovery smooth.
        assertTrue(guard.accept(s(1.35008, 4000)))
        assertFalse(guard.accept(s(1.35012, 4000))) // duplicate timestamp
        // Genuine reacquisition after >12 seconds may be far from old route.
        assertTrue(guard.accept(s(1.37000, 17000)))
    }

    @Test fun stationaryTrafficNoFalseMovementFromSmallGpsNoise() {
        val road = listOf(
            GpsPoint(1.35000,103.82000), GpsPoint(1.35100,103.82000))
        val tracker = NavigationVisualTracker()
        val first = tracker.update(GpsPoint(1.35050,103.82000), road, 8f, 0f, null)
        val second = tracker.update(GpsPoint(1.35052,103.82003), road, 8f, 0f, null)
        assertEquals(first.position, second.position)
        assertEquals(first.bearingDegrees, second.bearingDegrees)
    }

    @Test fun routePolylineStaysMonotonicAndPuckHasSharedBlueGreyBoundary() {
        val tracker = NavigationRouteProgressTracker()
        tracker.reset(bend)
        val f1 = requireNotNull(tracker.advance(matchOnNorth(1.35030), 8f, 1000))
        val f2 = requireNotNull(tracker.advance(matchOnNorth(1.35050), 8f, 3000))
        assertTrue(f2.progressedMeters > f1.progressedMeters)
        assertEquals(f2.traveled.last(), f2.remaining.first())
        assertEquals(f2.boundary, f2.traveled.last())
        val noisy = requireNotNull(tracker.advance(matchOnNorth(1.35049), 8f, 4000))
        assertEquals(f2.progressedMeters, noisy.progressedMeters, 0.01)
        assertEquals(f2.boundary, noisy.boundary)
        assertEquals(f2.progressedMeters,
            requireNotNull(tracker.advance(null, 8f, 5000)).progressedMeters, 0.01)
    }

    @Test fun maneuverIconsMatchRealRoutingCueTypes() {
        assertEquals("↖", NavigationDisplayTuning.maneuverArrow("Slight left onto West Road"))
        assertEquals("↗", NavigationDisplayTuning.maneuverArrow("Slight right"))
        assertEquals("↶", NavigationDisplayTuning.maneuverArrow("Make a U-turn"))
        assertEquals("⟳", NavigationDisplayTuning.maneuverArrow("At roundabout take 3rd exit"))
        assertEquals("⌖", NavigationDisplayTuning.maneuverArrow("Arrive at destination"))
    }
}
