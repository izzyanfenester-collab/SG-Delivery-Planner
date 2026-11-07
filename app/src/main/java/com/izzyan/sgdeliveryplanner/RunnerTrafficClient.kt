package com.izzyan.sgdeliveryplanner

import android.content.Context
import android.location.Location
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HTTPS runner-to-runner traffic pilot.
 *
 * The server derives direction-specific road IDs from actual licensed OSM geometry.
 * No order/customer fields are transmitted. If the backend is not configured,
 * unavailable, or has insufficient observations, shared coverage is UNKNOWN.
 */
internal data class SharedTrafficSegment(
    val id: String,
    val start: GpsPoint,
    val end: GpsPoint,
    val level: LocalTrafficLevel,
    val speedKph: Double?,
    val freeFlowKph: Double,
    val observedAtMs: Long
)

internal data class SharedTrafficSnapshot(
    val segments: List<SharedTrafficSegment>,
    val status: String
) {
    companion object {
        val DISCONNECTED = SharedTrafficSnapshot(emptyList(), "Runner traffic unavailable")
    }
}

internal data class RunnerTrafficObservation(
    val latitude: Double,
    val longitude: Double,
    val speedKph: Double,
    val accuracyM: Double,
    val bearingDeg: Double,
    val timestampMs: Long,
    val driving: Boolean,
    val deliveryActivity: Boolean
) {
    companion object {
        fun fromLocation(
            fix: Location,
            distanceToDestinationM: Double,
            driving: Boolean
        ): RunnerTrafficObservation? {
            if (!driving || !fix.hasAccuracy() || !fix.hasSpeed() || !fix.hasBearing() ||
                !fix.accuracy.isFinite() || fix.accuracy > 18f ||
                !fix.speed.isFinite() || fix.speed !in 0.9f..45f ||
                (fix.speed < 2f && fix.accuracy > 8f) ||
                !fix.bearing.isFinite() ||
                !distanceToDestinationM.isFinite() || distanceToDestinationM < 120.0 ||
                (fix.hasSpeedAccuracy() && fix.speedAccuracyMetersPerSecond > 3f)
            ) return null
            val point = GpsPoint(fix.latitude, fix.longitude)
            if (!point.valid()) return null
            return RunnerTrafficObservation(
                point.lat, point.lon, fix.speed * 3.6, fix.accuracy.toDouble(),
                ((fix.bearing.toDouble() % 360.0) + 360.0) % 360.0,
                fix.time, true, false
            )
        }
    }
}

