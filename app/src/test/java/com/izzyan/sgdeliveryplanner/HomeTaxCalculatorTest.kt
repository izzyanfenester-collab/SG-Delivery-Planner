package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

/** Home Tax Calculation is local-only; Payment and Delivery Summary math remain untouched. */
class HomeTaxCalculatorTest {
    private fun plan(
        id: String,
        savedAt: String?,
        finalRate: String
    ) = Plan(
        id = id,
        created = "2026-11-08T09:00:00",
        start = "2026-11-08T10:00:00",
        serviceMinutes = 10,
        mode = "Normal",
        stops = emptyList(),
        returnLeg = Leg(0.0, 0.0, 0.0),
        returned = "2026-11-08T12:00:00",
        geometry = emptyList(),
        summarySavedAt = savedAt,
        exchangeRate = finalRate
    )

    @Test fun latestSuccessfullySavedReportWinsOverNewerUnSavedRoute() {
        val olderSaved = plan("old", "2026-11-08T08:00:00", "3.50")
        val latestSaved = plan("latest", "2026-11-09T10:20:00", "3.57")
        val notSaved = plan("new-draft", null, "4.99")
        val rates = newestSavedHomeTaxRates(listOf(notSaved, olderSaved, latestSaved))
        assertNotNull(rates)
        assertEquals(BigDecimal("3.57"), rates?.afterMarkup)
        assertEquals(BigDecimal("3.17"), rates?.beforeMarkup)
    }

    @Test fun zeroOrMissingSavedReportsNeverInventRates() {
        assertNull(newestSavedHomeTaxRates(emptyList()))
        assertNull(newestSavedHomeTaxRates(listOf(plan("unsaved", null, "3.50"))))
        assertNull(newestSavedHomeTaxRates(listOf(plan("bad", "2026-11-09T01:00:00", "invalid"))))
        assertNull(newestSavedHomeTaxRates(listOf(plan("small", "2026-11-09T01:00:00", "0.40"))))
        // Do not silently use an older rate if the latest saved report's rate is invalid.
        assertNull(newestSavedHomeTaxRates(listOf(
            plan("old", "2026-11-08T01:00:00", "3.50"),
            plan("new", "2026-11-09T01:00:00", "N/A")
        )))
    }

    @Test fun suppliedThreeDecimalReferenceMatchesExpectedDisplayedAmounts() {
        val sample = HomeTaxRates(BigDecimal("3.171"), BigDecimal("3.571"))
        val result = requireNotNull(calculateHomeTax("470.00", sample))
        assertEquals("148.22", result.amountSgd)
        assertEquals("13.34", result.gstSgd)
        assertEquals("47.64", result.taxMyr)
    }

    @Test fun twoDecimalSavedFinalRateDerivesValidBeforeMarkupWithoutDoubleMarkup() {
        val rates = requireNotNull(newestSavedHomeTaxRates(
            listOf(plan("saved", "2026-11-09T15:00:00", "3.57"))
        ))
        assertEquals("3.17", rates.beforeMarkup.toPlainString())
        assertEquals("3.57", rates.afterMarkup.toPlainString())
        val result = requireNotNull(calculateHomeTax("470.00", rates))
        assertEquals("148.26", result.amountSgd)
        assertEquals("13.34", result.gstSgd)
        assertEquals("47.62", result.taxMyr)
    }

    @Test fun zeroInputYieldsZeroInBothCurrencies() {
        val rates = HomeTaxRates(BigDecimal("3.17"), BigDecimal("3.57"))
        assertEquals(HomeTaxResult("0.00", "0.00", "0.00"), calculateHomeTax("0", rates))
        assertEquals(HomeTaxResult("0.00", "0.00", "0.00"), calculateHomeTax("0.00", rates))
    }

    @Test fun noInputNoRateAndInvalidInvoiceDontCalculate() {
        val rates = HomeTaxRates(BigDecimal("3.17"), BigDecimal("3.57"))
        assertNull(calculateHomeTax("", rates))
        assertNull(calculateHomeTax("470", null))
        assertNull(calculateHomeTax("-100.00", rates))
        assertNull(calculateHomeTax("100.123", rates))
        assertNull(calculateHomeTax("abc", rates))
        assertNull(calculateHomeTax(".", rates))
        assertNull(calculateHomeTax("100", HomeTaxRates(BigDecimal.ZERO, BigDecimal("3.57"))))
    }

    @Test fun taxSgdUsesUnroundedConvertedAmountBeforeRounding() {
        // RM1.00 / 3.00 = 0.333333...; 9% of that rounds to S$0.03
        val result = requireNotNull(calculateHomeTax(
            "1.00", HomeTaxRates(BigDecimal("3.00"), BigDecimal("3.40"))
        ))
        assertEquals("0.33", result.amountSgd)
        assertEquals("0.03", result.gstSgd)
        assertEquals("0.10", result.taxMyr)
    }
}
