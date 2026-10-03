package com.izzyan.sgdeliveryplanner

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

interface OneMapApi {
    @GET("api/common/elastic/search")
    suspend fun search(
        @Query("searchVal") postal: String,
        @Query("returnGeom") geom: String = "Y",
        @Query("getAddrDetails") details: String = "Y"
    ): JsonObject
}

/** Restores the original public OneMap Search request, with no credential configuration. */
fun createOneMapApi(baseUrl: String = "https://www.onemap.gov.sg/"): OneMapApi =
    Retrofit.Builder().baseUrl(baseUrl).client(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
    ).addConverterFactory(GsonConverterFactory.create()).build().create(OneMapApi::class.java)

class OneMapRateLimitError(cause: Throwable? = null) : PlannerError(
    "OneMap is still limiting postal-code searches after several retries. Wait a moment, then try again. Your postal codes have been kept.",
    cause
)

class OneMapLookup(
    private val dao: PlannerDao,
    private val api: OneMapApi,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val gson = Gson()
    // Covers cache reads, network requests and retry delays, including concurrent callers.
    private val lookupMutex = Mutex()

    suspend fun resolve(postal: String): Place = lookupMutex.withLock {
        val cached = dao.place(postal)
        if (cached != null && now() - cached.saved < CACHE_AGE_MILLIS) {
            val place = runCatching { gson.fromJson(cached.json, Place::class.java) }.getOrNull()
            if (place != null && place.postal == postal && insideSingapore(place)) return@withLock place
        }
        val response = searchWithRetry(postal)
        val matches = response.get("results")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw PlannerError("OneMap returned an unexpected search response. Please try again later.")
        val row = matches.firstOrNull {
            it.isJsonObject && it.asJsonObject.get("POSTAL")?.takeIf { value -> value.isJsonPrimitive }?.asString == postal
        }?.asJsonObject ?: throw PlannerError("Postal code $postal was not found. Check the code and try again.")
        fun field(name: String) = row.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val latitude = field("LATITUDE").toDoubleOrNull()
        val longitude = field("LONGITUDE").toDoubleOrNull()
        if (latitude == null || longitude == null) {
            throw PlannerError("Postal code $postal returned invalid coordinates. Check the code and try again.")
        }
        val place = Place(postal, latitude, longitude,
            field("BLK_NO"), field("ROAD_NAME"), field("ADDRESS"))
        if (!insideSingapore(place)) {
            throw PlannerError("Postal code $postal returned a location outside Singapore. Check the code and try again.")
        }
        dao.place(CachedPlace(postal, gson.toJson(place), now()))
        place
    }

    private suspend fun searchWithRetry(postal: String): JsonObject {
        var retries = 0
        while (true) {
            try {
                return api.search(postal)
            } catch (error: HttpException) {
                when (error.code()) {
                    429 -> {
                        if (retries >= MAX_RETRIES) throw OneMapRateLimitError(error)
                        val requestedWait = retryAfterMillis(error)
                        // Do not retry earlier than a long server cooldown just to fit our budget.
                        if (requestedWait != null && requestedWait > MAX_RETRY_WAIT_MILLIS) {
                            throw OneMapRateLimitError(error)
                        }
                        wait(maxOf(1_000L shl retries, requestedWait ?: 0L))
                        retries++
                    }
                    else -> throw error
                }
            }
        }
    }

    private fun retryAfterMillis(error: HttpException): Long? {
        val value = error.response()?.headers()?.get("Retry-After")?.trim() ?: return null
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            return if (seconds > MAX_RETRY_WAIT_MILLIS / 1_000) MAX_RETRY_WAIT_MILLIS + 1 else seconds * 1_000
        }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun insideSingapore(place: Place) = place.lat in 1.1..1.6 && place.lon in 103.5..104.1

    private companion object {
        const val CACHE_AGE_MILLIS = 180L * 86_400_000
        const val MAX_RETRIES = 3
        const val MAX_RETRY_WAIT_MILLIS = 30_000L
    }
}