internal class RunnerTrafficClient(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val baseUrl = context.getString(R.string.runner_traffic_backend_url)
        .trim().trimEnd('/')
    private val pilotKey = context.getString(R.string.runner_traffic_pilot_key).trim()
    val configured: Boolean = baseUrl.startsWith("https://") &&
        pilotKey.isNotBlank()
    private val sessionId = UUID.randomUUID().toString().replace("-", "")
    private val pending = ArrayDeque<RunnerTrafficObservation>()
    private var lastAcceptedTime = Long.MIN_VALUE

    fun observe(fix: Location, destinationMeters: Double, driving: Boolean) {
        if (!configured) return
        val candidate = RunnerTrafficObservation.fromLocation(fix, destinationMeters, driving)
            ?: return
        val monotonic = fix.elapsedRealtimeNanos / 1_000_000L
        if (lastAcceptedTime != Long.MIN_VALUE && monotonic - lastAcceptedTime < 5_000) return
        lastAcceptedTime = monotonic
        if (pending.size >= 50) pending.removeFirst()
        pending.addLast(candidate)
    }

    fun clear() {
        pending.clear()
        lastAcceptedTime = Long.MIN_VALUE
        // Revocation/closing navigation must also cancel any outstanding pilot requests.
        http.dispatcher.cancelAll()
    }

    /** Invoked from the navigation coroutine every ~45 seconds, never from the GPS callback. */
    suspend fun exchange(current: GpsPoint?): SharedTrafficSnapshot {
        if (!configured || current == null || !current.valid()) return SharedTrafficSnapshot.DISCONNECTED
        val observations = pending.toList()
        pending.clear()
        return withContext(Dispatchers.IO) {
            try {
                if (observations.isNotEmpty()) upload(observations)
                readMap(current)
            } catch (_: Exception) {
                // No stale or invented live traffic if connectivity fails.
                SharedTrafficSnapshot.DISCONNECTED
            }
        }
    }

    private fun secured(url: String): Request.Builder =
        Request.Builder().url(url).header("X-Pilot-Key", pilotKey)
            .header("Accept", "application/json")

    private fun upload(records: List<RunnerTrafficObservation>) {
        val json = JsonObject().apply {
            addProperty("sessionId", sessionId)
            add("observations", JsonArray().apply {
                records.forEach { record ->
                    add(JsonObject().apply {
                        addProperty("latitude", record.latitude)
                        addProperty("longitude", record.longitude)
                        addProperty("speedKph", record.speedKph)
                        addProperty("accuracyM", record.accuracyM)
                        addProperty("bearingDeg", record.bearingDeg)
                        addProperty("timestampMs", record.timestampMs)
                        addProperty("driving", record.driving)
                        addProperty("deliveryActivity", record.deliveryActivity)
                    })
                }
            })
        }
        val request = secured("$baseUrl/v1/traffic/observations")
            .post(json.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use {
            if (!it.isSuccessful) error("Traffic upload rejected: HTTP ${it.code}")
        }
    }

    private fun readMap(position: GpsPoint): SharedTrafficSnapshot {
        val south = (position.lat - 0.025).coerceIn(1.10, 1.55)
        val north = (position.lat + 0.025).coerceIn(1.10, 1.55)
        val west = (position.lon - 0.025).coerceIn(103.5, 104.2)
        val east = (position.lon + 0.025).coerceIn(103.5, 104.2)
        if (north <= south || east <= west) return SharedTrafficSnapshot.DISCONNECTED
        val url = "$baseUrl/v1/traffic/segments?west=$west&south=$south&east=$east&north=$north"
        val request = secured(url).get().build()
        val content = http.newCall(request).execute().use {
            if (!it.isSuccessful) error("Traffic read rejected: HTTP ${it.code}")
            it.body?.string().orEmpty()
        }
        val data = JsonParser.parseString(content).asJsonObject
        val now = System.currentTimeMillis()
        val segments = buildList {
            val items = data.getAsJsonArray("segments") ?: return@buildList
            for (item in items.take(2000)) {
                runCatching {
                    val entry = item.asJsonObject
                    val points = entry.getAsJsonArray("coordinates")
                    if (points.size() < 2) return@runCatching
                    fun at(index: Int): GpsPoint {
                        val parts = points[index].asJsonArray
                        return GpsPoint(parts[0].asDouble, parts[1].asDouble)
                    }
                    val first = at(0)
                    val last = at(points.size() - 1)
                    val updated = entry.get("updatedAtMs").asLong
                    if (!first.valid() || !last.valid() ||
                        now - updated !in 0L..900_000L) return@runCatching
                    val level = runCatching {
                        LocalTrafficLevel.valueOf(entry.get("level").asString)
                    }.getOrDefault(LocalTrafficLevel.UNKNOWN)
                    val speed = entry.get("speedKph")
                        ?.takeIf { !it.isJsonNull }?.asDouble
                    val baseline = entry.get("freeFlowKph").asDouble
                    if (!baseline.isFinite() || baseline <= 0) return@runCatching
                    add(SharedTrafficSegment(entry.get("id").asString, first, last,
                        level, speed?.takeIf { it.isFinite() && it > 0 },
                        baseline, updated))
                }
            }
        }
        return SharedTrafficSnapshot(segments, "Runner traffic: ${segments.count { it.level != LocalTrafficLevel.UNKNOWN }} verified road segments")
    }
}
