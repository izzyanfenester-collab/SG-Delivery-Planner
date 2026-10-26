package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class PaymentTest {
    private val date = LocalDate.of(2026, 10, 26)

    private fun route(date: LocalDate, tax: String, savedAt: String?): Plan = schedule(
        listOf(depot),
        listOf(Leg(1.0, 180.0, 10.0), Leg(1.0, 180.0, 10.0)),
        date.atTime(10, 0), 8, "Normal", emptyList()
    ).copy(tax = tax, exchangeRate = "3.60", summarySavedAt = savedAt)

    @Test fun grandTotalUsesQuantityTimesCommissionPlusTaxAndOthers() {
        val result = calculatePayment("3", "12.50", "5.20", "1.30")
        assertEquals("37.50", result?.totalPaymentRm)
        assertEquals("44.00", result?.grandTotalPaymentRm)
    }

    @Test fun optionalOthersIsZeroAndAllCurrencyIsExactDecimal() {
        val result = calculatePayment("2", "15.99", "2.57", "")
        assertEquals("31.98", result?.totalPaymentRm)
        assertEquals("34.55", result?.grandTotalPaymentRm)
    }

    @Test fun invalidQuantityAndCommissionCannotBeSaved() {
        assertNull(calculatePayment("", "5.00", "0.00", ""))
        assertNull(calculatePayment("0", "5.00", "0.00", ""))
        assertNull(calculatePayment("1000000", "5.00", "0.00", ""))
        assertNull(calculatePayment("2", "", "0.00", ""))
        assertNull(calculatePayment("2", "-1.00", "0.00", ""))
        assertNull(calculatePayment("2", "1.234", "0.00", ""))
    }

    @Test fun taxUsesOnlyLatestSavedReportForSelectedHomeDate() {
        val old = route(date, "1.00", "2026-10-26T12:00")
        val latest = route(date, "5.00", "2026-10-26T13:00")
        val unsaved = route(date, "100.00", null)
        val otherDate = route(date.minusDays(1), "20.00", "2026-10-25T15:00")
        assertEquals("18.00", savedReportTaxRm(date, listOf(otherDate, old, latest, unsaved)))
        assertNull(savedReportTaxRm(date.plusDays(1), listOf(old, latest)))
        assertNull(savedReportTaxRm(date, listOf(unsaved)))
    }

    @Test fun paymentsRemainStoredInSaveOrderAfterNewLedgerIsOpened() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        val first = PaymentRecord("2026-10-26", 3, "12.50", "37.50", "5.20", "1.30", "44.00")
        val second = PaymentRecord("2026-10-26", 2, "10.00", "20.00", "5.20", "0.00", "25.20")
        val ledger = PaymentLedger(context)
        assertTrue(ledger.append(first))
        assertTrue(ledger.append(second))
        val afterRestart = PaymentLedger(context).read()
        assertEquals(listOf(first, second), afterRestart)
        assertEquals("44.00", afterRestart[0].grandTotalPaymentRm)
        assertEquals("25.20", afterRestart[1].grandTotalPaymentRm)
        assertFalse(afterRestart.isEmpty())
    }
}
