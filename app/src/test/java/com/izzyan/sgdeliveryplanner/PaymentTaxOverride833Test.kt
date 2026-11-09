package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PaymentTaxOverride833Test {
    @Test fun reportTaxIsDefaultWithManualTaxTakingPriority() {
        assertEquals("25.50", effectivePaymentTaxRm("25.50", null))
        assertEquals("0.00", effectivePaymentTaxRm(null, null))
        assertEquals("12.25", effectivePaymentTaxRm("25.50", "12.25"))
        assertEquals("3.00", effectivePaymentTaxRm(null, "3.00"))
    }

    @Test fun taxOverridesPersistByDateAndResetToReport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
        prefs.edit().remove("tax_overrides_by_date").commit()
        val first = LocalDate.of(2026, 11, 9)
        val second = LocalDate.of(2026, 11, 10)
        try {
            val ledger = PaymentTaxOverrideLedger(context)
            assertTrue(ledger.save(first, "25.5"))
            assertTrue(ledger.save(second, "0"))
            val reopened = PaymentTaxOverrideLedger(context)
            assertEquals("25.50", reopened.read()[first.toString()])
            assertEquals("0.00", reopened.read()[second.toString()])
            assertEquals("25.50", effectivePaymentTaxRm("13.00", reopened.read()[first.toString()]))
            assertTrue(reopened.resetToReport(first))
            assertFalse(reopened.read().containsKey(first.toString()))
            assertEquals("13.00", effectivePaymentTaxRm("13.00", reopened.read()[first.toString()]))
            assertEquals("0.00", reopened.read()[second.toString()])
        } finally {
            prefs.edit().remove("tax_overrides_by_date").commit()
        }
    }

    @Test fun changesToTaxRecalculateOneSynchronizedDeduction() {
        val defaultRecord = requireNotNull(revisedPaymentRecord(
            "2026-11-09", "2", "20.00", "25.50", "5.00", "3.00", "2.00", "1.00"
        ))
        val changed = requireNotNull(revisedPaymentRecord(
            "2026-11-09", "2", "20.00", "10.25", "5.00", "3.00", "2.00", "1.00"
        ))
        assertEquals("70.50", defaultRecord.grandTotalPaymentRm)
        assertEquals("31.50", defaultRecord.totalDeductionRm)
        assertEquals("39.00", defaultRecord.nettEarningRm)
        assertEquals("55.25", changed.grandTotalPaymentRm)
        assertEquals("16.25", changed.totalDeductionRm)
        assertEquals("39.00", changed.nettEarningRm)
        assertEquals(PaymentNetTotals("55.25", "16.25", "39.00"),
            monthlyPaymentNetTotals(listOf(changed), 2026, 11))
    }

    @Test fun invalidOverridesRejectedAndMergeIsIdempotent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("payment_ledger", Context.MODE_PRIVATE)
        prefs.edit().remove("tax_overrides_by_date").commit()
        try {
            val ledger = PaymentTaxOverrideLedger(context)
            assertFalse(ledger.save(LocalDate.of(2026, 11, 9), "-1.00"))
            assertFalse(validTaxOverrides(mapOf("not-a-date" to "12.00")))
            assertFalse(validTaxOverrides(mapOf("2026-11-09" to "oops")))
            assertEquals(1, ledger.mergeRestored(mapOf("2026-11-09" to "12.00")))
            assertEquals(0, ledger.mergeRestored(mapOf("2026-11-09" to "40.00")))
            assertEquals("12.00", ledger.read()["2026-11-09"])
        } finally {
            prefs.edit().remove("tax_overrides_by_date").commit()
        }
    }
}
