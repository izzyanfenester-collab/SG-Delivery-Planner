package com.izzyan.sgdeliveryplanner

/**
 * Build #869: presentation classification, never legal certification.
 *
 * The source workbook has 91 provisional numeric entries and 9 blanks.
 * A high-confidence GPS camera match is NOT evidence of an official speed limit.
 * Only SpeedLimitTracker868's independently verified direction-specific segment
 * gate may populate verifiedKmh, and only that value can cause overspeedVisual.
 */
internal enum class SpeedLimitBadge869(val label:String) {
    VERIFIED("VERIFIED"),
    REFERENCE("REFERENCE"),
    UNVERIFIED("UNVERIFIED"),
    UNKNOWN("UNKNOWN")
}

internal data class SpeedLimitPresentation869(
    val numberKmh:Int?,
    val badge:SpeedLimitBadge869,
    val highlightOverspeed:Boolean,
    val note:String?
) {
    val hasNumericLimit get() = numberKmh != null
}

/** A single tested source of truth for the circular number and its truthful label. */
internal object SpeedLimitDisplay869 {
    fun render(state:SpeedLimitState868):SpeedLimitPresentation869 {
        val verified=state.verifiedKmh?.takeIf{it in 20..110}
        if(verified != null) return SpeedLimitPresentation869(
            numberKmh=verified,
            badge=SpeedLimitBadge869.VERIFIED,
            highlightOverspeed=state.overspeedVisual,
            note=null
        )
        val reference=state.referenceKmh?.takeIf{it in 20..110}
        if(reference == null) return SpeedLimitPresentation869(
            numberKmh=null, badge=SpeedLimitBadge869.UNKNOWN,
            highlightOverspeed=false, note=null
        )
        // Preserve the raw original Malay status on SpeedLimitState868.
        // A "Padanan..." value is a *candidate* speed-limit reference, never
        // a source-confirmed speed limit or a road-wide speed restriction.
        val label=if(state.referenceStatus.orEmpty().contains("rujukan",ignoreCase=true))
            SpeedLimitBadge869.REFERENCE else SpeedLimitBadge869.UNVERIFIED
        return SpeedLimitPresentation869(
            numberKmh=reference,
            badge=label,
            highlightOverspeed=false,
            note="CAMERA-AREA REF"
        )
    }
}
