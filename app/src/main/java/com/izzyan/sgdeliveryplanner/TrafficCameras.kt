package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

internal const val TRAFFIC_CAMERA_ENDPOINT = "https://api.data.gov.sg/v1/transport/traffic-images"
internal const val CAMERA_CACHE_MILLIS = 60_000L
internal data class TrafficCamera(val id: String, val point: GpsPoint, val timestamp: Instant?, val imageUrl: String)
internal data class NearbyTrafficCamera(val camera: TrafficCamera, val meters: Double, val relativeToRoute: Boolean)
internal data class CameraSnapshot(val cameras: List<TrafficCamera>, val unavailable: Boolean = false)

internal fun parseTrafficCameras(json: String): List<TrafficCamera> {
    val root = JsonParser.parseString(json).asJsonObject
    val items = root.getAsJsonArray("items") ?: error("Missing traffic camera items")
    val cameras = items.lastOrNull()?.asJsonObject?.getAsJsonArray("cameras") ?: return emptyList()
    return cameras.mapNotNull { item ->
        // A malformed camera must not discard the other available cameras.
        runCatching {
            val c = item.asJsonObject
            val p = c.getAsJsonObject("location")
            val point = GpsPoint(p.get("latitude").asDouble, p.get("longitude").asDouble)
            require(point.valid() && point.lat in 1.1..1.6 && point.lon in 103.5..104.2)
            val url = c.get("image").asString.toHttpUrlOrNull() ?: error("Invalid image URL")
            require(url.isHttps)
            val id = c.get("camera_id").asString; require(id.isNotBlank())
            val timestamp = runCatching { OffsetDateTime.parse(c.get("timestamp").asString).toInstant() }.getOrNull()
            TrafficCamera(id, point, timestamp, url.toString())
        }.getOrNull()
    }.distinctBy { it.id }
}

/** Local selection only; neither GPS nor route information is sent to the camera API. */
internal fun nearbyTrafficCameras(cameras: List<TrafficCamera>, location: GpsPoint?, shape: List<GpsPoint>): List<NearbyTrafficCamera> {
    val current = location?.takeIf { it.valid() }
    if (current == null && shape.size < 2) return emptyList()
    return cameras.mapNotNull { camera ->
        val fromDriver = current?.let { navigationDistance(it, camera.point) } ?: Double.POSITIVE_INFINITY
        val fromRoute = distanceFromNavigationRoute(camera.point, shape)
        // Relevant within 3 km of driver or 500 m of the active route.
        if (fromDriver > 3_000 && fromRoute > 500) null
        else NearbyTrafficCamera(camera, if (fromDriver <= 3_000) fromDriver else fromRoute, fromDriver > 3_000)
    }.sortedWith(compareBy<NearbyTrafficCamera> { it.relativeToRoute }.thenBy { it.meters }).take(5)
}

internal fun cameraHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()

internal class TrafficCameraClient(
    private val endpoint: String = TRAFFIC_CAMERA_ENDPOINT,
    private val client: OkHttpClient = cameraHttpClient(),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private val mutex = Mutex()
    private var cached: CameraSnapshot? = null
    private var expires = 0L
    suspend fun snapshot(): CameraSnapshot = withContext(Dispatchers.IO) { loadSnapshot() }
    private suspend fun loadSnapshot(): CameraSnapshot = mutex.withLock {
        cached?.takeIf { clock() < expires }?.let { return@withLock it }
        repeat(2) { attempt ->
            try {
                val response = client.newCall(Request.Builder().url(endpoint).get().build()).awaitCameraResponse(1024 * 1024)
                // Do not retry 4xx, including rate limiting; retain any cached cameras.
                if (response.code in 400..499) return@withLock store(CameraSnapshot(cached?.cameras.orEmpty(), true))
                if (response.code !in 200..299) throw IOException("Camera service unavailable")
                return@withLock store(CameraSnapshot(parseTrafficCameras(response.bytes.toString(Charsets.UTF_8))))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (attempt == 0) delay(500) }
        }
        store(CameraSnapshot(cached?.cameras.orEmpty(), unavailable = true))
    }

    private fun store(value: CameraSnapshot): CameraSnapshot {
        cached = value; expires = clock() + if (value.unavailable) 30_000 else CAMERA_CACHE_MILLIS
        return value
    }
}

internal data class CameraHttpResponse(val code: Int, val bytes: ByteArray)

/** Keep cancellation attached until the entire bounded body has arrived, not just the headers. */
internal suspend fun Call.awaitCameraResponse(maxBytes: Int): CameraHttpResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    val buffer = okio.Buffer()
                    if (it.isSuccessful) {
                        val source = it.body?.source() ?: throw IOException("Empty camera response")
                        var remaining = maxBytes + 1L
                        while (remaining > 0) {
                            val read = source.read(buffer, minOf(8192L, remaining))
                            if (read == -1L) break
                            remaining -= read
                        }
                        if (buffer.size > maxBytes) throw IOException("Camera response too large")
                    }
                    CameraHttpResponse(it.code, buffer.readByteArray())
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
        }
    })
}
