package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Build 834 is presentation-only; preserve the Official #833 financial calculations. */
class PaymentDesignCTest {
    @Test fun responsivePanelsAvoidNarrowScreenClipping() {
        assertFalse(PaymentDesignC.useSideBySideCards(320))
        assertFalse(PaymentDesignC.useSideBySideCards(360))
        assertTrue(PaymentDesignC.useSideBySideCards(400))
        assertTrue(PaymentDesignC.useSideBySideCards(480))
        assertFalse(PaymentDesignC.useFourAcrossKpis(400))
        assertFalse(PaymentDesignC.useFourAcrossKpis(480))
        assertFalse(PaymentDesignC.useFourAcrossKpis(700))
    }

    @Test fun monthNavigationOffersAllTwelveMonthsAndWraps() {
        val months = generateSequence(1, PaymentDesignC::nextMonth).take(12).toList()
        assertEquals((1..12).toList(), months)
        assertEquals(12, PaymentDesignC.previousMonth(1))
        assertEquals(1, PaymentDesignC.nextMonth(12))
        assertEquals(6, PaymentDesignC.previousMonth(7))
    }

    @Test fun existingDualTaxCalculationIsUnchanged() {
        val row = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "20", "30", "47.70", "50.00",
            "40.00", "10.00", "20.00"
        ))
        assertEquals("600.00", row.totalPaymentRm)
        assertEquals("697.70", row.grandTotalPaymentRm)
        assertEquals("117.70", financialsForRecord(row).totalDeductionRm)
        assertEquals("580.00", financialsForRecord(row).nettEarningRm)
        assertTrue(validPaymentRecord(row))
    }

    @Test fun selectedMonthKpisShowTotalGrandDeductionAndNett() {
        val a = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "20", "30", "47.70", "50.00",
            "40.00", "10.00", "20.00"
        ))
        val b = requireNotNull(revisedPaymentRecord(
            "2026-11-08", "2", "15", "5", "0",
            "5", "0", "0"
        ))
        val september = requireNotNull(revisedPaymentRecord(
            "2026-09-04", "1", "100", "10", "0", "0", "0", "0"
        ))
        val records = listOf(a, b, september)
        assertEquals("630.00", monthlyPaymentTotals(records, 2026, 11).totalPaymentRm)
        assertEquals(PaymentNetTotals("732.70", "127.70", "605.00"),
            monthlyPaymentNetTotals(records, 2026, 11))
        assertEquals(PaymentNetTotals("110.00", "10.00", "100.00"),
            monthlyPaymentNetTotals(records, 2026, 9))
        assertEquals(PaymentNetTotals("0.00", "0.00", "0.00"),
            monthlyPaymentNetTotals(records, 2026, 1))
    }

    @Test fun paymentDateOverrideUsesHomeDateWithoutMutatingIt() {
        val home = LocalDate.of(2026, 11, 9)
        assertEquals(home, paymentDateForCalculator(home, null))
        assertEquals(LocalDate.of(2026, 11, 7),
            paymentDateForCalculator(home, "2026-11-07"))
        assertEquals(home, paymentDateForCalculator(home, null))
    }

    @Test fun historicalRecordsKeepOriginalGrandTotalAndNetDerivation() {
        val old = PaymentRecord("2026-10-26", 2, "12.50", "25.00",
            "5.20", "0.00", "30.20")
        assertEquals("25.00", old.totalPaymentRm)
        assertEquals("30.20", old.grandTotalPaymentRm)
        assertEquals("5.20", financialsForRecord(old).totalDeductionRm)
        assertEquals("25.00", financialsForRecord(old).nettEarningRm)
    }

    @Test fun compactHistoryStillRetainsFiveRowScrollThreshold() {
        assertEquals(5, PaymentCompactLayout.HistoryVisibleRows)
        assertEquals("Total\npayment (RM)",
            paymentTableHeaderValue("Total payment (RM)"))
    }
}
