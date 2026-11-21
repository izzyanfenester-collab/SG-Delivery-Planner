package com.izzyan.sgdeliveryplanner

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Display data only. A unit belongs to one selected delivery address, never a postal lookup. */
internal data class DestinationHeading873(
    val block:String?,val unit:String?,val postal:String?,val name:String?
) {
    val primaryLine:String? get()=listOfNotNull(block?.let{"Block $it"},unit)
        .takeIf{it.isNotEmpty()}?.joinToString(", ")
    val postalLine:String? get()=postal?.let{if(primaryLine==null)it else "($it)"}
    val full:String get()=listOfNotNull(primaryLine,postalLine).joinToString(" ")
        .ifBlank{name?.takeIf{it.isNotBlank()}?:"Destination"}
}

/** Require an explicit #floor-unit token. Bare road/floor/count/postal numbers
 * are never promoted to unit numbers. Preserve the supplied digits, case and suffix.
 * Multiple different units in one address are ambiguous: do not choose one. */
internal fun deliveryUnitNumber873(address:String):String? {
    val text=address.replace(Regex("""https?://\S+""",RegexOption.IGNORE_CASE)," ")
    val unit=Regex("""(?<![\p{L}\p{N}#])#(?:[Bb]?\d{1,3})-\d{1,5}[A-Za-z]?(?![\p{L}\p{N}-])""")
    val values=unit.findAll(text).filter { match ->
        val before=text.take(match.range.first).takeLast(32)
        !Regex("""(?i)\b(?:parcel|parcels|qty|quantity|pcs|pieces|order|orders|package|packages|floor|level|storey)\s*[:=]?\s*$""")
            .containsMatchIn(before)
    }.map{it.value}.distinct().toList()
    return values.singleOrNull()
}

/** Null address marks a custom/End Place handoff. Keep its previous presentation.
 * An empty actual delivery address must not borrow a unit from a geocoded Place. */
internal fun destinationHeading873(place:Place,deliveryAddress:String?):DestinationHeading873 {
    val legacy=destinationHeading870(if(deliveryAddress==null)place else place.copy(address=deliveryAddress))
    return DestinationHeading873(legacy.block,
        if(deliveryAddress!=null && legacy.postal!=null)deliveryUnitNumber873(deliveryAddress) else null,
        legacy.postal,legacy.name)
}

/** System font scaling is included in both measurements. Stack rows when two
 * readable columns cannot fit; never reduce the destination's 26sp type size. */
internal fun destinationColumnsFit873(available:Int,arrival:Int,destinationToken:Int,gap:Int)=
    arrival.toLong()+destinationToken+gap<=available.toLong()

