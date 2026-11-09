package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PaymentDeductions833Test {
    @Test fun dualTaxAndExpensesMatchRequestedExample() {
        val record = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "20", "30.00", "47.70", "50.00",
            "40.00", "10.00", "20.00"
        ))
        assertEquals("600.00", record.totalPaymentRm)
        assertEquals("697.70", record.grandTotalPaymentRm)
        assertEquals("47.70", record.taxRm)
        assertEquals("117.70", record.totalDeductionRm)
        assertEquals("580.00", record.nettEarningRm)
        assertEquals(PaymentFinancials("117.70", "580.00"), financialsForRecord(record))
        assertTrue(validPaymentRecord(record))
    }

    @Test fun netCanBeNegativeWithoutAlteringGrossEarnings() {
        val record = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "1", "5", "2", "", "15", "0", "1"
        ))
        assertEquals("7.00", record.grandTotalPaymentRm)
        assertEquals("18.00", record.totalDeductionRm)
        assertEquals("-11.00", record.nettEarningRm)
        assertTrue(validPaymentRecord(record))
    }

    @Test fun emptyExpensesDefaultZeroAndRejectNegativeExpense() {
        val record = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "2", "15", "5.00", "", "", "", ""
        ))
        assertEquals("35.00", record.grandTotalPaymentRm)
        assertEquals("5.00", record.totalDeductionRm)
        assertEquals("30.00", record.nettEarningRm)
        assertNull(revisedPaymentRecord("2026-11-07", "2", "15", "5", "", "-1", "", ""))
    }

    @Test fun dateStartsWithHomeAndManualOverrideNeverMutatesHome() {
        val home = LocalDate.of(2026, 11, 9)
        assertEquals(home, paymentDateForCalculator(home, null))
        assertEquals(LocalDate.of(2026, 11, 7), paymentDateForCalculator(home, "2026-11-07"))
        assertEquals(home, paymentDateForCalculator(home, null))
        assertEquals(home.plusDays(1), paymentDateForCalculator(home.plusDays(1), null))
        assertEquals(LocalDate.of(2026, 11, 7), paymentDateForCalculator(home.plusDays(1), "2026-11-07"))
    }

    @Test fun monthlySummaryUsesSavedDatesAndFourColumnAmounts() {
        val nov = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "20", "30", "47.70", "50",
            "40", "10", "20"
        ))
        val aug = requireNotNull(revisedPaymentRecord(
            "2026-08-06", "1", "50", "5", "0",
            "10", "0", "0"
        ))
        val previousYear = aug.copy(date = "2025-08-06")
        val records = listOf(nov, aug, previousYear)
        assertEquals(PaymentNetTotals("697.70", "117.70", "580.00"),
            monthlyPaymentNetTotals(records, 2026, 11))
        assertEquals(PaymentNetTotals("55.00", "15.00", "40.00"),
            monthlyPaymentNetTotals(records, 2026, 8))
        assertEquals(PaymentNetTotals("752.70", "132.70", "620.00"),
            yearlyPaymentNetTotals(records, 2026))
        assertEquals(listOf(8, 11), nonZeroMonthlyPaymentNetRows(records, 2026).map { it.first })
    }

    @Test fun legacyB832PaymentJsonKeepsGrandWithoutAddingTaxTwice() {
        val oldJson = """[{"date":"2026-11-07","quantity":2,"commissionPerParcelRm":"10.00","totalPaymentRm":"20.00","taxRm":"4.00","othersRm":"1.00","grandTotalPaymentRm":"25.00"}]"""
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
        prefs.edit().putString("records", oldJson).commit()
        try {
            val historical = PaymentLedger(context).read().single()
            assertEquals("25.00", historical.grandTotalPaymentRm)
            assertTrue(historical.schemaVersion <= 1)
            assertEquals(PaymentFinancials("4.00", "21.00"), financialsForRecord(historical))
            assertEquals(PaymentNetTotals("25.00", "4.00", "21.00"),
                monthlyPaymentNetTotals(listOf(historical), 2026, 11))
            assertTrue(validPaymentRecord(historical))
            assertTrue(PaymentLedger(context).mergeRestored(listOf(historical)).let { it == 0 })
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun existingRecordCanBeEditedWithoutReplacingNeighbors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val old = PaymentRecord("2026-11-07", 2, "20.00", "40.00", "5.00", "0.00", "45.00")
        val neighbor = PaymentRecord("2026-11-08", 1, "50.00", "50.00", "0.00", "0.00", "50.00")
        val ledger = PaymentLedger(context)
        try {
            assertTrue(ledger.append(old))
            assertTrue(ledger.append(neighbor))
            val revised = requireNotNull(revisedPaymentRecord(
                "2026-11-06", "2", "20", "5", "0", "10", "2", "3"
            ))
            assertTrue(ledger.replaceAt(0, old, revised))
            assertEquals(listOf(revised, neighbor), ledger.read())
            assertEquals(PaymentNetTotals("45.00", "20.00", "25.00"),
                monthlyPaymentNetTotals(listOf(revised), 2026, 11))
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun rejectCorruptedStoredDeductions() {
        val clean = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "3", "10", "4", "0", "1", "2", "3"
        ))
        assertFalse(validPaymentRecord(clean.copy(nettEarningRm = "999.00")))
        assertFalse(validPaymentRecord(clean.copy(totalDeductionRm = "0.00")))
        assertFalse(validPaymentRecord(clean.copy(petrolRm = "-20.00")))
    }

    @Test fun newFieldsRoundTripThroughExistingBackupJson() {
        val row = requireNotNull(revisedPaymentRecord(
            "2026-11-07", "20", "30", "47.70", "50", "40", "10", "20"
        ))
        val json = Gson().toJson(listOf(row))
        val restored = Gson().fromJson(json, Array<PaymentRecord>::class.java).single()
        assertEquals(row, restored)
        assertEquals("580.00", restored.nettEarningRm)
        assertTrue(validPaymentRecord(restored))
    }
}
