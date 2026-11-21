package com.izzyan.sgdeliveryplanner

/** B880 visual dimensions in dp. Both overlays live in the viewport bounded
 * by the measured opaque navigation header and arrival panel. The meter
 * occupies the lower-left lane and controls occupy the right lane.
 */
internal object GpsCompactLayout880 {
    const val RAIL_WIDTH_DP=174
    const val RAIL_END_PADDING_DP=8
    const val METER_WIDTH_DP=108
    const val CONTROL_WIDTH_DP=52
    const val CONTROL_HEIGHT_DP=49
    const val CONTROL_ICON_DP=20
    const val CONTROL_LABEL_SP=9
    const val CONTROL_GAP_DP=5
    const val CONTROL_COUNT=6
    const val MIN_LANE_GAP_DP=6
    const val MIN_TOUCH_TARGET_DP=48

    val visibleWidthReductionPercent:Int
        get()=((65-CONTROL_WIDTH_DP)*100)/65
    val visibleHeightReductionPercent:Int
        get()=((61-CONTROL_HEIGHT_DP)*100)/61

    fun lanesDoNotOverlap():Boolean =
        METER_WIDTH_DP+MIN_LANE_GAP_DP+CONTROL_WIDTH_DP <=
            RAIL_WIDTH_DP-RAIL_END_PADDING_DP

    fun controlsVerticalExtentDp():Int =
        CONTROL_COUNT*CONTROL_HEIGHT_DP+(CONTROL_COUNT-1)*CONTROL_GAP_DP

    /** Speed card stays fixed and visible above the bottom panel.
     * Only the right button rail may scroll on unusually short devices.
     */
    fun meterInsideSafeViewport(topInsetDp:Float,bottomInsetDp:Float,
                                screenHeightDp:Float,meterHeightDp:Float):Boolean =
        screenHeightDp-topInsetDp-bottomInsetDp >= meterHeightDp
}
