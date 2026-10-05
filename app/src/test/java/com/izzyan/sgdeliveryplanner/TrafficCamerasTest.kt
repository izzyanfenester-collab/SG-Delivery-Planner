package com.izzyan.sgdeliveryplanner

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TrafficCamerasTest {
    private fun camera(id: String, lat: Double, lon: Double) = TrafficCamera(id, GpsPoint(lat, lon), Instant.parse("2026-10-05T02:00:00Z"), "https://images.data.gov.sg/$id.jpg")
    // Public v1 traffic-images schema, synthetic coordinates and timestamps; no driver data.
    private fun fixture() = """{"items":[{"timestamp":"2026-10-05T10:00:00+08:00","cameras":[
      {"camera_id":"1001","timestamp":"2026-10-05T10:00:00+08:00","location":{"latitude":1.35,"longitude":103.8},"image":"https://images.data.gov.sg/1001.jpg"},
      {"camera_id":"bad","location":{"latitude":99,"longitude":103.8},"image":"https://images.data.gov.sg/bad.jpg"},
      {"camera_id":"insecure","location":{"latitude":1.35,"longitude":103.8},"image":"http://example.com/test.jpg"},
      {"camera_id":"1002","location":{"latitude":1.36,"longitude":103.81},"image":"https://images.data.gov.sg/1002.jpg"}
    ]}],"api_info":{"status":"healthy"}}"""
    @Test fun parsesLocationsImagesTimestampsAndSkipsMalformedIndividualEntries() {
        val values = parseTrafficCameras(fixture())
        assertEquals(listOf("1001", "1002"), values.map { it.id })
        assertEquals(GpsPoint(1.35, 103.8), values.first().point)
        assertEquals(Instant.parse("2026-10-05T02:00:00Z"), values.first().timestamp)
        assertEquals("https://images.data.gov.sg/1001.jpg", values.first().imageUrl)
        assertNull(values.last().timestamp)
        assertTrue(parseTrafficCameras("{\"items\":[]}").isEmpty())
    }
    @Test fun selectsNearestFiveAndIncludesCamerasCloseToRoute() {
        val nearby = (1..8).map { camera("$it", 1.35 + it * .001, 103.8) }
        val far = camera("far", 1.5, 104.1)
        assertEquals(listOf("1", "2", "3", "4", "5"), nearbyTrafficCameras(nearby + far, GpsPoint(1.35, 103.8), emptyList()).map { it.camera.id })
        val route = listOf(GpsPoint(1.35, 103.8), GpsPoint(1.45, 103.8))
        val onRoute = camera("route", 1.43, 103.801)
        val result = nearbyTrafficCameras(listOf(onRoute, far), GpsPoint(1.35, 103.8), route).single()
        assertTrue(result.relativeToRoute)
        assertTrue(result.meters < 500)
        assertEquals("route", nearbyTrafficCameras(listOf(onRoute), null, route).single().camera.id)
        assertTrue(nearbyTrafficCameras(nearby, null, emptyList()).isEmpty())
    }
    @Test fun apiUsesNoCredentialsOrDriverCoordinatesAndCachesForSixtySeconds() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture())); server.enqueue(MockResponse().setBody("{\"items\":[]}"))
            var time = 0L
            val client = TrafficCameraClient(server.url("/traffic-images").toString(), clock = { time })
            assertEquals(2, client.snapshot().cameras.size)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/traffic-images", request.path)
            assertEquals(0L, request.bodySize)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("api-key"))
            time = 59999; assertEquals(2, client.snapshot().cameras.size); assertEquals(1, server.requestCount)
            time = 60000; assertTrue(client.snapshot().cameras.isEmpty()); assertEquals(2, server.requestCount)
        }
    }
    @Test fun failureFallsBackWithoutThrowingAndRateLimitingIsNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429)); server.enqueue(MockResponse().setBody(fixture()))
            var time = 0L
            val client = TrafficCameraClient(server.url("/traffic-images").toString(), clock = { time })
            val failed = client.snapshot()
            assertTrue(failed.unavailable); assertTrue(failed.cameras.isEmpty())
            time = 29999; assertTrue(client.snapshot().unavailable); assertEquals(1, server.requestCount)
            time = 30000; assertFalse(client.snapshot().unavailable); assertEquals(2, server.requestCount)
        }
    }
    @Test fun transientFailureRetriesOnceAndReturnsCachedDataWhenOffline() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setBody(fixture()))
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(MockResponse().setResponseCode(503))
            var time = 0L
            val client = TrafficCameraClient(server.url("/traffic-images").toString(), clock = { time })
            val good = client.snapshot(); assertFalse(good.unavailable); assertEquals(2, server.requestCount)
            time = 60000
            val stale = client.snapshot(); assertTrue(stale.unavailable); assertEquals(good.cameras, stale.cameras)
            assertEquals(4, server.requestCount)
        }
    }
    @Test fun closingCameraPreviewCancelsRequestRatherThanReturningFailureData() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture()).setBodyDelay(5, TimeUnit.SECONDS))
            val client = TrafficCameraClient(server.url("/traffic-images").toString(), clock = { 0L })
            var returned = false
            val job = launch { client.snapshot(); returned = true }
            delay(100)
            job.cancel(); job.join()
            assertFalse(returned)
            assertTrue(job.isCancelled)
        }
    }
    @Test fun malformedApiResponseDoesNotBreakNavigation() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody("not JSON")) }
            val snapshot = TrafficCameraClient(server.url("/traffic-images").toString(), clock = { 0L }).snapshot()
            assertTrue(snapshot.unavailable); assertTrue(snapshot.cameras.isEmpty()); assertEquals(2, server.requestCount)
        }
    }
}
