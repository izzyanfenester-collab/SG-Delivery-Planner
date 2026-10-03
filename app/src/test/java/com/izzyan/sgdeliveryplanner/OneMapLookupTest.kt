package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** Exercise rate limits and credential boundaries without calling live mapping services. */
class OneMapLookupTest {
    @Test
    fun missingTokenStopsBeforeSendingAnySearchRequest() = runBlocking {
        val api = FakeOneMapApi { postal, _ -> searchResult(postal) }
        val lookup = OneMapLookup(MemoryPlannerDao(), api, tokenProvider = { null })

        val failure = expectFailure<OneMapAuthenticationError> { lookup.resolve("018956") }

        assertEquals(0, api.calls)
        assertTrue(failure.message.orEmpty().contains("OneMap"))
        assertTrue(failure.message.orEmpty().contains("Settings"))
    }

    @Test
    fun searchReceivesBearerAuthorizationAndKeepsLeadingZeroes() = runBlocking {
        val api = FakeOneMapApi { postal, authorization ->
            assertEquals("018956", postal)
            assertEquals("Bearer test-token", authorization)
            searchResult(postal)
        }
        val lookup = OneMapLookup(MemoryPlannerDao(), api, tokenProvider = { "test-token" })

        assertEquals("018956", lookup.resolve("018956").postal)
        assertEquals(1, api.calls)
    }

    @Test
    fun deniedAndExpiredTokensGiveSettingsGuidanceWithoutExposingServiceTextOrToken() = runBlocking {
        for (status in listOf(401, 403)) {
            val api = FakeOneMapApi { _, _ -> throw httpFailure(status, "Token rahsia-token telah tamat") }
            val waits = mutableListOf<Long>()
            val lookup = OneMapLookup(
                MemoryPlannerDao(), api, tokenProvider = { "rahsia-token" }, wait = { waits += it }
            )

            val failure = expectFailure<OneMapAuthenticationError> { lookup.resolve("018956") }
            val message = englishError(failure, "Unable to search.")

            assertEquals(1, api.calls)
            assertTrue(waits.isEmpty())
            assertTrue(message.contains("OneMap"))
            assertTrue(message.contains("Settings"))
            assertFalse(message.contains("rahsia-token"))
            assertFalse(message.contains("telah tamat"))
        }
    }

    @Test
    fun rateLimitedSearchRetriesWithExponentialBackoffThenCachesItsSuccess() = runBlocking {
        val dao = MemoryPlannerDao()
        var attempts = 0
        val api = FakeOneMapApi { postal, _ ->
            attempts++
            if (attempts <= 3) throw httpFailure(429)
            searchResult(postal)
        }
        val waits = mutableListOf<Long>()
        val lookup = OneMapLookup(dao, api, tokenProvider = { "test-token" }, wait = { waits += it })

        val place = lookup.resolve("018956")

        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
        assertEquals(4, api.calls)
        assertEquals(place, Gson().fromJson(dao.place("018956")!!.json, Place::class.java))
        assertEquals(place, lookup.resolve("018956"))
        assertEquals(4, api.calls)
    }

