package com.izzyan.sgdeliveryplanner

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ExchangeRateTest {
    @Test fun markupAndMyrTaxUseDecimalHalfUpRounding() {
        assertEquals("3.60", markedUpExchangeRate(BigDecimal("3.20")))
        assertEquals("99.79", taxMyr("27.72", "3.60"))
        assertEquals("0.02", taxMyr("0.01", "1.50"))
        assertEquals("0.00", taxMyr("0", "3.60"))
        assertNull(normalizedExchangeRate("0"))
        assertNull(normalizedExchangeRate("-1"))
        assertNull(normalizedExchangeRate("NaN"))
        assertNull(normalizedExchangeRate("101"))
        assertEquals("3.61", normalizedExchangeRate("3.605"))
    }

    @Test fun onlineProviderRequestsSgdMyrAndAddsMarkupExactlyOnce() = runBlocking {
        val provider = OnlineExchangeRateProvider(object : Api {
            override suspend fun get(url: String): com.google.gson.JsonObject {
                assertEquals("https://api.frankfurter.dev/v1/latest?base=SGD&symbols=MYR", url)
                return JsonParser.parseString("""{"base":"SGD","rates":{"MYR":3.20}}""").asJsonObject
            }
        })
        assertEquals("3.60", provider.finalSgdMyrRate())
    }

    @Test fun onlineFailureIsPropagatedForSavedManualFallback() = runBlocking {
        val provider = OnlineExchangeRateProvider(object : Api {
            override suspend fun get(url: String): com.google.gson.JsonObject = throw java.io.IOException("offline")
        })
        try { provider.finalSgdMyrRate(); fail("Expected failure") } catch (_: java.io.IOException) { }
    }
}
