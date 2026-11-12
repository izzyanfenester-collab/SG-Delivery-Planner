package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout refinement must not alter the financial formulas or historical records. */
class PaymentUltraCompactTest {
    @Test fun compactTargetsReduceBoxHeightsWhilePreservingReadableScaledRows() {
        assertEquals(38f, PaymentDesignC.InputHeight.value, 0.01f)
        assertTrue(PaymentDesignC.InputHeight.value < 48f)
        assertEquals(44f, PaymentDesignC.HistoryRowHeight.value, 0.01f)
        assertTrue(PaymentDesignC.HistoryRowHeight.value < PaymentCompactLayout.HistoryRowMinHeight.value)
        assertEquals(40f, PaymentDesignC.HistoryHeaderHeight.value, 0.01f)
        assertEquals(52f, PaymentDesignC.MonthlyMetricHeight.value, 0.01f)
        assertEquals(4f, PaymentDesignC.CardGap.value, 0.01f)
        assertEquals(3f, PaymentDesignC.InsideGap.value, 0.01f)
        assertEquals(6f, PaymentDesignC.CardPadding.value, 0.01f)
        assertEquals(46f, PaymentDesignC.ControlHeight.value, 0.01f)
        assertEquals(5, PaymentCompactLayout.HistoryVisibleRows)
        assertEquals(44f, PaymentDesignC.historyRowHeight(1.0f).value, 0.01f)
        assertEquals(54f, PaymentDesignC.historyRowHeight(1.25f).value, 0.01f)
        assertEquals(82f, PaymentDesignC.historyRowHeight(2.0f).value, 0.01f)
    }

    @Test fun dashboardAlwaysUsesTwoByTwoMonthlyKpis() {
        assertFalse(PaymentDesignC.useFourAcrossKpis(320))
        assertFalse(PaymentDesignC.useFourAcrossKpis(480))
        assertFalse(PaymentDesignC.useFourAcrossKpis(800))
        assertFalse(PaymentDesignC.useSideBySideCards(360))
        assertTrue(PaymentDesignC.useSideBySideCards(400))
    }

    @Test fun originalTaxAndDeductionFormulasStayUnchanged() {
        val record = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "20", "30.00", "47.70", "50.00",
            "40.00", "10.00", "20.00"
        ))
        assertEquals("600.00", record.totalPaymentRm)
        assertEquals("697.70", record.grandTotalPaymentRm)
        assertEquals("117.70", financialsForRecord(record).totalDeductionRm)
        assertEquals("580.00", financialsForRecord(record).nettEarningRm)
        assertTrue(validPaymentRecord(record))
    }

    @Test fun historicalPayoutsAndMonthlyAggregationStayCorrect() {
        val old = PaymentRecord("2026-11-07", 3, "10.00",
            "30.00", "5.00", "0.00", "35.00")
        assertTrue(validPaymentRecord(old))
        assertEquals("5.00", financialsForRecord(old).totalDeductionRm)
        assertEquals("30.00", financialsForRecord(old).nettEarningRm)
        assertEquals("30.00", monthlyPaymentTotals(listOf(old), 2026, 11).totalPaymentRm)
        assertEquals(PaymentNetTotals("35.00", "5.00", "30.00"),
            monthlyPaymentNetTotals(listOf(old), 2026, 11))
    }
}
