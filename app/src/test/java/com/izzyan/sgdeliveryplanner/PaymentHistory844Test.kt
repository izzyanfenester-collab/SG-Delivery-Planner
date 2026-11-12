package com.izzyan.sgdeliveryplanner

import org.junit.Assert.*
import org.junit.Test

class PaymentHistory844Test {
    @Test fun newPageStartsCollapsedAndTogglesBothWays() {
        val state = PaymentHistoryExpansionState()
        assertFalse(state.expanded)
        state.toggle()
        assertTrue(state.expanded)
        state.toggle()
        assertFalse(state.expanded)
    }

    @Test fun reopeningPaymentStartsMinimizedWithoutMutatingPreviousInstance() {
        val previousPage = PaymentHistoryExpansionState()
        previousPage.toggle()
        assertTrue(previousPage.expanded)
        val reopenedPage = PaymentHistoryExpansionState()
        assertFalse(reopenedPage.expanded)
        assertTrue(previousPage.expanded)
    }

    @Test fun fiveRowsAndLargerFontsLayoutRulesAreUnchanged() {
        assertEquals(5, PaymentCompactLayout.HistoryVisibleRows)
        assertEquals(44f, PaymentDesignC.historyRowHeight(1f).value, 0.01f)
        assertEquals(54f, PaymentDesignC.historyRowHeight(1.25f).value, 0.01f)
        assertEquals(40f, PaymentDesignC.HistoryHeaderHeight.value, 0.01f)
    }
}
