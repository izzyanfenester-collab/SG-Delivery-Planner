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

    @Test fun unifiedCameraLimitsZoomStepAndHysteresis() {
        val director = NavigationCameraDirector()
        val before = requireNotNull(director.choose(
            1_000L, 17.0, 54.0, 0f, null, "Continue", 5_000.0,
            null, true, null))
        assertNotNull(before.zoom)
        assertTrue(requireNotNull(before.zoom) - 17.0 <= 0.42001)
        assertNull(director.choose(
            1_200L, before.zoom!!, 54.0, 0f, null, "Continue", 5_000.0,
            null, true, null))
        val next = requireNotNull(director.choose(
            1_800L, before.zoom!!, 54.0, 0f, null, "Continue", 5_000.0,
            null, true, null))
        assertTrue(next.durationMs in 500..750)
        assertNotNull(next.zoom)
        assertNull(next.pitch)
    }

    @Test fun cameraPreparesTurnWithSlowZoomAndPitchAndPreservesManualZoom() {
        val corner = NavigationCorner(65.0, 90f)
        val director = NavigationCameraDirector()
        val turnZoom = requireNotNull(director.choose(
            1000, 17.5, 54.0, 16f, 65.0, "Continue", 1200.0,
            corner, true, null))
        assertTrue(requireNotNull(turnZoom.zoom) > 17.5)
        director.reset(18.7, 54.0)
        val pitch = requireNotNull(director.choose(
            2000, 18.7, 54.0, 16f, 65.0, "Continue", 1200.0,
            corner, true, null))
        assertNull(pitch.zoom)
        assertNotNull(pitch.pitch)
        assertTrue(requireNotNull(pitch.pitch) in 49.0..54.0)
        director.reset(18.3, 54.0)
        val manual = director.choose(3000, 18.3, 54.0,
            0f, 50.0, "Turn left", 900.0, corner, true, 18.3)
        // No automatic speed / junction zoom overrides the chosen manual zoom.
        assertTrue(manual == null || manual.zoom == null)
    }

    @Test fun manualZoomRetainsOriginalExtremeLimitsUntilRecenter() {
        val director = NavigationCameraDirector()
        director.reset(19.2, 54.0)
        val zoomIn = requireNotNull(director.choose(
            1000, 19.2, 54.0, 24f, null, "Continue", 9000.0,
            null, true, 19.5))
        assertTrue(requireNotNull(zoomIn.zoom) > 19.2)
        director.reset(15.9, 54.0)
        val zoomOut = requireNotNull(director.choose(
            2000, 15.9, 54.0, 0f, null, "Continue", 9000.0,
            null, true, 15.5))
        assertTrue(requireNotNull(zoomOut.zoom) < 15.9)
    }

    @Test fun switchingFrom3dTo2dAdjustsPitchOnly() {
        val director = NavigationCameraDirector()
        director.reset(18.8, 54.0)
        val cmd = requireNotNull(director.choose(
            1000, 18.8, 54.0, 0f, null, "Continue", 9000.0,
            null, false, 18.8))
        assertNull(cmd.zoom)
        assertEquals(49.0, cmd.pitch!!, 0.001)
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
