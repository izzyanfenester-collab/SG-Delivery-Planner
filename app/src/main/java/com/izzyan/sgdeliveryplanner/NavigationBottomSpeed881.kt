package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** B881: both gauges are siblings in one HORIZONTAL row, exclusively in the
 * opaque bottom arrival panel. Never install them as a floating map overlay.
 */
internal object NavigationBottomSpeedLayout881 {
    const val SPEED_WIDTH_DP=70
    const val SPEED_LIMIT_WIDTH_DP=64
    const val SPEED_LIMIT_SIGN_DP=52
    const val GAUGE_SPACING_DP=8
    const val MIN_REMAINING_DP=105
    const val MIN_DELIVERY_DP=103
    const val ACTION_SPACING_DP=8

    val pairWidthDp:Int get()=SPEED_WIDTH_DP+SPEED_LIMIT_WIDTH_DP+GAUGE_SPACING_DP

    /** Retain two horizontal gauges even on small screens: only the OTHER
     * panel actions may move to another internal row. Scaled fonts are
     * accounted for in the inline/extra-row breakpoint.
     */
    fun inlineWithActions(widthDp:Float,fontScale:Float):Boolean {
        val scale=fontScale.coerceAtLeast(1f)
        val needed=pairWidthDp+(MIN_REMAINING_DP+MIN_DELIVERY_DP)*scale+
            ACTION_SPACING_DP*2
        return widthDp>=needed
    }
}

/** This component intentionally does not perform GPS or road-limit calculations.
 * It displays the same real-time values from #879 that previously powered the
 * floating Speed Meter, including the independent overspeed RED UI state.
 */
@Composable
internal fun NavigationBottomSpeedMeters881(
    currentSpeedKmh:Int?,
    speedLimit:RoadSpeedState875,
    redOverspeed:Boolean,
    showSpeedLimit:Boolean,
    modifier:Modifier=Modifier
) {
    Row(
        modifier.testTag("gps-bottom-speed-pair"),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(NavigationBottomSpeedLayout881.GAUGE_SPACING_DP.dp)
    ) {
        Surface(
            color=SGColors.Card,
            shape=RoundedCornerShape(SGDimensions.RadiusSmall),
            modifier=Modifier.width(NavigationBottomSpeedLayout881.SPEED_WIDTH_DP.dp)
                .testTag("gps-bottom-current-speed")
        ) {
            Column(Modifier.padding(horizontal=3.dp,vertical=6.dp),
                horizontalAlignment=Alignment.CenterHorizontally) {
                val foreground=if(redOverspeed)Color(0xFFFF3030) else Color.White
                Text(currentSpeedKmh?.toString()?:"--",color=foreground,
                    fontSize=25.sp,fontWeight=FontWeight.Bold,
                    maxLines=1,softWrap=false)
                Text("km/h",color=if(redOverspeed)Color(0xFFFF3030) else SGColors.Silver,
                    fontSize=11.sp,fontWeight=FontWeight.Bold,maxLines=1)
            }
        }
        // Only a genuine numeric limit may appear in the speed-limit sign.
        // Source labels, evidence dates and diagnostic notes remain in the
        // RoadSpeedState875 model for safety but must not be rendered.
        if(showSpeedLimit && speedLimit.hasNumericLimit && speedLimit.numberKmh!=null) {
            val limitKmh=checkNotNull(speedLimit.numberKmh)
            Column(
                Modifier.width(NavigationBottomSpeedLayout881.SPEED_LIMIT_WIDTH_DP.dp)
                    .testTag("gps-bottom-speed-limit"),
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                Surface(
                    color=Color.White,
                    shape=CircleShape,
                    border=BorderStroke(
                        if(speedLimit.highlightOverspeed)4.dp else 3.dp,
                        if(speedLimit.highlightOverspeed)Color(0xFFFF3B30) else Color(0xFFD52C2C)
                    ),
                    modifier=Modifier.size(NavigationBottomSpeedLayout881.SPEED_LIMIT_SIGN_DP.dp)
                        .testTag("gps-bottom-speed-limit-sign")
                ) {
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        Text(
                            limitKmh.toString(),
                            color=Color.Black,
                            fontSize=if(limitKmh>=100)25.sp else 32.sp,
                            fontWeight=FontWeight.Black,
                            maxLines=1,
                            softWrap=false,
                            modifier=Modifier.testTag("gps-bottom-speed-limit-number")
                        )
                    }
                }
            }
        }
    }
}