    @Test
    fun persistentRateLimitsStopAfterThreeRetriesAndNeverCacheAFailure() = runBlocking {
        val dao = MemoryPlannerDao()
        val api = FakeOneMapApi { _, _ -> throw httpFailure(429, "Perkhidmatan sibuk") }
        val waits = mutableListOf<Long>()
        val lookup = OneMapLookup(dao, api, tokenProvider = { "test-token" }, wait = { waits += it })

        val failure = expectFailure<OneMapRateLimitError> { lookup.resolve("018956") }

        assertEquals(4, api.calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), waits)
        assertNull(dao.place("018956"))
        assertFalse(englishError(failure, "Unable to search.").contains("Perkhidmatan sibuk"))
    }

    @Test
    fun cancellationDuringBackoffStopsRequestsAndLeavesNoCacheEntry() = runBlocking {
        val dao = MemoryPlannerDao()
        val api = FakeOneMapApi { _, _ -> throw httpFailure(429) }
        val lookup = OneMapLookup(
            dao, api, tokenProvider = { "test-token" }, wait = { throw CancellationException("Cancelled") }
        )

        expectFailure<CancellationException> { lookup.resolve("018956") }

        assertEquals(1, api.calls)
        assertNull(dao.place("018956"))
    }

    @Test
    fun differentPostalCodesNeverProduceConcurrentSearchRequests() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val api = FakeOneMapApi { postal, _ ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { previous -> maxOf(previous, count) }
            try {
                delay(20)
                searchResult(postal)
            } finally {
                active.decrementAndGet()
            }
        }
        val lookup = OneMapLookup(MemoryPlannerDao(), api, tokenProvider = { "test-token" })
        val codes = listOf("018956", "238858", "560123", "738099")

        val places = codes.map { code -> async { lookup.resolve(code) } }.awaitAll()

        assertEquals(codes, places.map { it.postal })
        assertEquals(4, api.calls)
        assertEquals(1, maximum.get())
    }

    @Test
    fun concurrentRequestsForTheSamePostalCodeShareTheSavedResult() = runBlocking {
        val api = FakeOneMapApi { postal, _ -> delay(20); searchResult(postal) }
        val lookup = OneMapLookup(MemoryPlannerDao(), api, tokenProvider = { "test-token" })

        val places = List(4) { async { lookup.resolve("018956") } }.awaitAll()

        assertEquals(1, api.calls)
        assertTrue(places.all { it == places.first() })
    }

    @Test
    fun validCacheIsUsedEvenWhenTheTokenHasBeenRemoved() = runBlocking {
        val dao = MemoryPlannerDao()
        val cached = samplePlace("018956")
        val now = 20_000L
        dao.place(CachedPlace(cached.postal, Gson().toJson(cached), now - 1_000))
        val api = FakeOneMapApi { _, _ -> throw AssertionError("A cache hit must not call OneMap") }
        val lookup = OneMapLookup(dao, api, tokenProvider = { null }, now = { now })

        assertEquals(cached, lookup.resolve("018956"))
        assertEquals(0, api.calls)
    }

    @Test
    fun expiredCacheIsReplacedByANewSearchResult() = runBlocking {
        val dao = MemoryPlannerDao()
        val now = 200L * 86_400_000L
        val old = samplePlace("018956").copy(address = "Old address")
        dao.place(CachedPlace(old.postal, Gson().toJson(old), now - 181L * 86_400_000L))
        val api = FakeOneMapApi { postal, _ -> searchResult(postal) }
        val lookup = OneMapLookup(dao, api, tokenProvider = { "test-token" }, now = { now })

        val updated = lookup.resolve("018956")

        assertEquals(1, api.calls)
        assertEquals("Test address", updated.address)
        assertEquals(now, dao.place("018956")!!.saved)
    }

    @Test
    fun searchesRequireAnExactPostalMatchAndSingaporeCoordinates() = runBlocking {
        for (result in listOf(searchResult("238858"), searchResult("018956", latitude = 3.1))) {
            val dao = MemoryPlannerDao()
            val api = FakeOneMapApi { _, _ -> result }
            val lookup = OneMapLookup(dao, api, tokenProvider = { "test-token" })

            expectFailure<PlannerError> { lookup.resolve("018956") }

            assertEquals(1, api.calls)
            assertNull(dao.place("018956"))
        }
    }

    @Test
    fun retryAfterCanExtendTheBackoffWithinTheBound() = runBlocking {
        var attempts = 0
        val api = FakeOneMapApi { postal, _ ->
            attempts++
            if (attempts == 1) throw httpFailure(429, retryAfter = "7")
            searchResult(postal)
        }
        val waits = mutableListOf<Long>()
        val lookup = OneMapLookup(
            MemoryPlannerDao(), api, tokenProvider = { "test-token" }, wait = { waits += it }
        )

        assertEquals("018956", lookup.resolve("018956").postal)
        assertEquals(listOf(7_000L), waits)
        assertEquals(2, api.calls)
    }

    @Test
    fun longRetryAfterStopsInsteadOfIgnoringTheServiceLimit() = runBlocking {
        val api = FakeOneMapApi { _, _ -> throw httpFailure(429, retryAfter = "31") }
        val waits = mutableListOf<Long>()
        val lookup = OneMapLookup(
            MemoryPlannerDao(), api, tokenProvider = { "test-token" }, wait = { waits += it }
        )

        expectFailure<OneMapRateLimitError> { lookup.resolve("018956") }

        assertEquals(1, api.calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun retrofitSearchUsesTheOneMapPathAndAuthorizationHeader() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(searchResult("018956").toString()))

            createOneMapApi(server.url("/").toString()).search("018956", "Bearer private-token")

            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/api/common/elastic/search", request.requestUrl!!.encodedPath)
            assertEquals("018956", request.requestUrl!!.queryParameter("searchVal"))
            assertEquals("Y", request.requestUrl!!.queryParameter("returnGeom"))
            assertEquals("Y", request.requestUrl!!.queryParameter("getAddrDetails"))
            assertEquals("Bearer private-token", request.getHeader("Authorization"))
            assertFalse(request.path.orEmpty().contains("private-token"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun oneMapClientDoesNotForwardTokensToRedirectDestinations() = runBlocking {
        val oneMap = MockWebServer()
        val otherHost = MockWebServer()
        oneMap.start()
        otherHost.start()
        try {
            oneMap.enqueue(MockResponse().setResponseCode(302).setHeader("Location", otherHost.url("/search")))
            otherHost.enqueue(MockResponse().setBody(searchResult("018956").toString()))

            val failure = expectFailure<HttpException> {
                createOneMapApi(oneMap.url("/").toString()).search("018956", "Bearer private-token")
            }

            assertEquals(302, failure.code())
            assertEquals(1, oneMap.requestCount)
            assertEquals(0, otherHost.requestCount)
        } finally {
            oneMap.shutdown()
            otherHost.shutdown()
        }
    }

    @Test
    fun configurableRoutingRequestsDoNotReceiveTheOneMapCredential() = runBlocking {
        val oneMap = MockWebServer()
        val routing = MockWebServer()
        oneMap.start()
        routing.start()
        try {
            oneMap.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(searchResult("018956").toString()))
            routing.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"code\":\"Ok\"}"))

            createOneMapApi(oneMap.url("/").toString()).search("018956", "Bearer private-token")
            createRoutingApi(routing.url("/").toString()).get(routing.url("/table/v1/driving/103.8,1.3").toString())

            val search = requireNotNull(oneMap.takeRequest(5, TimeUnit.SECONDS))
            val road = requireNotNull(routing.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("Bearer private-token", search.getHeader("Authorization"))
            assertNull(road.getHeader("Authorization"))
            assertFalse(road.path.orEmpty().contains("private-token"))
        } finally {
            oneMap.shutdown()
            routing.shutdown()
        }
    }
}

