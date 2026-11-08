package com.izzyan.sgdeliveryplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RunnerEarningsTest {
    @Test fun correctSgdSnapshotAndNetCalculationDoNotIncludeCOD() {
        val cost = createRunnerExpense("2026-11-08", "Petrol", "12.50", "SGD", "3.20")
        assertNotNull(cost)
        assertEquals(BigDecimal("40.00"), cost!!.rmAmount)
        val payment = PaymentRecord(
            date = "2026-11-08", quantity = 2, commissionPerParcelRm = "5.00",
            totalPaymentRm = "10.00", taxRm = "0.00", othersRm = "0.00",
            grandTotalPaymentRm = "10.00"
        )
        val totals = monthlyRunnerEarnings(listOf(payment), listOf(cost), 2026, 11)
        assertEquals("10.00", totals.grossRm)
        assertEquals("40.00", totals.expensesRm)
        assertEquals("-30.00", totals.estimatedNetRm)
    }

    @Test fun rejectsInvalidExpenseAndPreservesStableIdOnRepeatedRestore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("runner_expense_ledger", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val ledger = RunnerExpenseLedger(context)
        val first = createRunnerExpense("2026-11-08", "Parking", "4.25", "MYR", "1.00")!!
        assertTrue(ledger.append(first))
        assertEquals(0, ledger.mergeRestored(listOf(first)))
        assertEquals(first, ledger.read().single())
        assertFalse(validRunnerExpense(first.copy(amount = "-3.00")))
        try {
            ledger.mergeRestored(listOf(first.copy(amount = "44.25")))
            fail("Conflicting ID must not overwrite saved record")
        } catch (expected: IllegalArgumentException) { /* expected */ }
        assertEquals(first, ledger.read().single())
        prefs.edit().clear().commit()
    }

    @Test fun otherMonthsStaySeparateFromSelectedSummary() {
        val november = createRunnerExpense("2026-11-08", "Toll", "3.00", "MYR", "1.00")!!
        val october = createRunnerExpense("2026-10-31", "Toll", "7.00", "MYR", "1.00")!!
        val totals = monthlyRunnerEarnings(emptyList(), listOf(october, november), 2026, 11)
        assertEquals("3.00", totals.expensesRm)
        assertEquals(1, totals.expenseCount)
    }
}
