package com.izzyan.sgdeliveryplanner

import android.os.SystemClock
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** An optional on-demand OpenStreetMap search. No delivery order or route is sent. */
internal const val FUEL_SEARCH_ENDPOINT = "https://overpass-api.de/api/interpreter"
private const val FUEL_RADIUS_METERS = 5_000.0
private const val FUEL_CACHE_MILLIS = 10 * 60_000L

internal data class FuelStation(val id: String, val name: String, val point: GpsPoint,
    val address: String?, val meters: Double)
internal data class FuelSnapshot(val stations: List<FuelStation>, val unavailable: Boolean = false)

internal fun fuelStationQuery(location: GpsPoint): String {
    require(location.valid())
    val lat = String.format(Locale.US, "%.5f", location.lat)
    val lon = String.format(Locale.US, "%.5f", location.lon)
    return "[out:json][timeout:12];nwr[\"amenity\"=\"fuel\"](around:5000,$lat,$lon);out center;"
}

internal fun parseFuelStations(json: String, origin: GpsPoint): List<FuelStation> {
    require(origin.valid())
    val root = JsonParser.parseString(json).asJsonObject
    val elements = root.getAsJsonArray("elements") ?: error("Missing Overpass elements")
    return elements.mapNotNull { raw ->
        runCatching {
            val item = raw.asJsonObject
            val tags = item.getAsJsonObject("tags") ?: return@runCatching null
            if (tags.get("amenity")?.asString != "fuel") return@runCatching null
            val center = item.getAsJsonObject("center")
            val lat = item.get("lat")?.asDouble ?: center?.get("lat")?.asDouble ?: return@runCatching null
            val lon = item.get("lon")?.asDouble ?: center?.get("lon")?.asDouble ?: return@runCatching null
            val point = GpsPoint(lat, lon)
            if (!point.valid()) return@runCatching null
            val meters = navigationDistance(origin, point)
            if (!meters.isFinite() || meters > FUEL_RADIUS_METERS + 500) return@runCatching null
            fun tag(key: String): String? = tags.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                ?.trim()?.take(90)?.takeIf { it.isNotBlank() }
            val name = tag("name") ?: tag("brand") ?: tag("operator") ?: "Petrol station"
            val address = listOfNotNull(tag("addr:housenumber"), tag("addr:street"))
                .joinToString(" ").takeIf { it.isNotBlank() }
            val id = "${item.get("type")?.asString}/${item.get("id")?.asString}"
            FuelStation(id, name, point, address, meters)
        }.getOrNull()
    }.distinctBy { it.id }.sortedBy { it.meters }.take(8)
}

internal class FuelStationClient(
    private val endpoint: String = FUEL_SEARCH_ENDPOINT,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private val mutex = Mutex()
    private var cachedOrigin: GpsPoint? = null
    private var cached: FuelSnapshot? = null
    private var expiresAt = 0L

    suspend fun nearby(origin: GpsPoint): FuelSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sameArea = cachedOrigin?.let { navigationDistance(it, origin) < 1_000 } ?: false
            if (sameArea && clock() < expiresAt) return@withLock requireNotNull(cached)
            val result = try {
                val request = Request.Builder().url(endpoint)
                    .header("User-Agent", "SGDeliveryGPSPro/1.4.9 (on-demand fuel lookup)")
                    .post(FormBody.Builder().add("data", fuelStationQuery(origin)).build()).build()
                val response = client.newCall(request).awaitCameraResponse(768 * 1024)
                if (response.code !in 200..299) throw IOException("Fuel search unavailable")
                FuelSnapshot(parseFuelStations(response.bytes.toString(Charsets.UTF_8), origin))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { FuelSnapshot(if (sameArea) cached?.stations.orEmpty() else emptyList(), unavailable = true) }
            cachedOrigin = origin
            cached = result
            expiresAt = clock() + if (result.unavailable) 60_000L else FUEL_CACHE_MILLIS
            result
        }
    }
}
