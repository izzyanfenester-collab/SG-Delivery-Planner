package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests: new Payment drafts have 30/10/30, saved payments never change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PaymentDefaultDeductionsTest {
    @Test fun freshPaymentDraftUsesExactlyTheThreeRequestedDefaults() {
        assertEquals("30.00", PaymentExpense.PETROL.defaultRm)
        assertEquals("10.00", PaymentExpense.TOUCH_AND_GO.defaultRm)
        assertEquals("30.00", PaymentExpense.AUTOPASS.defaultRm)
        assertEquals("70.00", calculatePaymentFinancials(
            "70.00", "0.00", PaymentExpense.PETROL.defaultRm,
            PaymentExpense.TOUCH_AND_GO.defaultRm, PaymentExpense.AUTOPASS.defaultRm
        )?.totalDeductionRm)
    }

    @Test fun taxRemainsTheFourthDeductionWithoutDoubleCounting() {
        val record = requireNotNull(revisedPaymentRecord(
            "2026-11-09", "20", "30.00", "45.64", "0",
            PaymentExpense.PETROL.defaultRm,
            PaymentExpense.TOUCH_AND_GO.defaultRm,
            PaymentExpense.AUTOPASS.defaultRm
        ))
        assertEquals("600.00", record.totalPaymentRm)
        assertEquals("645.64", record.grandTotalPaymentRm)
        assertEquals("115.64", record.totalDeductionRm)
        assertEquals("530.00", record.nettEarningRm)
        assertTrue(validPaymentRecord(record))
    }

    @Test fun editsAreIndependentAndZeroDeductionIsValid() {
        val editedPetrol = requireNotNull(revisedPaymentRecord(
            "2026-11-09", "2", "50.00", "0.00", "0.00",
            "0.00", PaymentExpense.TOUCH_AND_GO.defaultRm,
            PaymentExpense.AUTOPASS.defaultRm
        ))
        assertEquals("0.00", editedPetrol.petrolRm)
        assertEquals("10.00", editedPetrol.touchAndGoRm)
        assertEquals("30.00", editedPetrol.autopassRm)
        assertEquals("40.00", editedPetrol.totalDeductionRm)
        assertEquals("60.00", editedPetrol.nettEarningRm)
        assertNull(revisedPaymentRecord("2026-11-09", "2", "50", "0", "0", "-1.00", "10", "30"))
        assertNull(revisedPaymentRecord("2026-11-09", "2", "50", "0", "0", "1.999", "10", "30"))
    }

    @Test fun historicalRecordsKeepTheirPreviouslySavedDeductions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val old = requireNotNull(revisedPaymentRecord(
                "2026-11-07", "2", "25", "5", "0", "1", "2", "3"
            ))
            val ledger = PaymentLedger(context)
            assertTrue(ledger.append(old))
            // Initializing a new draft using default values must not mutate existing history.
            val fresh = requireNotNull(revisedPaymentRecord(
                "2026-11-09", "2", "25", "5", "0",
                PaymentExpense.PETROL.defaultRm,
                PaymentExpense.TOUCH_AND_GO.defaultRm,
                PaymentExpense.AUTOPASS.defaultRm
            ))
            assertEquals(listOf(old), PaymentLedger(context).read())
            assertEquals("1.00", ledger.read().single().petrolRm)
            assertEquals("2.00", ledger.read().single().touchAndGoRm)
            assertEquals("3.00", ledger.read().single().autopassRm)
            assertEquals("70.00", fresh.petrolRm!!.toBigDecimal()
                .add(fresh.touchAndGoRm!!.toBigDecimal())
                .add(fresh.autopassRm!!.toBigDecimal()).toPlainString())
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun legacyPaymentsWithNoExpensesStillDeriveZeroForMissingFields() {
        val historical = PaymentRecord("2026-11-07", 2, "10.00", "20.00",
            "5.00", "0.00", "25.00")
        assertEquals("5.00", financialsForRecord(historical).totalDeductionRm)
        assertEquals("20.00", financialsForRecord(historical).nettEarningRm)
    }
}
