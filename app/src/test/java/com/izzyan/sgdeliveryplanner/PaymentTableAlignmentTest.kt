package com.izzyan.sgdeliveryplanner

import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression checks for the two shared Payment tables' presentation-only rules. */
class PaymentTableAlignmentTest {

    @Test fun headersUseExactlyTwoReadableLines() {
        assertEquals("Total\npayment (RM)",
            paymentTableHeaderValue("Total payment (RM)"))
        assertEquals("Grand total\npayment (RM)",
            paymentTableHeaderValue("Grand total payment (RM)"))
        assertEquals("Date", paymentTableHeaderValue("Date"))
        assertEquals("Month", paymentTableHeaderValue("Month"))
    }

    @Test fun bothPaymentAmountColumnsAreCentered() {
        assertEquals(TextAlign.Start, paymentTableAlignment(0))
        assertEquals(TextAlign.Center, paymentTableAlignment(1))
        assertEquals(TextAlign.Center, paymentTableAlignment(2))
    }

    @Test fun formattingChangesDoNotAlterMonetaryValues() {
        val amounts = calculatePayment("3", "12.50", "5.20", "1.30")
        assertEquals("37.50", amounts?.totalPaymentRm)
        assertEquals("44.00", amounts?.grandTotalPaymentRm)
        assertEquals("44.00", amounts?.grandTotalPaymentRm)
    }
}
