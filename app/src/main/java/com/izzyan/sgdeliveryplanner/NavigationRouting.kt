package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.maplibre.navigation.core.models.DirectionsResponse
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.RouteOptions
import org.maplibre.spatialk.geojson.Position
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

internal const val NAVIGATION_STYLE = "https://tiles.openfreemap.org/styles/liberty"
internal const val VALHALLA_ENDPOINT = "https://valhalla1.openstreetmap.de/route"
internal const val ROUTE_UNAVAILABLE = "Navigation route temporarily unavailable. Please retry."

internal data class GpsPoint(val lat: Double, val lon: Double) {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)
}

/** Snapshot of just the selected delivery, never the optimized tour. */
internal fun navigationDestination(plan: Plan): Place? = plan.stops.getOrNull(plan.current)?.place

internal fun valhallaRequest(origin: GpsPoint, destination: GpsPoint): String {
    require(origin.valid() && destination.valid())
    return JsonObject().apply {
        addProperty("format", "osrm")
        addProperty("shape_format", "polyline6")
        addProperty("costing", "auto")
        addProperty("language", "en-US")
        addProperty("banner_instructions", true)
        addProperty("voice_instructions", true)
        add("directions_options", JsonObject().apply { addProperty("units", "kilometers") })
        add("locations", JsonArray().apply {
            listOf(origin, destination).forEach { point ->
                add(JsonObject().apply { addProperty("lat", point.lat); addProperty("lon", point.lon); addProperty("type", "break") })
            }
        })
    }.toString()
}

internal fun parseNavigationRoute(json: String, origin: GpsPoint, destination: GpsPoint): DirectionsRoute {
    val response = DirectionsResponse.fromJson(json)
    require(response.code == "Ok")
    val route = response.routes.firstOrNull() ?: error("No route")
    require(route.legs.size == 1 && route.legs.single().steps.isNotEmpty())
    require(route.distance.isFinite() && route.distance >= 0 && route.duration.isFinite() && route.duration >= 0)
    require(decodeNavigationGeometry(route.geometry).size >= 2)
    // SDK metadata only; requests always go through our Valhalla client, never Mapbox.
    return route.copy(routeOptions = RouteOptions(
        baseUrl = VALHALLA_ENDPOINT, user = "runner-route-planning-personal", profile = "auto",
        coordinates = listOf(Position(origin.lon, origin.lat), Position(destination.lon, destination.lat)),
        geometries = "polyline6", language = "en-US", steps = true, voiceInstructions = true, bannerInstructions = true
    ))
}

internal class ValhallaClient(
    private val endpoint: String = VALHALLA_ENDPOINT,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
) {
    suspend fun route(origin: GpsPoint, destination: GpsPoint): DirectionsRoute {
        val request = Request.Builder().url(endpoint)
            .header("X-Client-Id", "runner-route-planning-personal")
            .header("User-Agent", "RunnerRoutePlanning/1.2 personal navigation")
            .post(valhallaRequest(origin, destination).toRequestBody("application/json".toMediaType())).build()
        repeat(2) { attempt ->
            try {
                val response = client.newCall(request).awaitResponse()
                response.use {
                    if (it.code == 429 || it.code in 400..499) throw RoutingRejected()
                    if (!it.isSuccessful) throw IOException("Routing service unavailable")
                    return parseNavigationRoute(it.body?.string() ?: error("Empty response"), origin, destination)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: RoutingRejected) { throw IOException(ROUTE_UNAVAILABLE) }
            catch (e: IOException) { if (attempt == 1) throw IOException(ROUTE_UNAVAILABLE, e); delay(1_000) }
        }
        error(ROUTE_UNAVAILABLE)
    }
    private class RoutingRejected : IOException()
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

internal fun decodeNavigationGeometry(encoded: String): List<GpsPoint> {
    var index = 0; var lat = 0L; var lon = 0L
    fun component(): Long {
        var result = 0L; var shift = 0
        while (true) {
            require(index < encoded.length && shift <= 60) { "Invalid geometry" }
            val b = encoded[index++].code - 63
            require(b in 0..63)
            result = result or ((b and 31).toLong() shl shift); shift += 5
            if (b < 32) break
        }
        return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
    }
    return buildList {
        while (index < encoded.length) {
            lat += component(); lon += component()
            val point = GpsPoint(lat / 1e6, lon / 1e6)
            require(point.valid()); add(point)
        }
    }
}

internal fun navigationDistance(a: GpsPoint, b: GpsPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 6_371_000 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Segment distance, rather than vertex distance, avoids false reroutes on long road segments. */
internal fun distanceFromNavigationRoute(point: GpsPoint, shape: List<GpsPoint>): Double {
    if (shape.size < 2) return Double.POSITIVE_INFINITY
    val scale = cos(Math.toRadians(point.lat)); val meters = 111_195.0
    return shape.zipWithNext().minOf { (a, b) ->
        val ax = (a.lon - point.lon) * scale * meters; val ay = (a.lat - point.lat) * meters
        val bx = (b.lon - point.lon) * scale * meters; val by = (b.lat - point.lat) * meters
        val dx = bx - ax; val dy = by - ay
        val t = if (dx * dx + dy * dy == 0.0) 0.0 else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        hypot(ax + t * dx, ay + t * dy)
    }
}

internal class ReroutePolicy {
    private var offSince: Long? = null
    private var lastRequest: Long? = null
    private var blockedUntil: Long = 0
    fun requestAllowed(now: Long) = now >= blockedUntil && (lastRequest?.let { now - it >= 30_000 } ?: true)
    fun failed(now: Long) { blockedUntil = now + 60_000; offSince = null }
    fun requested(now: Long) { lastRequest = now; offSince = null }
    fun resetDrift() { offSince = null }
    fun shouldReroute(distance: Double, accuracy: Float, now: Long): Boolean {
        if (accuracy > 35 || !accuracy.isFinite() || distance <= max(60.0, accuracy * 2.0)) { offSince = null; return false }
        if (offSince == null) offSince = now
        return now - requireNotNull(offSince) >= 10_000 && requestAllowed(now)
    }
}

internal class ArrivalDetector {
    private var nearbyFixes = 0
    fun update(distance: Double, accuracy: Float, routeRemaining: Double?): Boolean {
        nearbyFixes = if (accuracy <= 25 && distance <= 35 && (routeRemaining == null || routeRemaining <= 80)) nearbyFixes + 1 else 0
        return nearbyFixes >= 3
    }
}

internal fun navigationEta(epochMillis: Long, durationSeconds: Double): Long =
    epochMillis + (durationSeconds.coerceAtLeast(0.0) * 1_000).toLong()

internal class VoiceCueGate {
    private val spoken = mutableSetOf<String>()
    fun reset() = spoken.clear()
    fun acceptCue(step: Int, index: Int, text: String): Boolean = text.isNotBlank() && spoken.add("$step:cue:$index:$text")
    fun accept(step: Int, instruction: String, distance: Double): Boolean {
        if (instruction.isBlank() || distance > 300) return false
        val bucket = if (distance <= 50) "turn" else "ahead"
        return spoken.add("$step:$bucket:$instruction")
    }
}
