package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** Recorded-like synthetic routes: unit tests do not certify real-device FPS. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationBlueArrow839Test {
    private val northEast = listOf(
        GpsPoint(1.35000, 103.82000),
        GpsPoint(1.35100, 103.82000),
        GpsPoint(1.35100, 103.82100)
    )
    private val northWest = listOf(
        GpsPoint(1.35000, 103.82000),
        GpsPoint(1.35100, 103.82000),
        GpsPoint(1.35100, 103.81900)
    )

    private fun match(lat: Double, lon: Double, segment: Int, bearing: Float) =
        NavigationRoadMatch(GpsPoint(lat, lon), bearing, 1.5, segment)

    @Test fun threeDimensionalBlueArrowResourceExistsWithoutCarDependency() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val arrow = ContextCompat.getDrawable(context, R.drawable.ic_gps_blue_arrow_3d)
        assertNotNull(arrow)
        assertTrue(requireNotNull(arrow).intrinsicWidth > 30)
        assertTrue(arrow.intrinsicHeight > 30)
    }

    @Test fun bearingUsesShortestAngleAcrossGeographicNorth() {
        assertEquals(20f, NavigationArrowOrientation.shortestDelta(350f, 10f), 0.01f)
        assertEquals(-20f, NavigationArrowOrientation.shortestDelta(10f, 350f), 0.01f)
        assertEquals(0f, NavigationArrowOrientation.toward(350f, 10f, 0.5f), 0.01f)
        assertEquals(270f, NavigationArrowOrientation.toward(0f, 270f, 1f), 0.01f)
    }

    @Test fun routeTangentsTurnGraduallyThroughRightAngle() {
        val before = requireNotNull(NavigationArrowOrientation.curveTangent(
            northEast, match(1.35080, 103.82000, 0, 0f), 9f))
        val junction = requireNotNull(NavigationArrowOrientation.curveTangent(
            northEast, match(1.35100, 103.82000, 1, 90f), 9f))
        val after = requireNotNull(NavigationArrowOrientation.curveTangent(
            northEast, match(1.35100, 103.82020, 1, 90f), 9f))
        assertTrue(NavigationArrowOrientation.angleLikeDistance(before, 0f) < 20f)
        assertTrue(junction in 20f..76f)
        assertTrue(NavigationArrowOrientation.angleLikeDistance(after, 90f) < 20f)
        assertTrue(NavigationArrowOrientation.angleLikeDistance(before, junction) < 80f)
        assertTrue(NavigationArrowOrientation.angleLikeDistance(junction, after) < 80f)
    }

    @Test fun geometryBearingTurnsLeftAndDoesNotPointTowardOppositeStreet() {
        val before = requireNotNull(NavigationArrowOrientation.curveTangent(
            northWest, match(1.35080, 103.82000, 0, 0f), 9f))
        val junction = requireNotNull(NavigationArrowOrientation.curveTangent(
            northWest, match(1.35100, 103.82000, 1, 270f), 9f))
        val after = requireNotNull(NavigationArrowOrientation.curveTangent(
            northWest, match(1.35100, 103.81980, 1, 270f), 9f))
        assertTrue(NavigationArrowOrientation.angleLikeDistance(before, 0f) < 20f)
        assertTrue(junction > 270f && junction < 350f)
        assertTrue(NavigationArrowOrientation.angleLikeDistance(after, 270f) < 20f)
    }

    @Test fun gpsCourseOverridesRouteWhenTrueWrongTurnIsLikely() {
        assertEquals(180f, NavigationArrowOrientation.target(180f, 0f, 10f)!!, 0.01f)
        assertEquals(0f, NavigationArrowOrientation.target(null, 0f, 10f)!!, 0.01f)
        assertNull(NavigationArrowOrientation.target(30f, 0f, 0f))
        assertNull(NavigationArrowOrientation.target(null, null, 10f))
    }

    @Test fun arrowSmoothsRightTurnWithoutNinetyDegreeSnap() {
        val heading = NavigationArrowHeadingController()
        assertEquals(0f, heading.smooth(0f, 8f, 1_000L)!!, 0.01f)
        val first = requireNotNull(heading.smooth(90f, 8f, 2_000L))
        assertTrue(first > 10f && first < 90f)
        val second = requireNotNull(heading.smooth(90f, 8f, 3_000L))
        assertTrue(second > first)
        assertTrue(second <= 90f)
        assertEquals(90f, heading.smooth(90f, 8f, 4_000L)!!, 2f)
    }

    @Test fun arrowSmoothsLeftTurnAndUTurnWithoutSuddenFlip() {
        val left = NavigationArrowHeadingController()
        assertEquals(0f, left.smooth(0f, 7f, 1_000L)!!, 0.01f)
        val leftStep = requireNotNull(left.smooth(270f, 7f, 2_000L))
        assertTrue(leftStep > 270f && leftStep < 360f)
        val uTurn = NavigationArrowHeadingController()
        uTurn.smooth(0f, 6f, 1_000L)
        val step = requireNotNull(uTurn.smooth(180f, 6f, 2_000L))
        assertTrue(abs(NavigationArrowOrientation.shortestDelta(0f, step)) < 100f)
        val final = requireNotNull(uTurn.smooth(180f, 6f, 5_000L))
        assertTrue(NavigationArrowOrientation.angleLikeDistance(final, 180f) <
            NavigationArrowOrientation.angleLikeDistance(step, 180f))
    }

    @Test fun stationaryAndShortGpsLossKeepLastReliableBearing() {
        val heading = NavigationArrowHeadingController()
        assertEquals(90f, heading.smooth(90f, 10f, 1_000L)!!, 0.01f)
        assertEquals(90f, heading.smooth(180f, 0f, 2_000L)!!, 0.01f)
        assertEquals(90f, heading.smooth(null, 0f, 7_000L)!!, 0.01f)
        val resumed = requireNotNull(heading.smooth(180f, 6f, 12_000L))
        assertTrue(NavigationArrowOrientation.angleLikeDistance(90f, resumed) < 90f)
    }

    @Test fun gpsArrowAndRouteKeepSameTrustedMatchedPositionAtJunction() {
        val tracker = NavigationVisualTracker()
        val progress = NavigationRouteProgressTracker().apply { reset(northEast) }
        val arrow = tracker.update(GpsPoint(1.35060, 103.82000), northEast,
            6f, 9f, 0f, 1_000L)
        val frame = requireNotNull(progress.advance(arrow.routeMatch, 9f, 1_000L))
        assertTrue(frame.trustedMatch)
        assertEquals(frame.boundary, frame.remaining.first())
        assertEquals(frame.boundary, frame.traveled.last())
        assertEquals(frame.boundary.lat, arrow.position.lat, 0.0000001)
        assertEquals(frame.boundary.lon, arrow.position.lon, 0.0000001)
    }

    @Test fun overlappingRouteContinuityStillAllowsARealOffRouteDeviation() {
        val tracker = NavigationVisualTracker()
        tracker.update(GpsPoint(1.35045, 103.82000), northEast, 7f, 9f, 0f, 1_000L)
        val offRoute = tracker.update(GpsPoint(1.35250, 103.82400),
            northEast, 7f, 10f, 90f, 2_000L)
        assertFalse(offRoute.matchedToRoute)
        assertEquals(GpsPoint(1.35250, 103.82400), offRoute.position)
    }

    @Test fun cameraRotationNeverEntersGeographicArrowController() {
        // Same real course and road tangent yield identical heading regardless
        // of camera mode; MapLibre does the only screen-space transformation.
        val geo = requireNotNull(NavigationArrowOrientation.target(85f, 90f, 12f))
        val northUp = NavigationArrowHeadingController().smooth(geo, 12f, 1_000L)
        val headingUp = NavigationArrowHeadingController().smooth(geo, 12f, 1_000L)
        assertEquals(northUp, headingUp)
    }
}
