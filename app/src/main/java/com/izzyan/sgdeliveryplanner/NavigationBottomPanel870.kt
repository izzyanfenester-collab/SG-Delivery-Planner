package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The original Bottom Navigation Panel only; no GPS, ETA or delivery writes. */
@Composable
internal fun NavigationBottomPanel870(
    destination:Place,
    arrivalTime:String,
    remainingSummary:String,
    localTrafficLabel:String?,
    onDeliveryClick:()->Unit
) {
    val label=remember(destination.postal,destination.block,destination.address) {
        NavigationDestinationFormatting870.fromDestination(destination)
    }
    Surface(color=PremiumNavy,shape=RoundedCornerShape(SGDimensions.RadiusCard)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=10.dp)) {
            val narrow=maxWidth<310.dp || LocalDensity.current.fontScale>=1.45f
            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                if(narrow) {
                    Text("ARRIVAL",color=SGColors.Silver,fontSize=13.sp,
                        fontWeight=FontWeight.Bold)
                    NavigationDestinationTitle870(label,Modifier.fillMaxWidth(),endAligned=false)
                } else {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("ARRIVAL",color=SGColors.Silver,fontSize=13.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(top=7.dp))
                        NavigationDestinationTitle870(label,Modifier.weight(1f),endAligned=true)
                    }
                }
                Text(arrivalTime,color=Color.White,fontSize=29.sp,lineHeight=34.sp,
                    fontWeight=FontWeight.ExtraBold)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    // 17sp is a floor: wrap the distance/duration instead of
                    // reducing text to unreadably small fonts.
                    Text(remainingSummary,modifier=Modifier.weight(1f),color=SGColors.Silver,
                        fontSize=17.sp,lineHeight=23.sp,fontWeight=FontWeight.Bold,
                        softWrap=true)
                    FilledTonalButton(onClick=onDeliveryClick,
                        modifier=Modifier.heightIn(min=48.dp),
                        colors=ButtonDefaults.filledTonalButtonColors(
                            containerColor=SGColors.SecondaryBackground,
                            contentColor=Color.White),
                        contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription=null,modifier=Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Delivery",fontSize=13.sp,fontWeight=FontWeight.Bold)
                    }
                }
                if(localTrafficLabel!=null) {
                    Text(localTrafficLabel,color=SGColors.Silver,fontSize=11.sp)
                }
            }
        }
    }
}

@Composable
private fun NavigationDestinationTitle870(
    label:NavigationDestinationLabel870,
    modifier:Modifier=Modifier,
    endAligned:Boolean
) {
    val largeStyle=TextStyle(
        color=SGColors.SelectedGold,
        fontSize=25.sp,
        lineHeight=30.sp,
        fontWeight=FontWeight.ExtraBold,
        letterSpacing=0.sp
    )
    BoxWithConstraints(modifier) {
        val textMeasurer=rememberTextMeasurer()
        val availablePx=with(LocalDensity.current){maxWidth.toPx()}
        val singleLineWidth=textMeasurer.measure(
            AnnotatedString(label.singleLine),
            style=largeStyle,maxLines=1,softWrap=false
        ).size.width
        val align=if(endAligned)TextAlign.End else TextAlign.Start
        if(singleLineWidth<=availablePx) {
            Text(label.singleLine,modifier=Modifier.fillMaxWidth(),style=largeStyle,
                softWrap=false,maxLines=1,textAlign=align)
        } else {
            // Never clip or ellipsize the 6-digit postal. An independently measured
            // full-size block and postal are shown on their own lines.
            Column(Modifier.fillMaxWidth(),
                horizontalAlignment=if(endAligned)Alignment.End else Alignment.Start) {
                Text(label.primaryLine,style=largeStyle,textAlign=align,
                    softWrap=true)
                label.secondaryLine?.let { postal ->
                    Text(postal,style=largeStyle,textAlign=align,softWrap=true)
                }
            }
        }
    }
}
