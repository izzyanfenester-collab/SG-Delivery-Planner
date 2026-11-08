package com.izzyan.sgdeliveryplanner

import kotlin.math.roundToInt

/** Pixel-identical Canvas colors; pure integer constants work in Android and JVM unit tests. */
internal object SGReportColors {
    val Background: Int = 0xFF081B33.toInt()
    val Secondary: Int = 0xFF102A4C.toInt()
    val Card: Int = 0xFF152F50.toInt()
    val Gold: Int = 0xFFD4AF37.toInt()
    val White: Int = 0xFFFFFFFF.toInt()
    val Silver: Int = 0xFFD6DEE8.toInt()
    val Divider: Int = 0xFF29415D.toInt()
    // Approved report panel: 30% opaque Navy card over original Marina Bay imagery.
    val ReportPanel: Int = ((SG_CARD_BACKGROUND_ALPHA * 255f).roundToInt() shl 24) or (Background and 0x00FFFFFF)
}
