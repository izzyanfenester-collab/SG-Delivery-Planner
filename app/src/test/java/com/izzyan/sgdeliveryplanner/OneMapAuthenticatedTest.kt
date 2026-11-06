package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneMapAuthenticatedTest {
    @Test fun accountIsRequiredBeforeCallingTheSearchEndpoint() = runBlocking {
        val tokens = FakeTokenService()
        val search = FakeProtectedSearch()
        val api = AuthenticatedOneMapApi(login = { null }, tokens = tokens, searchService = search)
        val failure = runCatching { api.search("018956") }.exceptionOrNull()
        assertTrue(failure is PlannerError)
        assertEquals(0, tokens.calls)
        assertEquals(0, search.calls)
    }

    @Test fun tokenIsReusedAndAutomaticallyRenewedBeforeExpiry() = runBlocking {
        var now = 1_000_000_000_000L
        val tokens = FakeTokenService { count ->
            JsonParser.parseString("""{"access_token":"token$count","expiry_timestamp":${now / 1000 + 86400}}""").asJsonObject
        }
        val search = FakeProtectedSearch()
        val api = AuthenticatedOneMapApi(
            login = { "owner@example.com" to "password" }, tokens = tokens,
            searchService = search, clock = { now }
        )
        api.search("018956")
        api.search("200640")
        assertEquals(1, tokens.calls)
        assertEquals(listOf("token1", "token1"), search.usedTokens)
        now += 86_400_000L
        api.search("238858")
        assertEquals(2, tokens.calls)
        assertEquals("token2", search.usedTokens.last())
    }

    @Test fun deletingCredentialsRevokesTheCachedToken() = runBlocking {
        var configured = true
        var generation = 1L
        var now = 1_000_000_000_000L
        val tokens = FakeTokenService {
            JsonParser.parseString("""{"access_token":"token","expiry_timestamp":${now / 1000 + 86400}}""").asJsonObject
        }
        val search = FakeProtectedSearch()
        val api = AuthenticatedOneMapApi(
            login = { if (configured) "owner@example.com" to "password" else null },
            tokens = tokens, searchService = search,
            credentialGeneration = { generation }, clock = { now }
        )
        api.search("018956")
        configured = false
        generation++
        val error = runCatching { api.search("200640") }.exceptionOrNull()
        assertTrue(error is PlannerError)
        assertEquals(1, search.calls)
    }
}

private class FakeTokenService(private val body: (Int) -> JsonObject = { JsonObject() }) : OneMapTokenApi {
    var calls = 0
    override suspend fun generateToken(credentials: OneMapLoginRequest): JsonObject = body(++calls)
}

private class FakeProtectedSearch : OneMapProtectedSearchApi {
    var calls = 0
    val usedTokens = mutableListOf<String>()
    override suspend fun search(token: String, value: String, geom: String, details: String): JsonObject {
        calls++
        usedTokens += token
        return JsonObject().apply { add("results", com.google.gson.JsonArray()) }
    }
}
