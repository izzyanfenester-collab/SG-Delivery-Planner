package com.izzyan.sgdeliveryplanner

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

interface OneMapApi {
    @GET("api/common/elastic/search")
    suspend fun search(
        @Query("searchVal") postal: String,
        @Header("Authorization") authorization: String,
        @Query("returnGeom") geom: String = "Y",
        @Query("getAddrDetails") details: String = "Y"
    ): JsonObject
}

/** OneMap credentials never enter the configurable routing client or follow redirects. */
fun createOneMapApi(baseUrl: String = "https://www.onemap.gov.sg/"): OneMapApi =
    Retrofit.Builder().baseUrl(baseUrl).client(
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    ).addConverterFactory(GsonConverterFactory.create()).build().create(OneMapApi::class.java)

class OneMapAuthenticationError(
    message: String = "OneMap did not accept your API token. It may be expired, invalid or denied. Update the OneMap API token in Settings, then try again.",
    cause: Throwable? = null
) : PlannerError(message, cause)

class OneMapRateLimitError(cause: Throwable? = null) : PlannerError(
    "OneMap is still limiting postal-code searches after several retries. Wait a moment, then try again. Your postal codes have been kept.",
    cause
)

class OneMapLookup(
    private val dao: PlannerDao,
    private val api: OneMapApi,
    private val tokenProvider: () -> String?,
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
        val token = try {
            withContext(Dispatchers.IO) { tokenProvider()?.trim()?.takeIf { it.isNotEmpty() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw OneMapAuthenticationError("Could not read your saved OneMap API token. Update the OneMap API token in Settings, then try again.", e)
        } ?: throw OneMapAuthenticationError("Add your OneMap API token in Settings to look up new postal codes, then try again.")

        val response = searchWithRetry(postal, token)
        val matches = response.getAsJsonArray("results")
            ?: throw PlannerError("OneMap returned an unexpected search response. Please try again later.")
        val row = matches.firstOrNull {
            it.asJsonObject.get("POSTAL")?.asString == postal
        }?.asJsonObject ?: throw PlannerError("Postal code $postal was not found. Check the code and try again.")
        fun field(name: String) = row.get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        val place = Place(postal, field("LATITUDE").toDouble(), field("LONGITUDE").toDouble(),
            field("BLK_NO"), field("ROAD_NAME"), field("ADDRESS"))
        if (!insideSingapore(place)) {
            throw PlannerError("Postal code $postal returned a location outside Singapore. Check the code and try again.")
        }
        dao.place(CachedPlace(postal, gson.toJson(place), now()))
        place
    }

    private suspend fun searchWithRetry(postal: String, token: String): JsonObject {
        var retries = 0
        while (true) {
            try {
                return api.search(postal, "Bearer $token")
            } catch (error: HttpException) {
                when (error.code()) {
                    401, 403 -> throw OneMapAuthenticationError(cause = error)
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