/** A resolved address must survive app restarts so a repeated route does not consume API quota. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class OneMapRoomCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "one-map-cache-${UUID.randomUUID()}.db"
    private val databases = mutableListOf<PlannerDb>()

    @After
    fun removeDatabase() {
        databases.forEach { it.close() }
        context.deleteDatabase(databaseName)
    }

    private fun database(): PlannerDb =
        Room.databaseBuilder(context, PlannerDb::class.java, databaseName).build().also(databases::add)

    @Test
    fun restartingTheDatabaseRetainsResolvedPostalCodesWithoutAnotherNetworkRequest() = runBlocking {
        val api = FakeOneMapApi { postal, _ -> searchResult(postal) }
        val first = database()
        val place = OneMapLookup(first.dao(), api, tokenProvider = { "test-token" }).resolve("018956")
        assertNotNull(first.dao().place("018956"))
        first.close()

        val reopened = database()
        val restored = OneMapLookup(reopened.dao(), api, tokenProvider = { null }).resolve("018956")

        assertEquals(place, restored)
        assertEquals(1, api.calls)
    }
}

private class FakeOneMapApi(private val handler: suspend (String, String) -> JsonObject) : OneMapApi {
    var calls = 0
        private set

    override suspend fun search(postal: String, authorization: String, geom: String, details: String): JsonObject {
        calls++
        return handler(postal, authorization)
    }
}

private class MemoryPlannerDao : PlannerDao {
    private val places = mutableMapOf<String, CachedPlace>()
    private val legs = mutableMapOf<String, CachedLeg>()
    private val routes = mutableMapOf<String, SavedRoute>()

    override suspend fun place(postal: String): CachedPlace? = places[postal]
    override suspend fun place(p: CachedPlace) { places[p.postal] = p }
    override suspend fun leg(key: String): CachedLeg? = legs[key]
    override suspend fun leg(l: CachedLeg) { legs[l.key] = l }
    override suspend fun save(r: SavedRoute) { routes[r.id] = r }
    override fun history(): Flow<List<SavedRoute>> = flowOf(routes.values.toList())
    override suspend fun route(id: String): SavedRoute? = routes[id]
}

private fun samplePlace(postal: String): Place = Place(postal, 1.30, 103.80, "10", "Test road", "Test address")

private fun searchResult(postal: String, latitude: Double = 1.30): JsonObject = JsonParser.parseString(
    """{"results":[{"POSTAL":"$postal","LATITUDE":"$latitude","LONGITUDE":"103.80","BLK_NO":"10","ROAD_NAME":"Test road","ADDRESS":"Test address"}]}"""
).asJsonObject

private fun httpFailure(status: Int, body: String = "Service response", retryAfter: String? = null): HttpException {
    val response = okhttp3.Response.Builder()
        .request(Request.Builder().url("https://www.onemap.gov.sg/api/common/elastic/search").build())
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("Service response")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .build()
    return HttpException(Response.error<JsonObject>(body.toResponseBody("text/plain".toMediaType()), response))
}

private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit): T {
    try {
        block()
    } catch (failure: Throwable) {
        if (failure is T) return failure
        throw AssertionError("Expected ${T::class.java.simpleName}, got ${failure::class.java.simpleName}", failure)
    }
    throw AssertionError("Expected ${T::class.java.simpleName}")
}
