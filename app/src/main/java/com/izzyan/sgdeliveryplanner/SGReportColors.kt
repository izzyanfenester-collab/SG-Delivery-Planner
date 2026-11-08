package com.izzyan.sgdeliveryplanner

/** Pixel-identical Canvas colors; pure integer constants work in Android and JVM unit tests. */
internal object SGReportColors {
    const val Background: Int = 0xFF081B33.toInt()
    const val Secondary: Int = 0xFF102A4C.toInt()
    const val Card: Int = 0xFF152F50.toInt()
    const val Gold: Int = 0xFFD4AF37.toInt()
    const val White: Int = 0xFFFFFFFF.toInt()
    const val Silver: Int = 0xFFD6DEE8.toInt()
    const val Divider: Int = 0xFF29415D.toInt()
    // Approved report panel: 30% opaque Navy card over original Marina Bay imagery.
    const val ReportPanel: Int = 0x4D152F50
}
