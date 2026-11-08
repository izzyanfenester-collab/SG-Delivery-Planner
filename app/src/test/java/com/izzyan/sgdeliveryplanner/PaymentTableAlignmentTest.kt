package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression guard for the shared Payment History / Monthly Summary header rules. */
class PaymentTableAlignmentTest {
    @Test fun bothLongHeadingsWrapIntoTwoReadablySizedLines() {
        assertEquals("Total\npayment (RM)", paymentCompactTableHeading("Total payment (RM)"))
        assertEquals("Grand total\npayment (RM)", paymentCompactTableHeading("Grand total payment (RM)"))
        assertEquals(2, paymentCompactTableHeading("Total payment (RM)").lines().size)
        assertEquals(2, paymentCompactTableHeading("Grand total payment (RM)").lines().size)
        assertEquals("Date", paymentCompactTableHeading("Date"))
        assertEquals("Month", paymentCompactTableHeading("Month"))
    }

    @Test fun paymentMathAndMonthlySummaryRemainUnchanged() {
        val saved = listOf(
            PaymentRecord("2026-11-01", 3, "20.00", "60.00", "1.00", "2.00", "63.00"),
            PaymentRecord("2026-11-07", 2, "20.00", "40.00", "3.00", "5.00", "48.00")
        )
        val total = monthlyPaymentTotals(saved, 2026, 11)
        assertEquals("100.00", total.totalPaymentRm)
        assertEquals("111.00", total.grandTotalPaymentRm)
        assertTrue(validPaymentRecord(saved.first()))
    }
}
