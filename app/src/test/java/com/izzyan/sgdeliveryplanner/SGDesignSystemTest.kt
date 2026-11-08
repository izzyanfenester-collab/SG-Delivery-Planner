package com.izzyan.sgdeliveryplanner

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/** Palette regression: these checks guard all core design tokens against drift. */
class SGDesignSystemTest {
    @Test fun paletteUsesApprovedBrandColors() {
        assertEquals(Color(0xFF081B33), SGColors.Background)
        assertEquals(Color(0xFF102A4C), SGColors.SecondaryBackground)
        assertEquals(0.30f, SG_CARD_BACKGROUND_ALPHA, 0.0001f)
        assertEquals(SG_CARD_BACKGROUND_ALPHA, SGColors.Card.alpha, 0.001f)
        assertEquals(Color(0xFF081B33).copy(alpha = SG_CARD_BACKGROUND_ALPHA), SGColors.Card)
        assertEquals(Color(0xFF152F50), SGColors.SolidCard)
        assertEquals(Color(0xFFD4AF37), SGColors.Gold)
        assertEquals(Color(0xFFF1D57A), SGColors.SelectedGold)
        assertEquals(Color.White, SGColors.White)
        assertEquals(Color(0xFFD6DEE8), SGColors.Silver)
        assertEquals(Color(0xFF29415D), SGColors.Divider)
        assertEquals(Color(0xFF728096), SGColors.Disabled)
    }

    @Test fun previousSharedNamesNowUseSameTokens() {
        assertEquals(SGColors.Background, PremiumNavy)
        assertEquals(SGColors.SecondaryBackground, PremiumRoyal)
        assertEquals(SGColors.Gold, PremiumGold)
        assertEquals(SGColors.Success, PremiumEmerald)
        assertEquals(SGColors.Warning, PremiumAmber)
    }

    @Test fun reportBitmapColorsArePixelEquivalent() {
        assertEquals(0xFF081B33.toInt(), SGReportColors.Background)
        assertEquals(0xFF152F50.toInt(), SGReportColors.Card)
        assertEquals(0x4D081B33, SGReportColors.ReportPanel)
        assertEquals(0xFFD4AF37.toInt(), SGReportColors.Gold)
        assertEquals(0xFFFFFFFF.toInt(), SGReportColors.White)
        assertEquals(0xFFD6DEE8.toInt(), SGReportColors.Silver)
    }

    @Test fun sizingUsesFourDpGridAndAccessibleControlTargets() {
        assertEquals(4f, SGDimensions.Tight.value, 0.01f)
        assertEquals(8f, SGDimensions.Compact.value, 0.01f)
        assertEquals(12f, SGDimensions.Small.value, 0.01f)
        assertEquals(16f, SGDimensions.Normal.value, 0.01f)
        assertEquals(24f, SGDimensions.Large.value, 0.01f)
        assertEquals(48f, SGDimensions.Touch.value, 0.01f)
        assertEquals(16f, SGDimensions.RadiusCard.value, 0.01f)
    }
}
