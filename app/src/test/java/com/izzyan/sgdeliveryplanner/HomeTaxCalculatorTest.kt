package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant

/** Dedicated Home Tax Calculator regression tests. No live requests are made by tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class HomeTaxCalculatorTest {
    private fun ratesJson(
        updated: Long = Instant.now().epochSecond - 3600,
        rate: String = "3.165184",
        base: String = "SGD",
        result: String = "success"
    ): String = JSONObject()
        .put("result", result)
        .put("base_code", base)
        .put("time_last_update_unix", updated)
        .put("rates", JSONObject().put("MYR", BigDecimal(rate)))
        .toString()

    private fun responseClient(body: String = "", code: Int = 200): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(code).message("Test response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun clearFxCache(context: Context) {
        context.getSharedPreferences("home_tax_live_fx_cache_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun exampleSampleUsesFullPrecisionAndSingleMarkup() {
        val rates = HomeTaxRates(BigDecimal("3.171"))
        assertEquals(BigDecimal("3.571"), rates.afterMarkup)
        // The 0.4 markup is added to the live rate, never to invoice/tax amounts.
        assertEquals(0, rates.afterMarkup.subtract(rates.beforeMarkup)
            .compareTo(BigDecimal("0.4")))
        val result = requireNotNull(calculateHomeTax("470.00", rates))
        assertEquals("148.22", result.amountSgd)
        assertEquals("13.34", result.gstSgd)
        assertEquals("47.64", result.taxMyr)
    }

    @Test fun sgdIsRoundedBeforeNinePercentGst() {
        // 0.83 / 3.00 = 0.27666.. -> S$0.28 -> GST S$0.03.
        // Calculating GST from unrounded SGD would instead give S$0.02.
        val result = requireNotNull(calculateHomeTax("0.83",
            HomeTaxRates(BigDecimal("3.00"))))
        assertEquals(HomeTaxResult("0.28", "0.03", "0.10"), result)
    }

    @Test fun validZeroInputAndRoundingInMyr() {
        assertEquals(HomeTaxResult("0.00", "0.00", "0.00"),
            calculateHomeTax("0", HomeTaxRates(BigDecimal("3.47"))))
        assertEquals(HomeTaxResult("0.00", "0.00", "0.00"),
            calculateHomeTax("0.00", HomeTaxRates(BigDecimal("3.47"))))
        assertNull(calculateHomeTax("-10.00", HomeTaxRates(BigDecimal("3.47"))))
        assertNull(calculateHomeTax("100.123", HomeTaxRates(BigDecimal("3.47"))))
        assertNull(calculateHomeTax("abc", HomeTaxRates(BigDecimal("3.47"))))
        assertNull(calculateHomeTax(".", HomeTaxRates(BigDecimal("3.47"))))
        assertNull(calculateHomeTax("", HomeTaxRates(BigDecimal("3.47"))))
        assertNull(calculateHomeTax("470", null))
        assertNull(calculateHomeTax("470", HomeTaxRates(BigDecimal.ZERO)))
    }

    @Test fun apiParserPreservesAllPublishedDecimalPlacesAndTimestamp() {
        val t = Instant.now().epochSecond - 3600
        val parsed = requireNotNull(parsePublishedSgdMyrRate(ratesJson(updated=t), t+10))
        assertEquals(BigDecimal("3.165184"), parsed.beforeMarkup)
        assertEquals(BigDecimal("3.565184"), parsed.afterMarkup)
        assertEquals(t, parsed.sourceUpdatedEpochSeconds)
    }

    @Test fun apiParserRejectsMalformedMissingZeroAndWrongCurrencies() {
        val now = Instant.now().epochSecond
        assertNull(parsePublishedSgdMyrRate("not json", now))
        assertNull(parsePublishedSgdMyrRate("{}", now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(base="USD"), now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(result="error"), now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(rate="0"), now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(rate="-1"), now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(rate="150"), now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(updated=0L), now))
        assertNull(parsePublishedSgdMyrRate(ratesJson(updated=now + 172800), now))
    }

    @Test fun fetchLatestWritesCacheForOfflineReopens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearFxCache(context)
        try {
            val repo = HomeTaxLiveFxRepository(context, responseClient(ratesJson()))
            assertNull(repo.cached())
            val fresh = requireNotNull(repo.fetchLatest())
            assertEquals(BigDecimal("3.165184"), fresh.beforeMarkup)
            assertEquals(fresh, repo.cached())

            // HTTP 503 simulates rate API unavailable; cached rate remains usable.
            val offline = HomeTaxLiveFxRepository(context, responseClient(code=503))
            assertNull(offline.fetchLatest())
            assertEquals(fresh, offline.cached())
            val result = calculateHomeTax("470.00", HomeTaxRates(offline.cached()!!.beforeMarkup))
            assertNotNull(result)
        } finally {
            clearFxCache(context)
        }
    }

    @Test fun invalidRateDoesNotOverwriteLastGoodRate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearFxCache(context)
        try {
            val good = HomeTaxLiveFxRepository(context, responseClient(ratesJson())).fetchLatest()
            assertNotNull(good)
            val invalid = HomeTaxLiveFxRepository(context, responseClient(ratesJson(rate="0")))
            assertNull(invalid.fetchLatest())
            assertEquals(good, invalid.cached())
        } finally {
            clearFxCache(context)
        }
    }

    @Test fun olderOnlineResponseCannotDowngradeOrMasqueradeAsFresh() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearFxCache(context)
        try {
            val now = Instant.now().epochSecond
            val newest = HomeTaxLiveFxRepository(context,
                responseClient(ratesJson(updated=now-3600, rate="3.21")))
            val good = requireNotNull(newest.fetchLatest())
            val older = HomeTaxLiveFxRepository(context,
                responseClient(ratesJson(updated=now-86400, rate="3.17")))
            assertNull(older.fetchLatest())
            assertEquals(good, older.cached())
        } finally {
            clearFxCache(context)
        }
    }

    @Test fun offlineAndOldStatusAreClearlyMarkedWithoutShowingRates() {
        val now = Instant.now().epochSecond
        val old = LiveHomeFxQuote(BigDecimal("3.171"), now-5L*86400L)
        val cachedLabel = homeTaxRateStatus(old, true, now)
        assertTrue(cachedLabel.contains("Using cached"))
        assertTrue(cachedLabel.contains("5 days old"))
        assertFalse(cachedLabel.contains("3.171"))
        val refreshedLabel = homeTaxRateStatus(old, false, now)
        assertTrue(refreshedLabel.contains("Latest published FX"))
        assertFalse(refreshedLabel.contains("3.571"))
    }

    @Test fun cachedQuoteNeverInventedWhenNoNetworkOrPriorData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        clearFxCache(context)
        try {
            val repo = HomeTaxLiveFxRepository(context, responseClient(code=429))
            assertNull(repo.cached())
            assertNull(repo.fetchLatest())
            assertNull(repo.cached())
        } finally {
            clearFxCache(context)
        }
    }
}
