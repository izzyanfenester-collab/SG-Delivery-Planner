package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class NavigationRoutingTest {
    private val origin = GpsPoint(1.35, 103.80)
    private val destination = GpsPoint(1.36, 103.81)
    private fun fixture() = requireNotNull(javaClass.getResource("/navigation/valhalla-singapore.json")).readText()

    @Test fun selectsOnlyCurrentStopWithoutReorderingOrChangingDeliveryData() {
        val plan = schedule((1..3).map { Place("00500$it", 1.35 + it / 1000.0, 103.8, "$it", "Area", "Road") },
            List(4) { Leg(1.0, 100.0, 10.0) }, LocalDateTime.of(2026, 10, 5, 10, 0), 8, "Normal Traffic", emptyList()).copy(current = 1)
        val before = plan.copy()
        assertSame(plan.stops[1].place, navigationDestination(plan))
        assertEquals(before, plan)
        assertNull(navigationDestination(plan.copy(current = 3)))
    }

    @Test fun parsesRealValhallaResponseIntoSdkRouteWithMetricGeometryAndGuidance() {
        val route = parseNavigationRoute(fixture(), origin, destination)
        assertEquals(1, route.legs.size)
        assertEquals(8, route.legs.single().steps.size)
        assertEquals(10180.688, route.distance, .001)
        assertEquals(1463.317, route.duration, .001)
        assertTrue(route.legs.single().steps.first().voiceInstructions!!.first().announcement!!.contains("Drive"))
        val shape = decodeNavigationGeometry(route.geometry)
        assertTrue(shape.size > 50)
        assertTrue(navigationDistance(shape.first(), origin) < 500)
        assertTrue(navigationDistance(shape.last(), destination) < 500)
        assertEquals("polyline6", route.routeOptions!!.geometries)
        assertNull(route.routeOptions!!.accessToken)
    }

    @Test fun sdkProgressUpdatesRemainingDistanceTimeAndDrivingEtaFromTheRecordedRoute() {
        val route = parseNavigationRoute(fixture(), origin, destination)
        val shape = decodeNavigationGeometry(route.geometry).map { org.maplibre.spatialk.geojson.Position(it.lon, it.lat) }
        fun progress(remaining: Double) = org.maplibre.navigation.core.routeprogress.RouteProgress(
            directionsRoute = route, legIndex = 0, distanceRemaining = remaining,
            currentStepPoints = shape, upcomingStepPoints = null, stepIndex = 0,
            legDistanceRemaining = remaining, stepDistanceRemaining = 100.0,
            intersections = null, currentIntersection = null, upcomingIntersection = null,
            currentLegAnnotation = null, intersectionDistancesAlongStep = null
        )
        val early = progress(route.distance * .75)
        val later = progress(route.distance * .5)
        assertTrue(later.distanceRemaining < early.distanceRemaining)
        assertTrue(later.durationRemaining < early.durationRemaining)
        assertEquals(route.duration * .5, later.durationRemaining, .001)
        assertTrue(navigationEta(1_000, later.durationRemaining) < navigationEta(1_000, early.durationRemaining))
        assertEquals(100.0, later.currentLegProgress.currentStepProgress.distanceRemaining, 0.0)
        assertTrue(later.currentLegProgress.currentStepProgress.nextStep!!.maneuver.instruction!!.contains("exit", ignoreCase = true))
    }

    @Test fun httpRequestSendsOnlyTwoLocationsAndIdentifyingHeader() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture()))
            val route = ValhallaClient(server.url("/route").toString()).route(origin, destination)
            assertTrue(route.distance > 0)
            val request = server.takeRequest()
            assertEquals("runner-route-planning-personal", request.getHeader("X-Client-Id"))
            assertEquals("POST", request.method)
            val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals("auto", json["costing"].asString)
            assertEquals("kilometers", json["directions_options"].asJsonObject["units"].asString)
            assertEquals(2, json["locations"].asJsonArray.size())
            assertEquals(origin.lat, json["locations"].asJsonArray[0].asJsonObject["lat"].asDouble, 0.0)
            assertEquals(destination.lon, json["locations"].asJsonArray[1].asJsonObject["lon"].asDouble, 0.0)
        }
    }

    @Test fun transientServerFailureRetriesOnceAndRateLimitsAreNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody(fixture()))
            assertTrue(ValhallaClient(server.url("/route").toString()).route(origin, destination).distance > 0)
            assertEquals(2, server.requestCount)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429))
            try { ValhallaClient(server.url("/route").toString()).route(origin, destination); fail("Expected routing failure") }
            catch (e: java.io.IOException) { assertEquals(ROUTE_UNAVAILABLE, e.message) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun malformedAndUnusableRoutesAreRejected() {
        listOf("{}", "{\"code\":\"NoRoute\",\"routes\":[]}", JsonParser.parseString(fixture()).asJsonObject.apply { addProperty("code", "NoRoute") }.toString()).forEach {
            try { parseNavigationRoute(it, origin, destination); fail("Invalid route accepted") } catch (_: Exception) { }
        }
        try { decodeNavigationGeometry("~"); fail("Truncated geometry accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun reroutesOnlyAfterSustainedDeviationAndCooldownNotGpsDrift() {
        val policy = ReroutePolicy()
        policy.requested(0)
        assertFalse(policy.shouldReroute(90.0, 10f, 30_000))
        assertFalse(policy.shouldReroute(90.0, 10f, 39_999))
        assertTrue(policy.shouldReroute(90.0, 10f, 40_000))
        policy.requested(40_000)
        assertFalse(policy.shouldReroute(150.0, 10f, 50_000))
        assertFalse(policy.shouldReroute(150.0, 10f, 69_999))
        assertTrue(policy.shouldReroute(150.0, 10f, 70_000))
        assertFalse(policy.shouldReroute(100.0, 70f, 80_000))
        assertFalse(policy.shouldReroute(20.0, 10f, 90_000))
        assertFalse(policy.shouldReroute(100.0, 10f, 90_001))
    }

    @Test fun failedRequestsBackOffForOneMinuteAndVoiceAnnouncementsDeduplicate() {
        val policy = ReroutePolicy()
        policy.requested(0); policy.failed(10_000)
        assertFalse(policy.requestAllowed(69_999))
        assertTrue(policy.requestAllowed(70_000))
        val gate = VoiceCueGate()
        assertTrue(gate.acceptCue(0, 0, "Continue straight"))
        assertFalse(gate.acceptCue(0, 0, "Continue straight"))
        assertTrue(gate.acceptCue(0, 1, "Turn right"))
        gate.reset()
        assertTrue(gate.acceptCue(0, 0, "Continue straight"))
    }

    @Test fun longSegmentDoesNotLookOffRouteAndArrivalRequiresGoodSustainedFixes() {
        val shape = listOf(GpsPoint(1.35, 103.8), GpsPoint(1.35, 103.81))
        assertEquals(0.0, distanceFromNavigationRoute(GpsPoint(1.35, 103.805), shape), .1)
        assertTrue(distanceFromNavigationRoute(GpsPoint(1.352, 103.805), shape) > 200)
        val detector = ArrivalDetector()
        assertFalse(detector.update(20.0, 10f, 40.0))
        assertFalse(detector.update(20.0, 10f, 40.0))
        assertFalse(detector.update(20.0, 60f, 40.0))
        assertFalse(detector.update(20.0, 10f, 400.0))
        repeat(2) { assertFalse(detector.update(20.0, 10f, 40.0)) }
        assertTrue(detector.update(20.0, 10f, 40.0))
    }

    @Test fun etaAndDistanceAreMetricAndVoiceCueDoesNotRepeat() {
        assertEquals(181_000, navigationEta(1_000, 180.0))
        assertEquals("250 m", metricDistance(250.0))
        assertEquals("1.5 km", metricDistance(1500.0))
        val gate = VoiceCueGate()
        assertTrue(gate.accept(1, "Turn left", 200.0))
        assertFalse(gate.accept(1, "Turn left", 190.0))
        assertTrue(gate.accept(1, "Turn left", 40.0))
        assertFalse(gate.accept(1, "Turn left", 30.0))
        assertTrue(gate.accept(2, "Turn left", 200.0))
    }
}
