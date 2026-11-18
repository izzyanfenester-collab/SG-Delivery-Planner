package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation only: original navigation intent supplies the selected delivery's
 * real Place.block/Place.postal; never fabricate a block from a house/street number.
 */
internal data class DestinationHeading870(val block:String?,val postal:String?,val name:String?) {
    val blockLine:String? get()=block?.let { "Block $it" }
    val postalLine:String? get()=postal?.let { "($it)" }
    val full:String get()=when {
        blockLine!=null && postalLine!=null -> "$blockLine $postalLine"
        blockLine!=null -> blockLine!!
        postal!=null -> postal
        !name.isNullOrBlank() -> name
        else -> "Destination"
    }
}

internal fun destinationHeading870(stop:Place):DestinationHeading870 {
    val postal=stop.postal.trim().takeIf { it.matches(Regex("""\d{6}""")) }
    // Primary source is the saved block field. Ignore unverified place names or
    // ordinary street/house numbers; only explicit "Block"/"Blk" in an address qualifies.
    val bare=Regex("""(?i)^\d{1,6}[a-z]?$""")
    val explicit=Regex("""(?i)\b(?:blk|block)\s*#?\s*(\d{1,6}[a-z]?)\b""")
    val raw=stop.block.trim()
    val block=when {
        bare.matches(raw) -> raw.uppercase()
        else -> explicit.find(raw)?.groupValues?.get(1)?.uppercase()
            ?: explicit.find(stop.address)?.groupValues?.get(1)?.uppercase()
    }
    val name=stop.address.trim().takeIf { it.isNotBlank() }
        ?: stop.area.trim().takeIf { it.isNotBlank() }
    return DestinationHeading870(block,postal,name)
}

/** Includes system font scale because the text measurer uses actual sp dimensions. */
internal fun destinationHeadingFitsRow870(
    availableWidthPx:Int, arrivalWidthPx:Int, destinationWidthPx:Int,gapPx:Int
)=arrivalWidthPx.toLong()+destinationWidthPx+gapPx <= availableWidthPx.toLong()

@Composable
internal fun NavigationDestinationHeading870(
    destination:DestinationHeading870,
    modifier:Modifier=Modifier,
    accent:Color=SGColors.SelectedGold
) {
    val headlineStyle=TextStyle(fontSize=26.sp,fontWeight=FontWeight.ExtraBold,
        lineHeight=30.sp,letterSpacing=0.sp)
    val labelStyle=TextStyle(fontSize=13.sp,fontWeight=FontWeight.Bold)
    val textMeasurer=rememberTextMeasurer()
    val density=LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available=with(density){maxWidth.roundToPx()}
        val labelWidth=textMeasurer.measure(AnnotatedString("ARRIVAL"),
            style=labelStyle,maxLines=1,softWrap=false).size.width
        val fullWidth=textMeasurer.measure(AnnotatedString(destination.full),
            style=headlineStyle,maxLines=1,softWrap=false).size.width
        val gap=with(density){12.dp.roundToPx()}
        val shared=destinationHeadingFitsRow870(available,labelWidth,fullWidth,gap)
        if(shared) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceBetween) {
                Text("ARRIVAL",color=SGColors.Silver,style=labelStyle)
                Text(destination.full,color=accent,style=headlineStyle,softWrap=false)
            }
        } else {
            Column(verticalArrangement=Arrangement.spacedBy(1.dp)) {
                Text("ARRIVAL",color=SGColors.Silver,style=labelStyle)
                if(fullWidth<=available || destination.blockLine==null || destination.postal==null) {
                    Text(destination.full,color=accent,style=headlineStyle,softWrap=true)
                } else {
                    // Separate lines instead of reducing the font size. No clipping,
                    // maxLines cap or ellipsis even at large Android font scale.
                    Text(destination.blockLine!!,color=accent,style=headlineStyle,softWrap=true)
                    Text(destination.postalLine!!,color=accent,style=headlineStyle,softWrap=true)
                }
            }
        }
    }
}