@Composable
internal fun NavigationDestinationPanel873(
    heading:DestinationHeading873,
    eta:String,
    remainingSummary:String,
    trafficLabel:String?,
    onDelivery:()->Unit,
    speedMeters:(@Composable ()->Unit)?=null,
    modifier:Modifier=Modifier,
    accent:Color=SGColors.SelectedGold
) {
    val headline=TextStyle(fontSize=26.sp,fontWeight=FontWeight.ExtraBold,
        lineHeight=30.sp,letterSpacing=0.sp,textAlign=TextAlign.End)
    val arrival=TextStyle(fontSize=13.sp,fontWeight=FontWeight.Bold)
    val etaStyle=TextStyle(fontSize=29.sp,lineHeight=33.sp,fontWeight=FontWeight.ExtraBold)
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    @Composable fun Arrival(modifier:Modifier=Modifier) {
        Column(modifier.testTag("gps-arrival"),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text("ARRIVAL",color=SGColors.Silver,style=arrival)
            Text(eta,color=Color.White,style=etaStyle,softWrap=true,
                modifier=Modifier.testTag("gps-eta"))
        }
    }
    @Composable fun Destination(modifier:Modifier=Modifier) {
        Column(modifier.testTag("gps-destination"),horizontalAlignment=Alignment.End,
            verticalArrangement=Arrangement.spacedBy(1.dp)) {
            heading.primaryLine?.let {
                Text(it,color=accent,style=headline,softWrap=true,
                    modifier=Modifier.fillMaxWidth().testTag("gps-destination-primary"))
            }
            heading.postalLine?.let {
                Text(it,color=accent,style=headline,softWrap=true,
                    modifier=Modifier.fillMaxWidth().testTag("gps-destination-postal"))
            }
            if(heading.primaryLine==null && heading.postalLine==null)
                Text(heading.full,color=accent,style=headline,softWrap=true,
                    modifier=Modifier.fillMaxWidth().testTag("gps-destination-name"))
        }
    }
    Column(modifier.fillMaxWidth().testTag("gps-bottom-panel")
        .padding(horizontal=12.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("gps-destination-header")) {
            fun width(text:String,style:TextStyle)=measurer.measure(AnnotatedString(text),style=style,
                maxLines=1,softWrap=false).size.width
            val arrivalWidth=maxOf(width("ARRIVAL",arrival),width(eta,etaStyle))
            val tokenWidth=listOfNotNull(heading.postalLine,heading.unit,
                heading.block,heading.name?.takeIf{heading.primaryLine==null && heading.postal==null})
                .maxOfOrNull{width(it,headline)}?:0
            val available=with(density){maxWidth.roundToPx()}
            val gap=with(density){12.dp.roundToPx()}
            if(destinationColumnsFit873(available,arrivalWidth,tokenWidth,gap)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top,
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Arrival(Modifier.width(with(density){arrivalWidth.toDp()}))
                    Destination(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Arrival()
                    Destination(Modifier.fillMaxWidth())
                }
            }
        }
        @Composable fun Remaining(modifier:Modifier=Modifier) {
            Column(modifier.testTag("gps-bottom-distance")) {
                Text(remainingSummary,color=SGColors.Silver,fontSize=17.sp,
                    fontWeight=FontWeight.Bold,lineHeight=21.sp,softWrap=true,
                    modifier=Modifier.fillMaxWidth().testTag("gps-remaining-summary"))
                trafficLabel?.let{Text(it,color=SGColors.Silver,fontSize=11.sp)}
            }
        }
        @Composable fun DeliveryAction() {
            FilledTonalButton(onClick=onDelivery,
                modifier=Modifier.heightIn(min=48.dp).testTag("gps-delivery-button"),
                colors=ButtonDefaults.filledTonalButtonColors(
                    containerColor=SGColors.SecondaryBackground,contentColor=Color.White),
                contentPadding=PaddingValues(horizontal=10.dp,vertical=6.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,contentDescription=null,modifier=Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("Delivery",fontSize=12.sp,fontWeight=FontWeight.Bold)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().testTag("gps-bottom-speed-actions")) {
            val inline=speedMeters!=null &&
                NavigationBottomSpeedLayout881.inlineWithActions(maxWidth.value,density.fontScale)
            if(inline) {
                // Normal screens: arrival distance | 2 SIDE-BY-SIDE gauges | Delivery.
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Remaining(Modifier.weight(1f))
                    speedMeters?.invoke()
                    DeliveryAction()
                }
            } else {
                // Small phones / large fonts: gauges may move to an EXTRA ROW
                // INSIDE the bottom panel, but never stack one above the other.
                // Arrival, destination, distance and Delivery are all preserved.
                Column(Modifier.fillMaxWidth(),
                    verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(speedMeters!=null) {
                        Row(Modifier.fillMaxWidth().testTag("gps-bottom-meters-row"),
                            horizontalArrangement=Arrangement.Center,
                            verticalAlignment=Alignment.CenterVertically) {
                            speedMeters()
                        }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Remaining(Modifier.weight(1f))
                        DeliveryAction()
                    }
                }
            }
        }
    }
}
