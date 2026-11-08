package com.izzyan.sgdeliveryplanner

import android.graphics.Color as AndroidColor

/** Shared Android Canvas palette for report PNGs and schedule JPEGs. */
internal object SGReportColors {
    val Background: Int = AndroidColor.rgb(8, 27, 51)
    val Secondary: Int = AndroidColor.rgb(16, 42, 76)
    val Card: Int = AndroidColor.rgb(21, 47, 80)
    val Gold: Int = AndroidColor.rgb(212, 175, 55)
    val White: Int = AndroidColor.WHITE
    val Silver: Int = AndroidColor.rgb(214, 222, 232)
    val Divider: Int = AndroidColor.rgb(41, 65, 93)
    // Retains the approved image-showing-through report panel opacity.
    val ReportPanel: Int = AndroidColor.argb(77, 21, 47, 80)
}
