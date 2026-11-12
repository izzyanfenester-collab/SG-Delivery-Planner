package com.izzyan.sgdeliveryplanner

import androidx.compose.material3.Typography
import org.junit.Assert.*
import org.junit.Test

class PaymentTypography843Test {
    @Test fun dialogTypographyGrowsTwentyFivePercentWithoutGlobalThemeMutation() {
        val original = Typography()
        val bigger = paymentTypography(original)
        assertEquals(original.bodyLarge.fontSize.value * 1.25f, bigger.bodyLarge.fontSize.value, 0.01f)
        assertEquals(original.titleLarge.fontSize.value * 1.25f, bigger.titleLarge.fontSize.value, 0.01f)
        assertEquals(original.labelLarge.fontSize.value * 1.25f, bigger.labelLarge.fontSize.value, 0.01f)
        assertEquals(original.bodySmall.lineHeight.value * 1.25f, bigger.bodySmall.lineHeight.value, 0.01f)
        assertEquals(Typography().bodyLarge, original.bodyLarge)
    }

    @Test fun compactHistoryUsesFiveRowsAndGrowsWithAccessibilityScale() {
        assertEquals(5, PaymentCompactLayout.HistoryVisibleRows)
        for (scale in listOf(1f, 1.25f, 1.5f, 2f)) {
            assertTrue(PaymentDesignC.historyRowHeight(scale).value >= 38f * scale + 6f)
            assertTrue(PaymentDesignC.historyHeaderHeight(scale).value >= 32f * scale + 6f)
        }
    }

    @Test fun existingResponsiveLayoutAndTwoByTwoMonthlySummaryArePreserved() {
        for (width in listOf(320, 360, 400, 480, 600)) {
            assertEquals(width >= 400, PaymentDesignC.useSideBySideCards(width))
            assertFalse(PaymentDesignC.useFourAcrossKpis(width))
        }
    }
}
