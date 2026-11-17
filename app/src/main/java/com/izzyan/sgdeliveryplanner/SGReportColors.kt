package com.izzyan.sgdeliveryplanner

import kotlin.math.roundToInt

/** Pixel-identical Canvas colors; pure integer constants work in Android and JVM unit tests. */
internal object SGReportColors {
    val Background: Int get() = if (SGThemePreferences.isBurgundy) 0xFF190D13.toInt() else 0xFF081B33.toInt()
    val Secondary: Int get() = if (SGThemePreferences.isBurgundy) 0xFF27111D.toInt() else 0xFF102A4C.toInt()
    val Card: Int get() = if (SGThemePreferences.isBurgundy) 0xFF3B1425.toInt() else 0xFF152F50.toInt()
    val Gold: Int get() = if (SGThemePreferences.isBurgundy) 0xFF9C294C.toInt() else 0xFFD4AF37.toInt()
    val White: Int get() = if (SGThemePreferences.isBurgundy) 0xFFE3E5E8.toInt() else 0xFFFFFFFF.toInt()
    val Silver: Int get() = if (SGThemePreferences.isBurgundy) 0xFFC6BFC5.toInt() else 0xFFD6DEE8.toInt()
    val Divider: Int get() = if (SGThemePreferences.isBurgundy) 0xFF78324A.toInt() else 0xFF29415D.toInt()
    // Approved report panel: 30% opaque Navy card over original Marina Bay imagery.
    val ReportPanel: Int get() = ((SG_CARD_BACKGROUND_ALPHA * 255f).roundToInt() shl 24) or (Background and 0x00FFFFFF)
}
