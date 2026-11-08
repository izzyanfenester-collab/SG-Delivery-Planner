package com.izzyan.sgdeliveryplanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard minimum touch/row dimensions and unchanged existing Payment calculations. */
class PaymentCompactUiTest {
    @Test fun paymentOnlySpacingUsesCompactAndAccessibleMinima() {
        assertEquals(12f, PaymentCompactLayout.ScreenHorizontal.value, 0.01f)
        assertEquals(6f, PaymentCompactLayout.ScreenVertical.value, 0.01f)
        assertEquals(10f, PaymentCompactLayout.SectionGap.value, 0.01f)
        assertEquals(6f, PaymentCompactLayout.IntraCardGap.value, 0.01f)
        assertEquals(10f, PaymentCompactLayout.CardPadding.value, 0.01f)
        assertEquals(8f, PaymentCompactLayout.TableCardPadding.value, 0.01f)
        assertEquals(56f, PaymentCompactLayout.OverviewMinHeight.value, 0.01f)
        assertEquals(40f, PaymentCompactLayout.CalculatorTitleMinHeight.value, 0.01f)
        assertEquals(48f, PaymentCompactLayout.FieldMinHeight.value, 0.01f)
        assertEquals(42f, PaymentCompactLayout.InputMinHeight.value, 0.01f)
        assertEquals(56f, PaymentCompactLayout.GrandTotalMinHeight.value, 0.01f)
        assertEquals(48f, PaymentCompactLayout.TableRowMinHeight.value, 0.01f)
        assertEquals(48f, PaymentCompactLayout.HistoryRowMinHeight.value, 0.01f)
        assertEquals(5, PaymentCompactLayout.HistoryVisibleRows)
    }

    @Test fun paymentFormulasRemainExactWithEditableTaxAndOthers() {
        val result = calculatePayment("30", "7.25", "11.60", "5.40")
        assertEquals("217.50", result?.totalPaymentRm)
        assertEquals("234.50", result?.grandTotalPaymentRm)
        val withReportTax = calculatePayment("30", "7.25",
            effectivePaymentTaxRm("11.60", null), "5.40")
        assertEquals(result, withReportTax)
        assertEquals("0.00", effectivePaymentTaxRm(null, null))
        assertFalse(validCurrencyInput("-100"))
        assertTrue(validCurrencyInput("12.30"))
    }
}
