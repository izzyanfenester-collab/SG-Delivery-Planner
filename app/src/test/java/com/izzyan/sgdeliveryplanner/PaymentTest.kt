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
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
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

    @Test fun manualTaxOverridesSavedDeliveryReportAndResetReturnsToReport() {
        val reportValue = "66.53"
        assertEquals(reportValue, effectivePaymentTaxRm(reportValue, null))
        assertEquals("40.00", effectivePaymentTaxRm(reportValue, "40.00"))
        val changed = calculatePayment("10", "30.00", effectivePaymentTaxRm(reportValue, "40.00"), "5.00")
        assertEquals(PaymentCalculation("300.00", "345.00"), changed)
        assertEquals("66.53", effectivePaymentTaxRm(reportValue, null))
        assertEquals("0.00", effectivePaymentTaxRm(null, null))
        assertEquals("12.75", effectivePaymentTaxRm(null, "12.75"))
        assertNull(calculatePayment("10", "30.00", effectivePaymentTaxRm(reportValue, ""), ""))
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

    @Test fun monthlyTotalsUseSavedValuesAndNeverMixYears() {
        val octOne = PaymentRecord("2026-10-26", 3, "10.00", "30.00", "3.00", "2.00", "35.00")
        val octTwo = PaymentRecord("2026-10-31", 2, "12.00", "24.00", "4.00", "1.00", "29.00")
        val nov = PaymentRecord("2026-11-01", 1, "20.00", "20.00", "0.00", "0.00", "20.00")
        val previousYear = PaymentRecord("2025-10-26", 1, "99.00", "99.00", "0.00", "0.00", "99.00")
        val all = listOf(octOne, octTwo, nov, previousYear)
        assertEquals(PaymentCalculation("54.00", "64.00"), monthlyPaymentTotals(all, 2026, 10))
        assertEquals(PaymentCalculation("20.00", "20.00"), monthlyPaymentTotals(all, 2026, 11))
        assertEquals(PaymentCalculation("99.00", "99.00"), monthlyPaymentTotals(all, 2025, 10))
        assertEquals(PaymentCalculation("0.00", "0.00"), monthlyPaymentTotals(all, 2026, 9))
    }

    @Test fun compactMonthSummaryHidesZeroRowsAndAddsFullYearTotal() {
        val october = PaymentRecord("2026-10-26", 2, "400.00", "800.00", "20.00", "0.00", "820.00")
        val anotherOctober = PaymentRecord("2026-10-27", 1, "40.00", "40.00", "0.00", "0.00", "40.00")
        val december = PaymentRecord("2026-12-15", 1, "30.00", "30.00", "5.00", "0.00", "35.00")
        val priorYear = PaymentRecord("2025-10-26", 1, "999.00", "999.00", "0.00", "0.00", "999.00")
        val records = listOf(october, anotherOctober, december, priorYear)
        val visible = nonZeroMonthlyPaymentRows(records, 2026)
        assertEquals(listOf(10, 12), visible.map { it.first })
        assertEquals(PaymentCalculation("840.00", "860.00"), visible[0].second)
        assertEquals(PaymentCalculation("870.00", "895.00"), yearlyPaymentTotals(records, 2026))
        assertEquals(PaymentCalculation("999.00", "999.00"), yearlyPaymentTotals(records, 2025))
    }

    @Test fun monthlySummaryCanShowGrandTotalOnlyAndAlwaysCalculateZeroYearTotal() {
        val otherOnly = PaymentRecord("2026-03-03", 1, "0.00", "0.00", "2.50", "0.00", "2.50")
        assertEquals(listOf(3), nonZeroMonthlyPaymentRows(listOf(otherOnly), 2026).map { it.first })
        assertEquals(PaymentCalculation("0.00", "2.50"), yearlyPaymentTotals(listOf(otherOnly), 2026))
        assertTrue(nonZeroMonthlyPaymentRows(emptyList(), 2026).isEmpty())
        assertEquals(PaymentCalculation("0.00", "0.00"), yearlyPaymentTotals(emptyList(), 2026))
    }

    @Test fun repeatedRestoreKeepsExistingPaymentsAndDuplicatesFromSameBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE).edit().clear().commit()
        val a = PaymentRecord("2026-10-26", 1, "10.00", "10.00", "0.00", "0.00", "10.00")
        val b = PaymentRecord("2026-10-27", 1, "9.00", "9.00", "0.00", "0.00", "9.00")
        val ledger = PaymentLedger(context)
        assertTrue(ledger.append(a))
        assertEquals(2, ledger.mergeRestored(listOf(a, a, b)))
        assertEquals(listOf(a, a, b), ledger.read())
        assertEquals(0, ledger.mergeRestored(listOf(a, a, b)))
        assertEquals(listOf(a, a, b), ledger.read())
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
