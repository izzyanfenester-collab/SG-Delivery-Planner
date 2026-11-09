package com.izzyan.sgdeliveryplanner

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Only used by Home > Tax Calculation. No Payment or Delivery Report state is touched.
 *
 * ExchangeRate-API's public endpoint provides latest-published daily indicative
 * mid-market SGD/MYR data, NOT streaming quotes or the GoogleFinance feed.
 * Per provider terms, attribution is shown beside the Tax Calculation dialog.
 */
private const val LIVE_FX_ENDPOINT = "https://open.er-api.com/v6/latest/SGD"
private const val LIVE_FX_CACHE = "home_tax_live_fx_cache_v1"
internal val HOME_TAX_MARKUP_RM = BigDecimal("0.40")

internal data class LiveHomeFxQuote(
    val beforeMarkup: BigDecimal,
    val sourceUpdatedEpochSeconds: Long
) {
    val afterMarkup: BigDecimal get() = beforeMarkup.add(HOME_TAX_MARKUP_RM)
}

/** Fail closed on malformed or invalid quotes, zero FX and wrong currency pairs.
 * Use the exact rate text returned by the service instead of rounding to 2dp.
 */
internal fun parsePublishedSgdMyrRate(
    payload: String,
    nowEpochSeconds: Long = Instant.now().epochSecond
): LiveHomeFxQuote? = runCatching {
    val json = JSONObject(payload)
    if (json.optString("result") != "success" || json.optString("base_code") != "SGD") {
        return@runCatching null
    }
    val raw = json.optJSONObject("rates")?.opt("MYR")?.toString() ?: return@runCatching null
    val rate = raw.toBigDecimalOrNull()?.takeIf {
        it > BigDecimal.ZERO && it < BigDecimal("100")
    } ?: return@runCatching null
    val timestamp = json.optLong("time_last_update_unix", 0L)
    // Avoid using a rate from an uninitialized/mock epoch or a publication in the future.
    if (timestamp < 1_577_836_800L || timestamp > nowEpochSeconds + 86_400L) {
        return@runCatching null
    }
    LiveHomeFxQuote(rate, timestamp)
}.getOrNull()

internal class HomeTaxLiveFxRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val prefs = context.applicationContext.getSharedPreferences(
        LIVE_FX_CACHE, Context.MODE_PRIVATE
    )

    fun cached(): LiveHomeFxQuote? {
        val savedRate = prefs.getString("base_rate", null)?.toBigDecimalOrNull() ?: return null
        val updated = prefs.getLong("source_updated_at", 0L)
        if (savedRate <= BigDecimal.ZERO || savedRate >= BigDecimal("100") ||
            updated < 1_577_836_800L || updated > Instant.now().epochSecond + 86_400L) {
            return null
        }
        return LiveHomeFxQuote(savedRate, updated)
    }

    /** Network I/O: call on Dispatchers.IO. Never overwrite cache on an error.
     * A fresh HTTP call is made each time the dialog opens or Refresh is tapped.
     */
    fun fetchLatest(): LiveHomeFxQuote? {
        val request = Request.Builder().url(LIVE_FX_ENDPOINT)
            .header("Accept", "application/json")
            .header("User-Agent", "SG-Delivery-Android-Tax-Calculator")
            .header("Cache-Control", "no-cache")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseText = response.body?.string() ?: return null
                val quote = parsePublishedSgdMyrRate(responseText) ?: return null
                val cached = cached()
                // Do not replace newer cached quotes with an older publication.
                if (cached != null && quote.sourceUpdatedEpochSeconds < cached.sourceUpdatedEpochSeconds) {
                    return cached
                }
                prefs.edit()
                    .putString("base_rate", quote.beforeMarkup.toPlainString())
                    .putLong("source_updated_at", quote.sourceUpdatedEpochSeconds)
                    .apply()
                quote
            }
        } catch (_: Exception) {
            null
        }
    }
}
