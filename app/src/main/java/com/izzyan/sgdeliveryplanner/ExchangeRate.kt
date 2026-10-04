package com.izzyan.sgdeliveryplanner

import java.math.BigDecimal
import java.math.RoundingMode

/** Positive final SGD → MYR rate; manual values already include any desired markup. */
fun normalizedExchangeRate(text: String): String? = text.toBigDecimalOrNull()
    ?.takeIf { it > BigDecimal.ZERO && it <= BigDecimal("100") }
    ?.setScale(2, RoundingMode.HALF_UP)?.takeIf { it > BigDecimal.ZERO }?.toPlainString()

fun markedUpExchangeRate(base: BigDecimal): String {
    require(base > BigDecimal.ZERO)
    return requireNotNull(normalizedExchangeRate(base.add(BigDecimal("0.40")).toPlainString()))
}

fun taxMyr(tax: String, rate: String): String =
    BigDecimal(normalizedCurrency(tax) ?: "0.00")
        .multiply(BigDecimal(normalizedExchangeRate(rate) ?: "3.60"))
        .setScale(2, RoundingMode.HALF_UP).toPlainString()

fun formatTax(plan: Plan): String = "${formatCurrency(plan.tax)} (RM ${taxMyr(plan.tax, plan.exchangeRate)})"

interface ExchangeRateProvider {
    /** Returns the final rate including the 0.40 markup; failures leave saved values intact. */
    suspend fun finalSgdMyrRate(): String
}

/** Frankfurter's public daily reference rates need no API key or Google Sheets runtime. */
class OnlineExchangeRateProvider(private val api: Api = createRoutingApi("https://api.frankfurter.dev/")) : ExchangeRateProvider {
    override suspend fun finalSgdMyrRate(): String {
        val response = api.get("https://api.frankfurter.dev/v1/latest?base=SGD&symbols=MYR")
        require(response.get("base")?.asString == "SGD") { "Unexpected exchange-rate currency" }
        return markedUpExchangeRate(response.getAsJsonObject("rates").get("MYR").asBigDecimal)
    }
}
